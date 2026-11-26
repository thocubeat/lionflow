package no.lionflow.app.plan

import org.json.JSONObject

/** A fast charger as OpenStreetMap describes it, before it is placed on a route. */
data class RawCharger(
    val id: String, val name: String, val operator: String, val lat: Double, val lon: Double,
    val kw: Double, val points: Int, val chargeTag: String,
    /** Charge points free right now, when the source tells (NOBIL); null = not known. */
    val free: Int? = null,
)

/** Reads the answers from the address search, the route service and the charger search. */
object PlanParse {
    /** Photon (OpenStreetMap address search): a GeoJSON list of places. */
    fun places(j: JSONObject): List<Place> {
        val arr = j.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (c.length() < 2) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val city = listOf("city", "town", "village", "locality", "district", "county").map { p.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val name = p.optString("name").ifBlank { street }.ifBlank { city }
            if (name.isBlank()) continue
            val detail = listOf(
                street.takeIf { it != name },
                listOf(p.optString("postcode"), city).filter { it.isNotBlank() }.joinToString(" ").takeIf { it != name },
                p.optString("country"),
            ).filter { !it.isNullOrBlank() }.distinct().joinToString(", ")
            val place = Place(name, detail, c.optDouble(1), c.optDouble(0), p.optString("countrycode").uppercase())
            if (out.none { it.name == place.name && it.detail == place.detail }) out.add(place)
        }
        return out
    }

    /** OSRM route answer with an encoded line (6 decimals) and distance/time per stretch. */
    fun route(j: JSONObject): RouteData? = routes(j).firstOrNull()

    /** All the routes in an OSRM answer: the best one first, then the alternatives. */
    fun routes(j: JSONObject): List<RouteData> {
        val arr = j.optJSONArray("routes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { one(arr.optJSONObject(it)) }
    }

    private fun one(r: JSONObject?): RouteData? {
        r ?: return null
        val geometry = r.optString("geometry")
        if (geometry.isBlank()) return null
        val (lat, lon) = Geo.decodePolyline(geometry, 6)
        if (lat.size < 2) return null
        val dist = ArrayList<Double>(); val dur = ArrayList<Double>()
        val legs = r.optJSONArray("legs")
        if (legs != null) for (i in 0 until legs.length()) {
            val a = legs.optJSONObject(i)?.optJSONObject("annotation") ?: continue
            val d = a.optJSONArray("distance"); val t = a.optJSONArray("duration")
            if (d == null || t == null || d.length() != t.length()) continue
            for (k in 0 until d.length()) { dist.add(d.optDouble(k, 0.0)); dur.add(t.optDouble(k, 0.0)) }
        }
        val fits = dist.size == lat.size - 1
        val rd = RouteData.build(lat, lon, if (fits) dist.toDoubleArray() else null, if (fits) dur.toDoubleArray() else null, r.optDouble("duration", 0.0))
        rd.ferries = ferries(r, rd)
        return rd
    }

    /** Car ferries: the steps OSRM marks with mode "ferry". */
    private fun ferries(r: JSONObject, rd: RouteData): List<Ferry> {
        val out = ArrayList<Ferry>()
        val legs = r.optJSONArray("legs") ?: return out
        for (i in 0 until legs.length()) {
            val steps = legs.optJSONObject(i)?.optJSONArray("steps") ?: continue
            for (k in 0 until steps.length()) {
                val st = steps.optJSONObject(k) ?: continue
                if (st.optString("mode") != "ferry") continue
                val g = st.optString("geometry")
                if (g.isBlank()) continue
                val (la, lo) = Geo.decodePolyline(g, 6)
                if (la.size < 2) continue
                out.add(Ferry(st.optString("name").ifBlank { "Ferry" }, la[0], lo[0], la[la.size - 1], lo[lo.size - 1],
                    st.optDouble("duration", 0.0) / 60.0, Geo.project(rd, la[0], lo[0]).first, Geo.project(rd, la[la.size - 1], lo[lo.size - 1]).first))
            }
        }
        return out
    }

    /** Names that point to a charger for buses or lorries (bus depots, truck charging), not for cars. */
    private val notCarWords = Regex("(?i)(\\bbus(s|sen|depot|anlegg)?\\b|connectbus|unibuss|norgesbuss|tide buss|boreal|vy buss|nobina|\\bruter\\b|bussdepot|bussanlegg|truck|lastebil|milence|\\bhgv\\b)")
    fun notForCars(text: String): Boolean = notCarWords.containsMatchIn(text)

    /** Overpass answer: charging stations with a CCS plug. */
    fun chargers(j: JSONObject): List<RawCharger> {
        val els = j.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<RawCharger>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val center = e.optJSONObject("center")
            val la = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            val t = e.optJSONObject("tags") ?: JSONObject()
            // Not open to ordinary cars: private, staff only, or for buses and lorries.
            if (t.optString("access") in setOf("private", "no", "permit", "employees", "delivery") ||
                t.optString("motorcar") == "no" || t.optString("motor_vehicle") == "no") continue
            if ((t.optString("bus") == "designated" || t.optString("hgv") == "designated") && t.optString("motorcar") != "yes") continue
            if (notForCars(listOf("name", "operator", "brand", "network").joinToString(" ") { t.optString(it) }) && t.optString("motorcar") != "yes") continue
            var maxKw = 0.0
            val keys = t.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.endsWith(":output") || k == "maxpower" || k == "charging_station:output") {
                    val kw = kwOf(t.optString(k))
                    if (kw != null && kw > maxKw) maxKw = kw
                }
            }
            val operator = listOf("operator", "brand", "network").map { t.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val name = t.optString("name").ifBlank { operator }.ifBlank { "Charging station" }
            val points = t.optString("socket:type2_combo").trim().toIntOrNull()?.takeIf { it in 1..200 }
                ?: t.optString("capacity").trim().toIntOrNull()?.takeIf { it in 1..200 } ?: 0
            out.add(RawCharger("osm-${e.optString("type")}-${e.optLong("id")}", name, operator, la, lo, maxKw, points, t.optString("charge")))
        }
        return out
    }

    /** "150 kW", "150", "150000 W", "0.35 MW", "50 kW;150 kW" → the highest, in kW. */
    fun kwOf(raw: String): Double? {
        var best: Double? = null
        for (part in raw.lowercase().split(';', '/', ',').map { it.trim() }) {
            val num = Regex("([0-9]+(?:\\.[0-9]+)?)").find(part)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val kw = when {
                part.contains("mw") -> num * 1000
                part.contains("kw") || part.contains("kva") -> num
                num > 1000 -> num / 1000   // plain watts
                else -> num
            }
            if (kw in 1.0..1500.0 && (best == null || kw > best)) best = kw
        }
        return best
    }
}

