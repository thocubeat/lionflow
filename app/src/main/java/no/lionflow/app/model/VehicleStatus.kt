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
) {
    val isCharging: Boolean get() = chargingStatus == "InProgress"

    /** The program block the remote-preconditioning command expects. */
    fun programsJson(): JSONObject {
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

    companion object {
        fun parse(j: JSONObject, maintenance: JSONObject?): VehicleStatus {
            val energies = j.optJSONArray("energies")?.objects().orEmpty()
            val electric = energies.firstOrNull { it.optString("type") == "Electric" }
            val charging = electric?.obj("extension", "electric", "charging")
            val health = electric?.obj("extension", "electric", "battery", "health")
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
                batteryPct = electric?.num("level"),
                rangeKm = electric?.num("autonomy"),
                plugged = charging?.optBoolean("plugged") ?: false,
                chargingStatus = charging?.str("status"),
                chargingMode = charging?.str("chargingMode"),
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
) {
    companion object {
        fun parse(t: JSONObject): Trip {
            val electric = t.optJSONArray("energyConsumptions")?.objects()?.firstOrNull { it.optString("type") == "Electric" }
            return Trip(
                id = t.optString("id"),
                startMillis = VehicleStatus.parseIso(t.str("startedAt") ?: t.str("createdAt")),
                endMillis = VehicleStatus.parseIso(t.str("stoppedAt")),
                distanceKm = t.num("distance") ?: 0.0,
                durationSec = t.num("duration"),
                kwh = electric?.num("consumption")?.let { it / 1000.0 },
                avgKwhPer100 = electric?.num("avgConsumption")?.let { it / 1000.0 },
                avgSpeedKmh = t.optJSONObject("kinetic")?.num("avgSpeed")?.let { it * 3.6 },
            )
        }
    }
}
