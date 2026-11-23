package no.lionflow.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TripExport {
    fun csv(trips: List<Trip>, tagName: (Trip) -> String?): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        appendLine("id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag,fuel_liters,avg_l_per_100km,fuel_units_estimated")
        for (t in trips) {
            appendLine(listOf(
                t.id,
                t.startMillis?.let { iso.format(Date(it)) }.orEmpty(),
                t.endMillis?.let { iso.format(Date(it)) }.orEmpty(),
                String.format(Locale.US, "%.1f", t.distanceKm),
                t.durationSec?.let { String.format(Locale.US, "%.0f", it / 60) }.orEmpty(),
                t.kwh?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
                t.avgKwhPer100?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                t.avgSpeedKmh?.let { String.format(Locale.US, "%.0f", it) }.orEmpty(),
                tagName(t)?.replace(Regex("[,\r\n\"]"), " ").orEmpty(),
                t.liters?.toString().orEmpty(),
                t.avgLPer100?.toString().orEmpty(),
                if (t.liters != null || t.avgLPer100 != null) t.fuelUnitsEstimated.toString() else "",
            ).joinToString(","))
        }
    }
}