/**
 * What a kWh costs at a charger. There is no free, open source of live prices, so this uses
 * the price written on the charger in OpenStreetMap when there is one, and otherwise the
 * operator's usual drop-in price from a small table shipped with the app.
 */
class Prices(json: String) {
    private class Country(val currency: String, val ops: List<Pair<Regex, Double>>)
    private val countries = HashMap<String, Country>()
    /** When the table was last checked, e.g. "2026-10". */
    var asOf: String = ""
        private set

    init {
        try {
            val j = JSONObject(json)
            asOf = j.optString("asOf")
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val c = j.optJSONObject(k) ?: continue
                val ops = c.optJSONObject("ops") ?: continue
                val list = ArrayList<Pair<Regex, Double>>()
                val ok = ops.keys()
                while (ok.hasNext()) { val name = ok.next(); list.add(Regex("(^|[^\\p{L}])" + Regex.escape(name.lowercase()) + "($|[^\\p{L}])") to ops.optDouble(name)) }
                // Longest names first, so "circle k" wins over a shorter name inside it.
                countries[k.uppercase()] = Country(c.optString("cur"), list.sortedByDescending { it.first.pattern.length })
            }
        } catch (_: Exception) { }
    }

    /** Price per kWh and its currency, or null when it isn't known. */
    fun find(country: String, operatorText: String, chargeTag: String): Pair<Double, String>? {
        Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(eur|€|nok|kr|sek|dkk|gbp|£|chf)\\s*/\\s*kwh").find(chargeTag.lowercase())?.let { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            val cur = when (m.groupValues[2]) { "eur", "€" -> "€"; "gbp", "£" -> "£"; "chf" -> "CHF"; "sek" -> "SEK"; "dkk" -> "DKK"; else -> "kr" }
            if (v != null && v > 0) return v to cur
        }
        val c = countries[country.uppercase()] ?: return null
        val text = operatorText.lowercase()
        val hit = c.ops.firstOrNull { it.first.containsMatchIn(text) } ?: return null
        return hit.second to c.currency
    }
}
