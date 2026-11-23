package no.lionflow.app.model

import no.lionflow.app.api.num
import no.lionflow.app.api.obj
import no.lionflow.app.api.objects
import no.lionflow.app.api.str
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Program(val slot: Int, val days: List<String>, val hour: Int, val minute: Int, val enabled: Boolean)

data class VehicleStatus(
    val raw: JSONObject,
    val batteryPct: Double?,
    val rangeKm: Double?,
    val plugged: Boolean,
    val chargingStatus: String?,      // InProgress | Finished | Stopped | Disconnected | Failure
    val chargingMode: String?,        // Slow | Quick | No
    val chargingRateKmh: Double?,
    val remainingMinutes: Int?,
    val nextDelayedHour: Int?,
    val nextDelayedMinute: Int?,
    val batteryHealthPct: Double?,
    val odometerKm: Double?,
    val locked: Boolean?,
    val openDoors: List<String>,
    val outsideTempC: Double?,
    val preconditioningOn: Boolean,
    val programs: List<Program>,
    val lat: Double?,
    val lon: Double?,
    val ignition: String?,
    val moving: Boolean,
    val updatedAtMillis: Long?,
    val kmToService: Double?,
    val daysToService: Double?,
    val residualKwh: Double? = null,   // energy in the battery now
    val capacityKwh: Double? = null,   // usable capacity reported by the car
    val energyAtMillis: Long? = null,  // when the battery reading was taken
    val rangeEstimated: Boolean = false,

    val chargeType: String? = null,
    val energyType: String = "Electric", // Electric | Fuel | Hybrid
    val fuelPct: Double? = null,
    val fuelRangeKm: Double? = null,
    val isMildHybrid: Boolean = false,
) {
    val isCharging: Boolean get() = chargingStatus == "InProgress"

    /** The program block the remote-preconditioning command expects. */
    fun programsJson(): JSONObject = programsJsonOf(programs)

    companion object {
        /** The car's 4 schedule slots. An empty slot is sent as 34:07, off (as MyPeugeot does). */
        fun programsJsonOf(programs: List<Program>): JSONObject {
            val out = JSONObject()
            for (slot in 1..4) {
                val p = programs.firstOrNull { it.slot == slot }
                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").map { if (p != null && it in p.days) 1 else 0 }
                out.put("program$slot", JSONObject()
                    .put("day", JSONArray(days))
                    .put("hour", p?.hour ?: 34)
                    .put("minute", p?.minute ?: 7)
                    .put("on", if (p?.enabled == true) 1 else 0))
            }
            return out
        }

        /**
         * The battery level of the electric part. Some plug-in hybrids (seen on a 2017 DS 7) report "100 %" with
         * 0 km of electric range while the battery is actually empty: a full plug-in battery always gives some
         * range, so 100 % with 0 km (and not charging) is shown as empty.
         */
        private fun hybridLevel(electric: JSONObject, fuel: JSONObject?, charging: JSONObject?): Double? {
            val level = electric.num("level") ?: return if (fuel != null) 0.0 else null
            val plugged = charging?.optBoolean("plugged") == true || charging?.str("status") == "InProgress"
            if (fuel != null && !plugged && level >= 95 && electric.num("autonomy") == 0.0) return 0.0
            return level
        }

        fun parse(
            j: JSONObject,
            maintenance: JSONObject?,
            mildHybrid: Boolean = false,
            requireChargingEvidence: Boolean = false,
        ): VehicleStatus {
            val energies = j.optJSONArray("energies")?.objects().orEmpty()
            val reportedElectric = energies.firstOrNull { it.optString("type") == "Electric" }
            val reportedCharging = reportedElectric?.obj("extension", "electric", "charging")
            // Generic hybrid metadata needs positive evidence of an external charge connection.
            val externalCharging = reportedCharging?.optBoolean("plugged") == true ||
                reportedCharging?.str("status") in setOf("InProgress", "Finished")
            val fuelOnly = mildHybrid || (requireChargingEvidence && !externalCharging)
            val electric = reportedElectric.takeUnless { fuelOnly }
            val fuel = energies.firstOrNull { it.optString("type") == "Fuel" }
            val charging = electric?.obj("extension", "electric", "charging")
            val health = electric?.obj("extension", "electric", "battery", "health")
            val load = electric?.obj("extension", "electric", "battery", "load")
            fun kwh(k: String) = load?.num(k)?.takeIf { it >= 1 }?.let { it / 1000.0 }
            val cap = kwh("capacity")?.takeIf { it in 30.0..130.0 }
            val res = kwh("residual")?.takeIf { cap == null || it <= cap * 1.05 }
            val doors = j.optJSONObject("doorsState")
            val lockedStates = doors?.optJSONArray("lockedStates")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val openings = doors?.optJSONArray("opening")?.objects().orEmpty()
                .filter { it.optString("state") == "Open" }.map { it.optString("identifier") }
            val coords = j.obj("lastPosition", "geometry")?.optJSONArray("coordinates")
            val ac = j.obj("preconditioning", "airConditioning")
            val programs = ac?.optJSONArray("programs")?.objects().orEmpty().mapNotNull { p ->
                val start = parsePt(p.optString("start")) ?: return@mapNotNull null
                val days = p.optJSONObject("occurence")?.optJSONArray("day")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                Program(p.optInt("slot"), days, start.first, start.second, p.optBoolean("enabled"))
            }
            val next = charging?.str("nextDelayedTime")?.let { parsePt(it) }
            val remaining = charging?.str("remainingTime")?.let { parsePt(it) }?.let { it.first * 60 + it.second }
            val m = maintenance ?: j.optJSONObject("maintenance")

            return VehicleStatus(
                raw = j,
                // Petrol/diesel cars: the fuel tank stands in for the battery. A plug-in hybrid never shows the
                // tank as its battery: some (older DS7 / 3008 Hybrid4) leave out the battery level when it is empty.
                batteryPct = if (electric != null) hybridLevel(electric, fuel, charging) else fuel?.num("level"),
                rangeKm = if (electric != null) electric.num("autonomy") ?: (if (fuel != null && electric.num("level") == null) 0.0 else null) else fuel?.num("autonomy"),
                // Non-plug-in hybrids use the existing fuel-only display capabilities.
                energyType = when {
                    fuelOnly || (electric == null && fuel != null) -> "Fuel"
                    electric != null && fuel != null -> "Hybrid"
                    else -> "Electric"
                },
                isMildHybrid = mildHybrid,
                fuelPct = fuel?.num("level"),
                fuelRangeKm = fuel?.num("autonomy"),
                plugged = charging?.optBoolean("plugged") ?: false,
                chargingStatus = charging?.str("status"),
                chargingMode = charging?.str("chargingMode"),
                chargeType = charging?.str("type"),
                chargingRateKmh = charging?.num("chargingRate"),
                remainingMinutes = remaining,
                nextDelayedHour = next?.first,
                nextDelayedMinute = next?.second,
                batteryHealthPct = health?.num("capacity") ?: health?.num("resistance"),
                odometerKm = j.optJSONObject("odometer")?.num("mileage"),
                locked = if (lockedStates.isEmpty()) null else lockedStates.any { it.contains("Locked") && !it.contains("Unlocked") },
                openDoors = openings,
                outsideTempC = j.obj("environment", "air")?.num("temp"),
                preconditioningOn = ac?.optString("status") == "Enabled",
                programs = programs,
                lat = coords?.optDouble(1)?.takeIf { !it.isNaN() },
                lon = coords?.optDouble(0)?.takeIf { !it.isNaN() },
                ignition = j.optJSONObject("ignition")?.str("type"),
                moving = j.optJSONObject("kinetic")?.optBoolean("moving") ?: false,
                updatedAtMillis = parseIso(j.optString("updatedAt")),
                kmToService = m?.num("mileageBeforeMaintenance"),
                daysToService = m?.num("daysBeforeMaintenance"),
                residualKwh = res,
                capacityKwh = cap,
                energyAtMillis = parseIso(electric?.optString("updatedAt")),
            )
        }

        /** "PT7H30M" -> (7, 30) */
        fun parsePt(s: String?): Pair<Int, Int>? {
            if (s.isNullOrBlank() || !s.startsWith("PT")) return null
            val h = Regex("(\\d+)H").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            val mi = Regex("(\\d+)M").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            return h to mi
        }

        fun parseIso(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            for (p in patterns) {
                try {
                    val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    return f.parse(s)?.time
                } catch (_: Exception) {}
            }
            return null
        }
    }
}

