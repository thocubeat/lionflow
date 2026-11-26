package no.lionflow.app.plan

import org.json.JSONArray
import org.json.JSONObject

/** A finished plan written out in full (route, stops, ferries), so a saved trip opens exactly as it was, without asking any server. */
object PlanStore {
    private fun arr(a: DoubleArray, digits: Int): JSONArray {
        val j = JSONArray(); val f = Math.pow(10.0, digits.toDouble())
        a.forEach { j.put(Math.round(it * f) / f) }
        return j
    }
    private fun darr(j: JSONArray) = DoubleArray(j.length()) { j.getDouble(it) }

    fun place(p: Place) = JSONObject().put("n", p.name).put("d", p.detail).put("la", p.lat).put("lo", p.lon).put("c", p.country)
    fun place(o: JSONObject) = Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c"))

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    fun route(r: RouteData) = JSONObject()
        .put("lat", arr(r.lat, 5)).put("lon", arr(r.lon, 5)).put("km", arr(r.km, 3)).put("min", arr(r.min, 2)).put("speed", arr(r.speed, 1))
        .put("ferries", JSONArray().apply { r.ferries.forEach { f ->
            put(JSONObject().put("n", f.name).put("al", f.aLat).put("ao", f.aLon).put("bl", f.bLat).put("bo", f.bLon).put("m", f.minutes).put("i", f.idx).put("e", f.endIdx))
        } })

    fun route(o: JSONObject): RouteData {
        val r = RouteData(darr(o.getJSONArray("lat")), darr(o.getJSONArray("lon")), darr(o.getJSONArray("km")), darr(o.getJSONArray("min")), darr(o.getJSONArray("speed")))
        val fs = o.optJSONArray("ferries")
        if (fs != null) r.ferries = (0 until fs.length()).map { i -> fs.getJSONObject(i).let {
            Ferry(it.getString("n"), it.getDouble("al"), it.getDouble("ao"), it.getDouble("bl"), it.getDouble("bo"), it.getDouble("m"), it.getInt("i"), it.optInt("e", -1))
        } }
        return r
    }

    private fun charger(c: PlanCharger) = JSONObject().put("id", c.id).put("n", c.name).put("op", c.operator).put("la", c.lat).put("lo", c.lon)
        .put("kw", c.kw).put("pts", c.points).put("i", c.idx).put("km", c.km).put("off", c.offKm).put("cur", c.currency)
        .apply { c.price?.let { put("price", it) }; c.free?.let { put("free", it) } }

    private fun charger(o: JSONObject) = PlanCharger(
        o.getString("id"), o.getString("n"), o.optString("op"), o.getDouble("la"), o.getDouble("lo"), o.getDouble("kw"), o.getInt("pts"),
        o.getInt("i"), o.getDouble("km"), o.getDouble("off"), dbl(o, "price"), o.optString("cur"), if (o.has("free")) o.getInt("free") else null,
    )

    fun plan(p: Plan) = JSONObject()
        .put("stops", JSONArray().apply { p.stops.forEach { s ->
            put(JSONObject().put("c", charger(s.charger)).put("a", s.arrivePct).put("l", s.leavePct).put("m", s.minutes).put("k", s.kwh)
                .put("pin", s.pinned).put("at", s.atMin).apply { s.cost?.let { put("cost", it) } }
                .put("alt", JSONArray().apply { s.alternatives.forEach { put(charger(it)) } }))
        } })
        .put("arrive", p.arrivePct).put("drive", p.driveMin).put("charge", p.chargeMin).put("total", p.totalMin).put("km", p.totalKm)
        .put("ok", p.ok).put("cur", p.currency).put("unpriced", p.unpriced).put("soc", arr(p.socAt, 1)).put("used", p.usedKwh)
        .apply { p.failKm?.let { put("fail", it) }; p.cost?.let { put("cost", it) } }

    fun plan(o: JSONObject): Plan {
        val st = o.getJSONArray("stops")
        return Plan(
            (0 until st.length()).map { i -> st.getJSONObject(i).let { s ->
                val alt = s.optJSONArray("alt")
                Stop(charger(s.getJSONObject("c")), s.getDouble("a"), s.getDouble("l"), s.getInt("m"), s.getDouble("k"), dbl(s, "cost"),
                    s.optBoolean("pin"), s.getDouble("at"), if (alt == null) emptyList() else (0 until alt.length()).map { charger(alt.getJSONObject(it)) })
            } },
            o.getDouble("arrive"), o.getDouble("drive"), o.getInt("charge"), o.getDouble("total"), o.getDouble("km"), o.getBoolean("ok"),
            dbl(o, "fail"), dbl(o, "cost"), o.optString("cur"), o.optInt("unpriced"), darr(o.getJSONArray("soc")), o.optDouble("used", 0.0),
        )
    }
}
