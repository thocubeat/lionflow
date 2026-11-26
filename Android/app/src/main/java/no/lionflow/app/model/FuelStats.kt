package no.lionflow.app.model

import java.util.Calendar

/**
 * Fuel figures worked out from the trips and the car's own counters: totals per month, fill-ups, records and the
 * average since new. For petrol, diesel and hybrid cars.
 *
 * The method (fill-ups read from the fuel gauge between two trips, the tank size worked out from the gauge, the
 * lifetime average from the car's fuel counter) comes from lionCar, the iPhone port of LionFlow (GPL-3.0).
 */
object FuelStats {
    data class Month(val startMillis: Long, val km: Double, val trips: Int, val liters: Double, val kmWithFuel: Double, val seconds: Double) {
        val lPer100: Double? get() = if (kmWithFuel > 0 && liters > 0) liters / kmWithFuel * 100 else null
    }

    private fun monthStart(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Totals per calendar month, newest first. */
    fun monthly(trips: List<Trip>): List<Month> =
        trips.filter { it.startMillis != null }.groupBy { monthStart(it.startMillis!!) }.map { (start, list) ->
            val fuelled = list.filter { it.liters != null }
            Month(start, list.sumOf { it.distanceKm }, list.size, fuelled.sumOf { it.liters ?: 0.0 }, fuelled.sumOf { it.distanceKm }, list.sumOf { it.durationSec ?: 0.0 })
        }.sortedByDescending { it.startMillis }

    /** Litres used by these trips, and what that cost at [pricePerLiter] (null without a price or without fuel figures). */
    fun liters(trips: List<Trip>): Double = trips.sumOf { it.liters ?: 0.0 }
    fun cost(liters: Double?, pricePerLiter: Double): Double? = if (liters != null && liters > 0 && pricePerLiter > 0) liters * pricePerLiter else null

    data class FillUp(val atMillis: Long, val fromPct: Double, val toPct: Double, val liters: Double?, val kmSincePrevious: Double?)

    /** Fill-ups: the gauge at a trip's start is clearly higher than at the end of the trip before. Newest first. */
    fun fillUps(trips: List<Trip>, tankLiters: Double?, minJump: Double = 5.0): List<FillUp> {
        val sorted = trips.filter { it.startMillis != null }.sortedBy { it.startMillis }
        val out = ArrayList<FillUp>()
        var kmSince = 0.0
        var seenOne = false
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            kmSince += prev.distanceKm
            val a = prev.fuelEndPct ?: continue
            val b = cur.fuelStartPct ?: continue
            if (b - a < minJump) continue
            out += FillUp(cur.startMillis!!, a, b, tankLiters?.let { (b - a) / 100 * it }, if (seenOne) kmSince else null)
            kmSince = 0.0
            seenOne = true
        }
        return out.asReversed().toList()
    }

    /**
     * Tank size from the fuel used against how far the gauge fell, over the trips where it moved. Needs at least
     * 10 % of gauge movement, and gives nothing when the answer isn't a believable tank (20–100 l).
     */
    fun tankEstimate(trips: List<Trip>): Double? {
        var liters = 0.0
        var drop = 0.0
        for (t in trips) {
            val l = t.liters ?: continue
            val a = t.fuelStartPct ?: continue
            val b = t.fuelEndPct ?: continue
            if (a <= b || a >= 100) continue
            liters += l; drop += a - b
        }
        if (drop < 10) return null
        return (liters / drop * 100).takeIf { it in 20.0..100.0 }
    }

    data class Records(val longest: Trip?, val longestTime: Trip?, val mostEconomical: Trip?, val thirstiest: Trip?, val bestDayMillis: Long?, val bestDayKm: Double)

    /** Longest trip, longest time at the wheel, lowest and highest l/100 km (trips of 10 km or more), most km in one day. */
    fun records(trips: List<Trip>): Records {
        val rated = trips.filter { it.distanceKm >= 10 && it.avgLPer100 != null }
        val days = trips.filter { it.startMillis != null }.groupBy { dayStart(it.startMillis!!) }.mapValues { e -> e.value.sumOf { it.distanceKm } }
        val best = days.maxByOrNull { it.value }
        return Records(
            longest = trips.maxByOrNull { it.distanceKm }?.takeIf { it.distanceKm > 0 },
            longestTime = trips.filter { it.durationSec != null }.maxByOrNull { it.durationSec!! },
            mostEconomical = rated.minByOrNull { it.avgLPer100!! },
            thirstiest = rated.maxByOrNull { it.avgLPer100!! }?.takeIf { rated.size > 1 },
            bestDayMillis = best?.key, bestDayKm = best?.value ?: 0.0,
        )
    }

