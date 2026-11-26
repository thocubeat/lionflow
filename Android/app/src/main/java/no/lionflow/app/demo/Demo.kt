package no.lionflow.app.demo

import android.content.Context
import no.lionflow.app.api.Vehicle
import no.lionflow.app.model.ChargePlace
import no.lionflow.app.model.ChargeSession
import no.lionflow.app.model.PlacesConfig
import no.lionflow.app.model.Program
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Demo mode: a sample car with made-up data, for trying the app (and for Google Play's reviewers)
 * without a MyPeugeot account. Nothing is sent anywhere; commands only change the sample car.
 */
object Demo {
    const val VIN = "VR3DEMOLIONFLOW00"

    /** Which sample car is shown: "electric", "hybrid" (plug-in hybrid) or "fuel" (petrol or diesel). Chosen when the demo starts. */
    @Volatile var kind = "electric"
        private set
    private val kinds = setOf("electric", "hybrid", "fuel")
    fun load(ctx: Context) { kind = prefs(ctx).getString("kind", "electric")?.takeIf { it in kinds } ?: "electric" }
    fun choose(ctx: Context, k: String) {
        kind = k.takeIf { it in kinds } ?: "electric"
        prefs(ctx).edit().putString("kind", kind).apply()
    }

    /** The sample car, of the chosen kind and of the brand picked for the demo. */
    val vehicle: Vehicle
        get() = Vehicle(id = "demo", vin = VIN, motorization = when (kind) { "fuel" -> "Thermal"; "hybrid" -> "PHEV"; else -> "Electric" },
            brand = no.lionflow.app.model.Brands.current.display, pictureUrl = null, maintenanceHref = null)

    // A neutral spot (Paris) for the sample car's home.
    private const val HOME_LAT = 48.8566
    private const val HOME_LON = 2.3522

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE)
    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("on", on).apply() }

    // The sample car's state; commands change it.
    private var battery = 64.0
    private var locked = true
    private var precond = false
    private var charging = true
    private var plugged = true
    private var chargeType = "Partial"
    private var programs = listOf(
        Program(1, listOf("Mon", "Tue", "Wed", "Thu", "Fri"), 7, 15, true),
        Program(2, listOf("Sat"), 9, 0, false),
        Program(3, emptyList(), 0, 0, false),
        Program(4, emptyList(), 0, 0, false),
    )

    fun reset() { battery = 64.0; locked = true; precond = false; charging = true; plugged = true; chargeType = "Partial" }

    // The tank of the hybrid and the petrol/diesel sample car.
    private const val FUEL_PCT = 58.0
    private const val FUEL_RANGE = 530.0

    fun status(): VehicleStatus {
        val now = System.currentTimeMillis()
        val fuelOnly = kind == "fuel"
        val hybrid = kind == "hybrid"
        // A plug-in hybrid has a small battery: about 12 kWh and 55 km when full, charged at 3.7 kW.
        val cap = if (hybrid) 12.4 else 73.0
        val evRange = if (hybrid) battery * 0.55 else battery * 4.3
        val on = charging && !fuelOnly
        return VehicleStatus(
            raw = raw(now, cap, evRange),
            batteryPct = if (fuelOnly) FUEL_PCT else battery, rangeKm = if (fuelOnly) FUEL_RANGE else evRange, plugged = plugged && !fuelOnly,
            chargingStatus = if (fuelOnly) null else if (charging) "InProgress" else if (plugged) "Stopped" else "Disconnected",
            chargingMode = if (fuelOnly) null else if (charging) "Slow" else "No", chargingRateKmh = if (on) (if (hybrid) 16.0 else 48.0) else null,
            remainingMinutes = if (!on) null else if (hybrid) ((100 - battery).coerceAtLeast(0.0) * 2).toInt() else ((80 - battery).coerceAtLeast(0.0) * 9).toInt(),
            nextDelayedHour = if (fuelOnly) null else 1, nextDelayedMinute = if (fuelOnly) null else 0,
            batteryHealthPct = if (fuelOnly) null else 98.0, odometerKm = 13_412.0,
            locked = locked, openDoors = emptyList(), outsideTempC = 6.0, preconditioningOn = precond && !fuelOnly,
            programs = if (fuelOnly) emptyList() else programs, lat = HOME_LAT, lon = HOME_LON, ignition = "Stop", moving = false,
            updatedAtMillis = now - 2 * 60_000, kmToService = 11_588.0, daysToService = 270.0,
            residualKwh = if (fuelOnly) null else battery / 100 * cap, capacityKwh = if (fuelOnly) null else cap,
            energyAtMillis = if (fuelOnly) null else now, chargeType = if (fuelOnly) null else chargeType,
            energyType = when { fuelOnly -> "Fuel"; hybrid -> "Hybrid"; else -> "Electric" },
            fuelPct = if (fuelOnly || hybrid) FUEL_PCT else null, fuelRangeKm = if (fuelOnly || hybrid) FUEL_RANGE else null,
            heading = 118.0, privacyState = "None",
            // The lifetime fuel counter, in hundredths of a litre: 5.9 l/100 km since new (3.6 for the plug-in hybrid).
            fuelTotalRaw = when { fuelOnly -> 13_412.0 * 5.9; hybrid -> 13_412.0 * 3.6; else -> null },
        )
    }

    /** What the carmaker's server would have sent for the sample car, so "All car data" has something to show. */
    private fun raw(now: Long, cap: Double, evRange: Double): JSONObject {
        val fuelOnly = kind == "fuel"
        val at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(now - 2 * 60_000))
        val energies = JSONArray()
        if (!fuelOnly) energies.put(JSONObject().put("type", "Electric").put("level", battery).put("autonomy", Math.round(evRange)).put("updatedAt", at)
            .put("extension", JSONObject().put("electric", JSONObject()
                .put("charging", JSONObject().put("plugged", plugged).put("status", if (charging) "InProgress" else if (plugged) "Stopped" else "Disconnected")
                    .put("chargingMode", if (charging) "Slow" else "No").put("type", chargeType))
                .put("battery", JSONObject().put("load", JSONObject().put("capacity", Math.round(cap * 1000)).put("residual", Math.round(battery / 100 * cap * 1000)))
                    .put("health", JSONObject().put("capacity", 98))))))
        if (kind != "electric") energies.put(JSONObject().put("type", "Fuel").put("level", FUEL_PCT).put("autonomy", FUEL_RANGE).put("updatedAt", at))
        val j = JSONObject().put("demo", true).put("updatedAt", at).put("energies", energies)
            .put("odometer", JSONObject().put("mileage", 13_412.0))
            .put("battery", JSONObject().put("voltage", 12.6))
            .put("doorsState", JSONObject().put("lockedStates", JSONArray().put(if (locked) "Locked" else "Unlocked")).put("opening", JSONArray()))
            .put("environment", JSONObject().put("air", JSONObject().put("temp", 6.0)))
            .put("ignition", JSONObject().put("type", "Stop"))
            .put("kinetic", JSONObject().put("moving", false).put("speed", 0))
            .put("privacy", JSONObject().put("state", "None"))
            .put("lightingSystem", JSONObject().put("fog", JSONArray().put(JSONObject().put("direction", "Front").put("status", "Off"))))
            .put("lastPosition", JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(HOME_LON).put(HOME_LAT)))
                .put("properties", JSONObject().put("heading", 118)))
        // Petrol and diesel cars have no pre-heating, so the Climate tab is left out for them, as on a real one.
        if (!fuelOnly) j.put("preconditioning", JSONObject().put("airConditioning", JSONObject().put("status", if (precond) "Enabled" else "Disabled")))
        return j
    }

    /** Applies a simulated command to the sample car. */
    fun apply(label: String, programsNew: List<Program>? = null) {
        when (label) {
            "Lock" -> locked = true
            "Unlock" -> locked = false
            "Pre-heating" -> precond = true
            "Stop pre-heating" -> precond = false
            "Charge now" -> { plugged = true; charging = true }
            "Stop charging" -> charging = false
            "Daily charge limit" -> chargeType = "Partial"
            "Full charge for a trip" -> chargeType = "Full"
        }
        programsNew?.let { programs = it }
    }

    /**
     * A month of drives for the electric car. The other two get four months, so the fuel statistics have months and
     * fill-ups to show: the petrol/diesel car burns fuel on every trip, the plug-in hybrid drives short trips on the
     * battery and uses the engine on the long ones.
     */
    fun trips(): List<Trip> {
        val r = Random(42)
        val day = 86_400_000L
        val days = if (kind == "electric") 30 else 120
        val start = System.currentTimeMillis() - days * day
        val out = mutableListOf<Trip>()
        for (d in 0 until days) {
            val n = r.nextInt(1, 4)
            for (i in 0 until n) {
                val t = start + d * day + (7 + i * 5 + r.nextInt(0, 3)) * 3_600_000L + r.nextInt(0, 50) * 60_000L
                val km = if (r.nextInt(10) == 0) r.nextDouble(60.0, 140.0) else r.nextDouble(3.0, 28.0)
                val speed = if (km > 50) r.nextDouble(70.0, 95.0) else r.nextDouble(28.0, 55.0)
                val sec = km / speed * 3600
                val per100 = r.nextDouble(15.5, 21.0)
                val end = t + (sec * 1000).toLong()
                out += when (kind) {
                    "fuel" -> {
                        val l100 = if (km > 50) r.nextDouble(4.6, 5.8) else r.nextDouble(5.6, 8.4)
                        Trip("demo-$d-$i", t, end, km, sec, null, null, speed, liters = km * l100 / 100, avgLPer100 = l100)
                    }
                    "hybrid" -> {
                        // The first 40 km of a trip on the battery, the rest on the engine.
                        val evKm = minOf(km, 40.0)
                        val liters = if (km > evKm) (km - evKm) * r.nextDouble(5.8, 6.8) / 100 else null
                        Trip("demo-$d-$i", t, end, km, sec, evKm * per100 / 100, if (liters == null) per100 else null, speed,
                            liters = liters, avgLPer100 = liters?.let { it / km * 100 })
                    }
                    else -> Trip("demo-$d-$i", t, end, km, sec, km * per100 / 100, per100, speed)
                }
            }
        }
        if (kind == "electric") return out.sortedByDescending { it.startMillis }
        // The fuel gauge before and after each trip, with a fill-up whenever the tank gets low.
        val tank = if (kind == "hybrid") 43.0 else 47.0
        var level = 82.0
        return out.sortedBy { it.startMillis }.map { trip ->
            if (level < 18) level = 95.0 + r.nextInt(0, 5)
            val after = (level - (trip.liters ?: 0.0) / tank * 100).coerceAtLeast(3.0)
            val withGauge = trip.copy(fuelStartPct = Math.round(level).toDouble(), fuelEndPct = Math.round(after).toDouble())
            level = after
            withGauge
        }.sortedByDescending { it.startMillis }
    }

    fun places() = PlacesConfig(
        home = ChargePlace("home", "Home", "Sample street 1, Paris", HOME_LAT, HOME_LON, 0.25),
        known = listOf(ChargePlace("k1", "Work", "Office car park", HOME_LAT + 0.03, HOME_LON + 0.04, 0.0)),
        publicAcPrice = 0.45, publicDcPrice = 0.69, currency = "€",
    )

    /** Charging sessions for the log: none for the petrol/diesel car; the plug-in hybrid only charges slowly, at home and at work. */
    fun sessions(): List<ChargeSession> {
        if (kind == "fuel") return emptyList()
        val hybrid = kind == "hybrid"
        val cap = if (hybrid) 12.4 else 73.0
        val r = Random(7)
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        return (1..14).map { i ->
            val where = when (i % 5) { 0 -> if (hybrid) 0 else 2; 3 -> 1; else -> 0 } // 0 home, 1 work, 2 fast charger
            val s = now - i * 2 * day - r.nextInt(0, 6) * 3_600_000L
            val from = r.nextDouble(18.0, 50.0)
            val to = if (where == 2) r.nextDouble(70.0, 82.0) else if (hybrid) 100.0 else 80.0
            val kwh = (to - from) / 100 * cap
            val kw = when { where == 2 -> r.nextDouble(70.0, 110.0); hybrid -> r.nextDouble(3.3, 3.7); else -> r.nextDouble(6.5, 10.5) }
            val (lat, lon, addr) = when (where) {
                0 -> Triple(HOME_LAT, HOME_LON, "Sample street 1, Paris")
                1 -> Triple(HOME_LAT + 0.03, HOME_LON + 0.04, "Office car park")
                else -> Triple(HOME_LAT + 0.4, HOME_LON - 0.3, "Motorway services A10")
            }
            ChargeSession(id = s, start = s, end = s + (kwh / kw * 3_600_000).toLong(), startPct = from, endPct = to, kwh = kwh,
                peakKw = kw * 1.15, mode = if (where == 2) "Quick" else "Slow", lat = lat, lon = lon, address = addr,
                source = if (where == 2) "Fast charger" else null)
        }
    }
}
