package no.lionflow.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trips worked out from the car's own reports (odometer, position, battery), for cars whose trips Peugeot's server
 * doesn't share with other apps. It doesn't matter who drives or which phone is along: LionFlow checks the car
 * in the background, and each time the odometer has moved, that drive is logged.
 * When the car reports only after a drive, the start time is estimated from the distance.
 */
object CarTrips {
    private class Snap(val t: Long, val odo: Double, val lat: Double?, val lon: Double?, val pct: Double?, val res: Double?, val cap: Double?) {
        fun json() = JSONObject().put("t", t).put("odo", odo).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
            .put("pct", pct ?: JSONObject.NULL).put("res", res ?: JSONObject.NULL).put("cap", cap ?: JSONObject.NULL)
        companion object {
            fun of(o: JSONObject?): Snap? = o?.let {
                fun d(k: String) = if (it.isNull(k)) null else it.optDouble(k).takeIf { v -> !v.isNaN() }
                Snap(it.getLong("t"), it.getDouble("odo"), d("lat"), d("lon"), d("pct"), d("res"), d("cap"))
            }
        }
    }

    private const val MAX = 400

    @Synchronized
    fun observe(st: VehicleStatus) {
        val p = ChargePower.store() ?: return
        val odo = st.odometerKm ?: return
        if (odo <= 0) return
        val now = Snap(st.updatedAtMillis ?: System.currentTimeMillis(), odo, st.lat, st.lon, st.batteryPct.takeUnless { st.energyType == "Fuel" }, st.residualKwh, st.capacityKwh)
        val last = Snap.of(p.getString(ChargePower.key("ctLast"), null)?.let { JSONObject(it) })
        var open = p.getString(ChargePower.key("ctOpen"), null)?.let { JSONObject(it) }
        val e = p.edit()
        if (last == null) { e.putString(ChargePower.key("ctLast"), now.json().toString()).apply(); return }
        if (now.t <= last.t && now.odo <= last.odo) return   // nothing new from the car
        val moved = now.odo - last.odo
        if (moved < -1) { e.putString(ChargePower.key("ctLast"), now.json().toString()).remove(ChargePower.key("ctOpen")).apply(); return }   // another car or a reset
        // A drive that was still going at the last report but has since been idle for long: close it first.
        if (open != null && moved > 0.3 && now.t - (Snap.of(open.optJSONObject("end"))?.t ?: 0L) > 45 * 60_000L) {
            save(open); open = null
        }
        val stopped = !st.moving && st.ignition != "Drive" && st.ignition != "StartUp"
        if (moved > 0.3) {
            open = if (open == null) JSONObject().put("start", last.json()).put("end", now.json()).put("live", st.moving)
            else open.put("end", now.json()).put("live", open.optBoolean("live") || st.moving)
            if (stopped) { save(open); open = null }
        } else if (open != null && (stopped || now.t - (Snap.of(open.optJSONObject("end"))?.t ?: now.t) > 15 * 60_000L)) {
            save(open); open = null
        }
        e.putString(ChargePower.key("ctLast"), now.json().toString())
        if (open != null) e.putString(ChargePower.key("ctOpen"), open.toString()) else e.remove(ChargePower.key("ctOpen"))
        e.apply()
    }

    private fun save(open: JSONObject) {
        val p = ChargePower.store() ?: return
        val a = Snap.of(open.optJSONObject("start")) ?: return
        val b = Snap.of(open.optJSONObject("end")) ?: return
        val km = b.odo - a.odo
        if (km < 0.3) return
        // The car may only have reported long before and right after the drive: estimate the start from about 45 km/h.
        val est = (km / 45.0 * 3_600_000L).toLong().coerceAtLeast(3 * 60_000L)
        val start = if (open.optBoolean("live")) a.t else maxOf(a.t, b.t - est)
        val kwh = when {
            a.res != null && b.res != null && b.res < a.res -> a.res - b.res
            a.pct != null && b.pct != null && b.pct < a.pct && (b.cap ?: a.cap) != null -> (a.pct - b.pct) / 100.0 * (b.cap ?: a.cap)!!
            else -> null
        }?.takeIf { it / km * 100 in 5.0..60.0 }
        val o = JSONObject().put("id", "car-${b.t}").put("start", start).put("end", b.t).put("km", km)
            .put("min", (b.t - start) / 60_000.0).put("kwh", kwh ?: 0.0).put("avg", kwh?.let { it / km * 100 } ?: 0.0)
            .put("speed", if (b.t > start) km / ((b.t - start) / 3_600_000.0) else 0.0)
            .put("aLat", a.lat ?: JSONObject.NULL).put("aLon", a.lon ?: JSONObject.NULL).put("bLat", b.lat ?: JSONObject.NULL).put("bLon", b.lon ?: JSONObject.NULL)
            .put("estimated", !open.optBoolean("live"))
        val arr = try { JSONArray(p.getString(ChargePower.key("ctTrips"), "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(o)
        val keep = JSONArray(); val from = maxOf(0, arr.length() - MAX)
        for (i in from until arr.length()) keep.put(arr.get(i))
        p.edit().putString(ChargePower.key("ctTrips"), keep.toString()).apply()
    }

    /** Trips logged from the car's reports, newest first. */
    fun list(): List<Trip> {
        val p = ChargePower.store() ?: return emptyList()
        val arr = try { JSONArray(p.getString(ChargePower.key("ctTrips"), "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Trip(
                id = o.optString("id"),
                startMillis = o.optLong("start").takeIf { it > 0 },
                endMillis = o.optLong("end").takeIf { it > 0 },
                distanceKm = o.optDouble("km", 0.0),
                durationSec = o.optDouble("min", 0.0) * 60,
                kwh = o.optDouble("kwh", 0.0).takeIf { it > 0 },
                avgKwhPer100 = o.optDouble("avg", 0.0).takeIf { it > 0 },
                avgSpeedKmh = o.optDouble("speed", 0.0).takeIf { it > 0 && !o.optBoolean("estimated") },
            )
        }.sortedByDescending { it.startMillis ?: 0L }
    }
}
