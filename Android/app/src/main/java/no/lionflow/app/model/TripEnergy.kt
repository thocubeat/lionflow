package no.lionflow.app.model

/**
 * Fills in the energy a trip used when Peugeot's trip list leaves it out (the E-5008 and other newer cars often send
 * no electric consumption, so the official app shows none either). In order:
 * 1. the car's own figure, rescaled if it came in kWh instead of Wh;
 * 2. the car's average × distance;
 * 3. the drop in battery level over the trip × the battery's usable size;
 * 4. the trip LionFlow logged from the car's reports at the same time.
 * Figures from 2–4 are marked as estimated. Results outside 3–60 kWh/100 km are left out.
 */
object TripEnergy {
    private fun sane(kwh: Double, km: Double) = km >= 0.5 && kwh / km * 100 in 3.0..60.0

    /** Wh read as kWh shows up as a thousandth of a sane value. */
    private fun rescaled(v: Double?, km: Double, per100: Boolean): Double? {
        if (v == null || v <= 0) return null
        val p = if (per100) v else if (km > 0) v / km * 100 else return v
        return if (p in 0.003..0.06) v * 1000 else v
    }

    fun fill(trips: List<Trip>, batteryKwh: Double?, logged: List<Trip> = emptyList()): List<Trip> = trips.map { fill(it, batteryKwh, logged) }

    fun fill(t: Trip, batteryKwh: Double?, logged: List<Trip> = emptyList()): Trip {
        val km = t.distanceKm
        val own = rescaled(t.kwh, km, false)?.takeIf { sane(it, km) }
        val avg = rescaled(t.avgKwhPer100, km, true)?.takeIf { it in 3.0..60.0 }
        val drop = if (t.batteryStartPct != null && t.batteryEndPct != null) t.batteryStartPct - t.batteryEndPct else null
        val estimate = when {
            own != null -> null
            avg != null -> avg * km / 100
            drop != null && drop > 0 && batteryKwh != null && batteryKwh > 1 -> drop / 100 * batteryKwh
            else -> matching(t, logged)?.kwh
        }?.takeIf { sane(it, km) }
        val kwh = own ?: estimate ?: return if (avg != t.avgKwhPer100) t.copy(avgKwhPer100 = avg) else t
        return t.copy(kwh = kwh, avgKwhPer100 = avg ?: kwh / km * 100, energyEstimated = own == null)
    }

    /** A trip logged from the car's reports that overlaps [t] and covers about the same distance. */
    private fun matching(t: Trip, logged: List<Trip>): Trip? {
        val a = t.startMillis ?: return null
        val b = t.endMillis ?: a
        val slack = 20 * 60_000L
        return logged.firstOrNull { o ->
            val oa = o.startMillis ?: return@firstOrNull false
            val ob = o.endMillis ?: oa
            (o.kwh ?: 0.0) > 0 && oa <= b + slack && ob >= a - slack && kotlin.math.abs(o.distanceKm - t.distanceKm) <= maxOf(1.0, t.distanceKm * 0.15)
        }
    }
}