data class Trip(
    val id: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val distanceKm: Double,
    val durationSec: Double?,
    val kwh: Double?,
    val avgKwhPer100: Double?,
    val avgSpeedKmh: Double?,
    val liters: Double? = null,
    val avgLPer100: Double? = null,
) {
    companion object {
        /** Peugeot doesn't document fuel units; pick the scale that gives a sane l/100 km (2–30). */
        private fun litersOf(raw: Double?, km: Double): Double? {
            if (raw == null || raw <= 0 || km < 0.5) return null
            return listOf(1.0, 0.01, 0.001).map { raw * it }.firstOrNull { it / km * 100 in 2.0..30.0 }
        }
        private fun per100Of(raw: Double?): Double? {
            if (raw == null || raw <= 0) return null
            return listOf(1.0, 0.1, 0.01, 0.001).map { raw * it }.firstOrNull { it in 2.0..30.0 }
        }

        fun parse(t: JSONObject, mildHybrid: Boolean = false): Trip {
            val electric = t.optJSONArray("energyConsumptions")?.objects()?.firstOrNull { !mildHybrid && it.optString("type") == "Electric" }
            val fuel = t.optJSONArray("energyConsumptions")?.objects()?.firstOrNull { it.optString("type") == "Fuel" }
            val km = t.num("distance") ?: 0.0
            val liters = litersOf(fuel?.num("consumption"), km)
            return Trip(
                id = t.optString("id"),
                startMillis = VehicleStatus.parseIso(t.str("startedAt") ?: t.str("createdAt")),
                endMillis = VehicleStatus.parseIso(t.str("stoppedAt")),
                distanceKm = t.num("distance") ?: 0.0,
                durationSec = t.num("duration"),
                kwh = electric?.num("consumption")?.let { it / 1000.0 },
                avgKwhPer100 = electric?.num("avgConsumption")?.let { it / 1000.0 },
                // Work it out from distance and time: the car's own "avgSpeed" is in m/s on some models and km/h on others.
                avgSpeedKmh = t.num("duration")?.takeIf { it >= 60 && km > 0 }?.let { km / (it / 3600.0) }
                    ?: t.optJSONObject("kinetic")?.num("avgSpeed")?.let { v -> if (km > 0) v else null },
                liters = liters,
                // Work it out from fuel used and distance; the car's own average comes in different units (some trips were km/l).
                avgLPer100 = liters?.let { it / km * 100 } ?: per100Of(fuel?.num("avgConsumption")),
            )
        }
    }
}
