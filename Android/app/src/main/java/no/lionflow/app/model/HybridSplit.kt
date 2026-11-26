package no.lionflow.app.model

/**
 * Splits plug-in hybrid driving into electric and engine kilometres.
 * Peugeot reports, per trip, the electricity used and the fuel used, but not how the distance was shared.
 * Trips with no fuel are fully electric; for mixed trips the electric part is estimated from your own
 * electric consumption on the fully electric trips.
 */
object HybridSplit {
    class Result(
        val totalKm: Double,
        val evKm: Double,
        val engineKm: Double,
        val kwh: Double,
        /** kWh/100 km measured on fully electric trips, or null if there were none. */
        val evKwhPer100: Double?,
    ) {
        val evShare: Double get() = if (totalKm > 0) evKm / totalKm else 0.0
    }

    private const val FALLBACK_KWH_PER_KM = 0.2

    fun of(trips: List<Trip>): Result {
        fun usedEngine(t: Trip) = (t.liters ?: 0.0) >= 0.05
        val pureEv = trips.filter { !usedEngine(it) && (it.kwh ?: 0.0) > 0 && it.distanceKm >= 1 }
        val evKm = pureEv.sumOf { it.distanceKm }
        val rate = if (evKm > 3) pureEv.sumOf { it.kwh ?: 0.0 } / evKm else FALLBACK_KWH_PER_KM
        var ev = 0.0; var eng = 0.0; var kwh = 0.0
        for (t in trips) {
            val k = t.distanceKm
            kwh += t.kwh ?: 0.0
            if (!usedEngine(t)) ev += k
            else if ((t.kwh ?: 0.0) < 0.05) eng += k
            else { val e = ((t.kwh ?: 0.0) / rate).coerceIn(0.0, k); ev += e; eng += k - e }
        }
        return Result(trips.sumOf { it.distanceKm }, ev, eng, kwh, if (evKm > 3) rate * 100 else null)
    }
}
