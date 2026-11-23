package no.lionflow.app.plan

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Planned trips saved for Android Auto. Each keeps the road (thinned out), the battery use along it and the
 * charging stops, so the car screen can work out what is left from where the car is and its battery now.
 */
object SavedRoutes {
    class Stop(
        val name: String, val lat: Double, val lon: Double, val idx: Int, val km: Double,
        /** Planned: minutes charging, battery % on arrival and when leaving (0 when not known). */
        val minutes: Int = 0, val arrivePct: Double = 0.0, val leavePct: Double = 0.0,
    )

    class Saved(
        val id: Long,
        val name: String,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val km: DoubleArray,
        /** kWh used from the start to each point (driving only). */
        val kwh: DoubleArray,
        val batteryKwh: Double,
        val stops: List<Stop>,
        /** How far along the route the car was last seen (point index): a round trip passes the same road twice. */
        var progress: Int = 0,
        /** The trip as it was set up (start, stops, destination, stays), so it can be planned again in the app. */
        val setup: JSONObject? = null,
        /** Driving minutes from the start to each point (empty for trips saved before this was kept). */
        val min: DoubleArray = DoubleArray(0),
    ) {
        val totalKm: Double get() = km.last()
    }

    /** Where the car is on a saved route and what is left. */
    class Live(
        val offRouteKm: Double,
        val leftKm: Double,
        val stopsLeft: Int,
        val nextStop: Stop?,
        val toNextKm: Double?,
        /** Battery % expected at the next charging stop, or at the end when none is left. */
        val pctAtNext: Double?,
        /** Point index the car is at. */
        val at: Int = 0,
        /** Minutes left, driving and the charging still planned (null when not known). */
        val minLeft: Double? = null,
        /** Battery % expected at the end, when no charging stop is left. */
        val pctAtEnd: Double? = null,
    )

    private const val FILE = "saved_routes.json"
    private const val MAX = 10

    /** Only builds installed from Google Play may use this. */
    fun fromPlay(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val src = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        src == "com.android.vending"
    } catch (_: Exception) { false }