    /**
     * Litres per unit of the car's lifetime fuel counter. The unit isn't documented and differs between models, so the
     * usual ones are tried against the odometer: the first that gives a believable lifetime average (3–12 l/100 km)
     * wins. Null when the car has driven too little to tell, or nothing fits.
     */
    fun litersPerUnit(totalRaw: Double?, odometerKm: Double?): Double? {
        if (totalRaw == null || totalRaw <= 0 || odometerKm == null || odometerKm < 300) return null
        return listOf(0.01, 0.001, 1.0, 0.1, 0.004).firstOrNull { totalRaw * it / odometerKm * 100 in 3.0..12.0 }
    }

    /** Litres burned since the car was new, from its own counter. */
    fun lifetimeLiters(st: VehicleStatus?): Double? {
        val scale = litersPerUnit(st?.fuelTotalRaw, st?.odometerKm) ?: return null
        return st!!.fuelTotalRaw!! * scale
    }

    /** Average since new (l/100 km), from the car's fuel counter and odometer. */
    fun lifetimeLPer100(st: VehicleStatus?): Double? {
        val liters = lifetimeLiters(st) ?: return null
        val km = st?.odometerKm ?: return null
        return liters / km * 100
    }
}

/** A warning the car itself has reported (tyre pressure, a lamp, a fault …). */
data class CarAlert(val type: String, val atMillis: Long?) {
    /** "TyrePressureFault" or "TYRE_PRESSURE_FAULT" as "Tyre pressure fault". */
    val label: String get() {
        val spaced = type.replace('_', ' ').replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim()
        return spaced.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        /** Only warnings that still stand: ones the car has marked as ended are left out. */
        fun parse(list: List<org.json.JSONObject>): List<CarAlert> = list.mapNotNull { j ->
            if (j.has("active") && !j.isNull("active") && !j.optBoolean("active", true)) return@mapNotNull null
            if (j.has("endAt") && !j.isNull("endAt") && j.optString("endAt").isNotBlank()) return@mapNotNull null
            val type = listOf("type", "label", "code").firstNotNullOfOrNull { k -> j.optString(k).takeIf { it.isNotBlank() && !j.isNull(k) } } ?: return@mapNotNull null
            CarAlert(type, VehicleStatus.parseIso(j.optString("startedAt").ifBlank { j.optString("createdAt") }))
        }.distinctBy { it.type }
    }
}

/** Everything in the car's status, one line per value, for the "All car data" screen. */
object CarData {
    /** "energies › Electric › level" to "80", in the order the car sent them. Positions inside lists are numbered. */
    fun flatten(j: org.json.JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(path: String, v: Any?) {
            when (v) {
                is org.json.JSONObject -> {
                    val keys = v.keys().asSequence().toList().filter { !it.startsWith("_") }.sorted()
                    for (k in keys) walk(if (path.isEmpty()) k else "$path › $k", v.opt(k))
                }
                is org.json.JSONArray -> {
                    if (v.length() > 0 && (0 until v.length()).all { v.opt(it) !is org.json.JSONObject && v.opt(it) !is org.json.JSONArray }) {
                        out += path to (0 until v.length()).joinToString(", ") { v.opt(it).toString() }
                    } else for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        // A list of typed things (energies, doors) reads better by its type than by its number.
                        val name = (item as? org.json.JSONObject)?.let { o -> listOf("type", "identifier", "slot").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } } } ?: "${i + 1}"
                        walk("$path › $name", item)
                    }
                }
                null, org.json.JSONObject.NULL -> {}
                else -> out += path to v.toString()
            }
        }
        walk("", j)
        return out
    }

    /** The raw answer, indented, as the carmaker's server sent it. */
    fun pretty(j: org.json.JSONObject): String = try { j.toString(2) } catch (_: Exception) { j.toString() }

    /** N, NE, E … for a heading in degrees. */
    fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
