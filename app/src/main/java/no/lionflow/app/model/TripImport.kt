package no.lionflow.app.model

import no.lionflow.app.api.num
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads trips exported from other apps: LionFlow's own CSV, the official MyPeugeot / MyCitroën / MyDS / MyOpel
 * app's CSV export (columns named in the app's language, ";" or "," between them) and its full ".myp" export (JSON).
 * Returns LionFlow's stored form: one JSON object per trip (id, start, end, km, min, kwh, avg, speed) and the tag
 * name, if the file had one.
 */
object TripImport {
    data class Row(val json: JSONObject, val tag: String?)

    fun parse(text: String): List<Row> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (t.startsWith("[") || t.startsWith("{")) parseMyp(t) else parseCsv(t)
    }

    // ---- .myp (JSON): [{ "vin": …, "trips": [ { "id", "startDateTime" (s), "endDateTime", "startMileage", "endMileage", … } ] }]
    private fun parseMyp(t: String): List<Row> {
        val root = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        val out = ArrayList<Row>()
        for (i in 0 until root.length()) {
            val car = root.optJSONObject(i) ?: continue
            val trips = car.optJSONArray("trips") ?: continue
            val vin = car.optString("vin").takeLast(6)
            for (k in 0 until trips.length()) {
                val o = trips.optJSONObject(k) ?: continue
                val start = secOrMs(o, "startDateTime", "startDate", "start") ?: continue
                val km = o.optDouble("distance", Double.NaN).takeIf { !it.isNaN() }
                    ?: (o.optDouble("endMileage", Double.NaN) - o.optDouble("startMileage", Double.NaN)).takeIf { !it.isNaN() && it >= 0 }
                    ?: continue
                val end = secOrMs(o, "endDateTime", "endDate", "end")
                    ?: o.optDouble("duration", Double.NaN).takeIf { !it.isNaN() }?.let { start + (it * 1000).toLong() }
                    ?: start
                val kwh = listOf("electricConsumption", "consumptionElectric", "energyConsumption").firstNotNullOfOrNull { key -> o.optDouble(key, Double.NaN).takeIf { !it.isNaN() } } ?: 0.0
                out += Row(store("myp-$vin-${o.opt("id") ?: start}", start, end, km, kwh), null)
            }
        }
        return out
    }

    private fun secOrMs(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k)
            val n = (v as? Number)?.toLong() ?: v?.toString()?.toLongOrNull()
            if (n != null && n > 0) return if (n < 10_000_000_000L) n * 1000 else n
            v?.toString()?.let { date(it) }?.let { return it }
        }
        return null
    }

    // ---- CSV
    private fun parseCsv(t: String): List<Row> {
        val lines = t.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val head = lines[0]
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> head.count { it == c } } ?: ','
        val cols = split(head, sep).map { norm(it) }
        // LionFlow's own export.
        if (cols.firstOrNull() == "id" && cols.getOrNull(1) == "start") {
            val liters = cols.indexOf("fuel_liters")
            val rate = cols.indexOf("avg_l_per_100km")
            val estimated = cols.indexOf("fuel_units_estimated")
            return lines.drop(1).mapNotNull { line -> own(split(line, sep), liters, rate, estimated) }
        }

        fun find(vararg words: String, not: List<String> = emptyList()) = cols.indexOfFirst { c -> words.any { c.contains(it) } && not.none { c.contains(it) } }
        val iStart = find("start", "debut", "départ", "depart", "beginn", "inicio", "partenza", "begin", "dato", "date", not = listOf("km", "mileage", "kilom", "odom", "lieu", "adresse", "address", "posit", "place", "lat", "lon"))
        val iEnd = find("end", "fin", "arrivee", "arrivée", "ende", "llegada", "arrivo", "slutt", not = listOf("km", "mileage", "kilom", "odom", "lieu", "adresse", "address", "posit", "place", "lat", "lon"))
        val iKm = find("distance", "km", "kilom", "afstand", "strecke", "lengde")
        val iDur = find("duree", "durée", "duration", "dauer", "duracion", "durata", "varighet", "tid", "time")
        val iKwh = find("kwh", "energ", "electri", not = listOf("/100", "100 km", "100km", "moy", "avg", "average"))
        val iAvg = cols.indexOfFirst { "kwh" in it && ("100" in it || "avg" in it || "average" in it) }
        if (iStart < 0 || iKm < 0) return emptyList()
        val out = ArrayList<Row>()
        for (line in lines.drop(1)) {
            val c = split(line, sep)
            val start = c.getOrNull(iStart)?.let { date(it) } ?: continue
            val km = num(c.getOrNull(iKm)) ?: continue
            val dur = c.getOrNull(iDur)?.let { minutes(it) }
            val end = c.getOrNull(iEnd)?.let { date(it) } ?: dur?.let { start + (it * 60_000).toLong() } ?: start
            val kwh = num(c.getOrNull(iKwh)) ?: num(c.getOrNull(iAvg))?.let { it * km / 100 } ?: 0.0
            out += Row(store("csv-$start-${(km * 10).toLong()}", start, end, km, kwh), null)
        }
        return out
    }

    private fun own(c: List<String>, liters: Int, rate: Int, estimated: Int): Row? {
        if (c.size < 4) return null
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val start = c.getOrNull(1)?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } } ?: 0L
        val end = c.getOrNull(2)?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } } ?: 0L
        val j = JSONObject().put("id", c[0].ifEmpty { "imp-" + System.nanoTime() }).put("start", start).put("end", end)
            .put("km", c.getOrNull(3)?.toDoubleOrNull() ?: 0.0).put("min", c.getOrNull(4)?.toDoubleOrNull() ?: 0.0)
            .put("kwh", c.getOrNull(5)?.toDoubleOrNull() ?: 0.0).put("avg", c.getOrNull(6)?.toDoubleOrNull() ?: 0.0)
            .put("speed", c.getOrNull(7)?.toDoubleOrNull() ?: 0.0)
        c.getOrNull(liters)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { j.put("liters", it) }
        c.getOrNull(rate)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { j.put("avgL", it) }
        if (c.getOrNull(estimated).equals("true", ignoreCase = true)) j.put("fuelUnitsEstimated", true)
        return Row(j, c.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun restore(o: JSONObject): Trip = Trip(
        id = o.optString("id"),
        startMillis = o.optLong("start").takeIf { it > 0 },
        endMillis = o.optLong("end").takeIf { it > 0 },
        distanceKm = o.optDouble("km", 0.0),
        durationSec = o.optDouble("min", 0.0) * 60,
        kwh = o.num("kwh")?.takeIf { it > 0 },
        avgKwhPer100 = o.num("avg")?.takeIf { it > 0 },
        avgSpeedKmh = o.num("speed")?.takeIf { it > 0 },
        liters = o.num("liters")?.takeIf { it.isFinite() && it >= 0 },
        avgLPer100 = o.num("avgL")?.takeIf { it.isFinite() && it >= 0 },
        fuelUnitsEstimated = o.optBoolean("fuelUnitsEstimated"),
    )

    private fun store(id: String, start: Long, end: Long, km: Double, kwh: Double): JSONObject {
        val min = ((end - start) / 60_000.0).coerceAtLeast(0.0)
        return JSONObject().put("id", id).put("start", start).put("end", end).put("km", km).put("min", min)
            .put("kwh", kwh).put("avg", if (km > 0 && kwh > 0) kwh / km * 100 else 0.0)
            .put("speed", if (min > 0) km / (min / 60) else 0.0)
    }

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == sep && !q -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out += sb.toString().trim()
        return out
    }

    private fun norm(s: String) = s.trim().trim('"').lowercase(Locale.ROOT)

    /** "12,5" / "12.5" / "12.5 km" → 12.5 */
    private fun num(s: String?): Double? = s?.replace(Regex("[^0-9,.\\-]"), "")?.let {
        val t = if (it.count { c -> c == ',' } == 1 && !it.contains('.')) it.replace(',', '.') else it.replace(",", "")
        t.toDoubleOrNull()
    }

    /** "1:05", "01:05:30", "65", "65 min", "1h05" → minutes */
    private fun minutes(s: String): Double? {
        val t = s.trim()
        Regex("^(\\d+):(\\d{1,2})(?::(\\d{1,2}))?$").find(t)?.let { m ->
            val (a, b, c) = m.destructured
            return if (c.isNotEmpty()) a.toDouble() * 60 + b.toDouble() + c.toDouble() / 60 else a.toDouble() * 60 + b.toDouble()
        }
        Regex("(\\d+)\\s*h\\s*(\\d+)?").find(t)?.let { m -> return m.groupValues[1].toDouble() * 60 + (m.groupValues[2].toDoubleOrNull() ?: 0.0) }
        return num(t)
    }

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm",
        "MM/dd/yyyy hh:mm a", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd",
    )

    fun date(s: String): Long? {
        val t = s.trim().trim('"').replace(" - ", " ").replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        try { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) { }
        for (f in formats) {
            try {
                val p = java.text.ParsePosition(0)
                val d = SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(t, p)
                if (d != null && p.index >= t.length - 1) return d.time
            } catch (_: Exception) { }
        }
        return null
    }
}