    fun all(ctx: Context): List<Saved> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { parse(a.optJSONObject(it)) } }
    } catch (_: Exception) { emptyList() }

    /** Goes up each time trips are added or deleted, so the phone and Android Auto show the same list right away. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun write(ctx: Context, list: List<Saved>, listChanged: Boolean = true) {
        val a = JSONArray(); list.forEach { a.put(toJson(it)) }
        File(ctx.filesDir, FILE).writeText(a.toString())
        if (listChanged) changes.value++
    }

    fun delete(ctx: Context, id: Long) = synchronized(this) { write(ctx, all(ctx).filter { it.id != id }) }

    fun resetProgress(ctx: Context, id: Long) = synchronized(this) { write(ctx, all(ctx).onEach { if (it.id == id) it.progress = 0 }, false) }

    fun add(ctx: Context, s: Saved) = synchronized(this) { write(ctx, (listOf(s) + all(ctx)).take(MAX)) }

    /** Makes a saved route from a plan: points about every 300 m, battery use per point, the charging stops. */
    fun build(name: String, r: RouteData, energy: DoubleArray, plan: Plan, batteryKwh: Double, setup: JSONObject? = null): Saved {
        val keep = ArrayList<Int>()
        var next = 0.0
        for (i in 0 until r.size) if (i == 0 || i == r.size - 1 || r.km[i] >= next) { keep += i; next = r.km[i] + 0.3 }
        val map = HashMap<Int, Int>()
        fun newIdx(old: Int): Int = map.getOrPut(old) { keep.indexOfFirst { it >= old }.let { if (it < 0) keep.size - 1 else it } }
        val stops = plan.stops.filter { it.kwh > 0.01 }.sortedBy { it.charger.km }.map {
            Stop(it.charger.name, it.charger.lat, it.charger.lon, newIdx(it.charger.idx), it.charger.km, it.minutes, it.arrivePct, it.leavePct)
        }
        return Saved(
            System.currentTimeMillis(), name,
            DoubleArray(keep.size) { r.lat[keep[it]] }, DoubleArray(keep.size) { r.lon[keep[it]] },
            DoubleArray(keep.size) { r.km[keep[it]] }, DoubleArray(keep.size) { energy[keep[it]] - energy[0] },
            batteryKwh, stops, 0, setup, DoubleArray(keep.size) { r.min[keep[it]] },
        )
    }

    /**
     * What is left from the car's position and battery. Looks only ahead of the last known progress, and picks the
     * earliest close point, so the way back on a round trip isn't mistaken for the way out.
     */
    fun live(ctx: Context, s: Saved, lat: Double?, lon: Double?, pct: Double?): Live {
        var at = s.progress
        var off = 0.0
        if (lat != null && lon != null) {
            val from = (s.progress - 5).coerceAtLeast(0)
            val d = DoubleArray(s.lat.size) { if (it < from) Double.MAX_VALUE else Geo.km(lat, lon, s.lat[it], s.lon[it]) }
            val min = d.minOrNull() ?: 0.0
            off = min
            if (min < 3.0) {
                at = (from until d.size).first { d[it] <= min + 0.3 }
                if (at != s.progress) synchronized(this) { write(ctx, all(ctx).onEach { if (it.id == s.id) it.progress = at }, false); s.progress = at }
            }
        }
        val left = s.stops.filter { it.idx > at }
        val next = left.firstOrNull()
        val target = next?.idx ?: (s.km.size - 1)
        val pctAt = pct?.let { it - (s.kwh[target] - s.kwh[at]) / s.batteryKwh * 100 }
        val minLeft = if (s.min.size == s.km.size) (s.min.last() - s.min[at]).coerceAtLeast(0.0) + left.sumOf { it.minutes } else null
        val pctEnd = if (next == null) pctAt else null
        return Live(off, (s.totalKm - s.km[at]).coerceAtLeast(0.0), left.size, next, next?.let { s.km[it.idx] - s.km[at] }, pctAt, at, minLeft, pctEnd)
    }

    private fun arr(a: DoubleArray, digits: Int): JSONArray {
        val j = JSONArray(); val f = Math.pow(10.0, digits.toDouble())
        a.forEach { j.put(Math.round(it * f) / f) }
        return j
    }

    private fun darr(j: JSONArray) = DoubleArray(j.length()) { j.getDouble(it) }

    private fun toJson(s: Saved) = JSONObject().put("id", s.id).put("name", s.name)
        .put("lat", arr(s.lat, 5)).put("lon", arr(s.lon, 5)).put("km", arr(s.km, 2)).put("kwh", arr(s.kwh, 3))
        .put("battery", s.batteryKwh).put("progress", s.progress).apply { s.setup?.let { put("setup", it) } }
        .put("stops", JSONArray().apply { s.stops.forEach { put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("idx", it.idx).put("km", it.km)
            .put("m", it.minutes).put("a", it.arrivePct).put("l", it.leavePct)) } })
        .apply { if (s.min.isNotEmpty()) put("min", arr(s.min, 1)) }

    private fun parse(o: JSONObject?): Saved? = try {
        o!!
        val st = o.getJSONArray("stops")
        Saved(
            o.getLong("id"), o.getString("name"),
            darr(o.getJSONArray("lat")), darr(o.getJSONArray("lon")), darr(o.getJSONArray("km")), darr(o.getJSONArray("kwh")),
            o.getDouble("battery"),
            (0 until st.length()).map { i -> st.getJSONObject(i).let { Stop(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getInt("idx"), it.getDouble("km"),
                it.optInt("m"), it.optDouble("a", 0.0), it.optDouble("l", 0.0)) } },
            o.optInt("progress"), o.optJSONObject("setup"), o.optJSONArray("min")?.let { darr(it) } ?: DoubleArray(0),
        )
    } catch (_: Exception) { null }
}
