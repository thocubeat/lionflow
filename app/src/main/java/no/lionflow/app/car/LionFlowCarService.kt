package no.lionflow.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.model.ChargePower
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp

/**
 * LionFlow in Android Auto: a simple overview of the car (battery, charging power, range, climate,
 * doors, service, trips) with a few remote actions. No map.
 */
class LionFlowCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Only Android Auto / Google's car hosts may connect (or anything, in a debug build).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(): Session = MainSession()
}

private fun CarContext.icon(res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let { setTint(it) } }.build()

private fun CarContext.listLimit(): Int = try {
    getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
        .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (_: Exception) { 6 }

class MainSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarHub.notices.collect { m -> CarToast.makeText(carContext, m, CarToast.LENGTH_LONG).show() }
            }
        }
        CarHub.refresh(carContext)
        CarHub.loadTrips(carContext)
        return OverviewScreen(carContext)
    }
}

/** Base screen that redraws whenever the shared car state changes. */
abstract class LiveScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(CarHub.status, CarHub.message, CarHub.busy, CarHub.chargers) { _, _, _, _ -> Unit }
                    .collect { invalidate() }
            }
        }
    }
}

// ---------------------------------------------------------------- overview

class OverviewScreen(ctx: CarContext) : LiveScreen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        val app = CarHub.app(carContext)
        if (!app.api.isSignedIn) {
            return MessageTemplate.Builder("Open LionFlow on your phone and sign in with your Peugeot account.")
                .setTitle("LionFlow").setHeaderAction(Action.APP_ICON).build()
        }
        val s = app.settings.value
        val st = CarHub.status.value
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Refresh").setOnClickListener {
                CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true); CarHub.say("Updating…")
            }.build())
        if (CarHub.actionsAllowed(carContext) == null)
            strip.addAction(Action.Builder().setTitle("Actions").setOnClickListener { screenManager.push(ActionsScreen(carContext)) }.build())
        val b = ListTemplate.Builder().setTitle(s.carName).setHeaderAction(Action.APP_ICON).setActionStrip(strip.build())
        if (st == null) return b.setLoading(true).build()

        // Battery & charging
        val power = ChargePower.label(st)
        val charge = ItemList.Builder()
            .addItem(Row.Builder()
                .setTitle("${st.batteryPct?.toInt() ?: "—"} %  ·  ≈ ${fmtDistance(st.rangeKm, s)}" + if (st.rangeEstimated) " (est.)" else "")
                .addText(CarHub.chargingLine(st))
                .setImage(carContext.icon(R.drawable.ic_car_battery)).build())
        if (st.isCharging) charge.addItem(Row.Builder()
            .setTitle("Charging power: ${power ?: "calculating…"}")
            .addText(st.chargingRateKmh?.takeIf { it > 0 }?.let { "Adds ${fmtDistance(it, s)} of range per hour" } ?: "Measured from the energy going into the battery")
            .setImage(carContext.icon(R.drawable.ic_car_bolt, CarColor.GREEN)).build())

        // Car
        val car = ItemList.Builder()
            .addItem(Row.Builder()
                .setTitle(if (st.preconditioningOn) "Pre-heating is on" else "Climate")
                .addText(st.outsideTempC?.let { "${fmtTemp(it, s)} outside" } ?: "Outside temperature unknown")
                .setImage(carContext.icon(R.drawable.ic_car_climate)).build())
            .addItem(Row.Builder()
                .setTitle("Doors: ${CarHub.doorsLine(st)}")
                .addText(CarHub.updatedLine())
                .setImage(carContext.icon(R.drawable.ic_car_lock)).build())
            .addItem(Row.Builder()
                .setTitle(st.odometerKm?.let { "Odometer ${fmtDistance(it, s)}" } ?: "Odometer unknown")
                .addText(serviceLine(st, s))
                .setImage(carContext.icon(R.drawable.ic_car_refresh)).build())

        // Driving
        val trips = CarHub.trips.value
        val week = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }
        val km = week?.sumOf { it.distanceKm } ?: 0.0
        val kwh = week?.mapNotNull { it.kwh }?.sum() ?: 0.0
        val driving = ItemList.Builder().addItem(Row.Builder()
            .setTitle(if (trips == null) "Driving history" else "Last 7 days: ${fmtDistance(km, s)} · ${week?.size ?: 0} trips")
            .addText(when {
                trips == null -> "Loading…"
                km > 1 && kwh > 0 -> consumption(kwh / km * 100, s) + " average · tap for trips"
                else -> "Tap for recent trips"
            })
            .setImage(carContext.icon(R.drawable.ic_car_route))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(TripsScreen(carContext)) }.build())
            .addItem(Row.Builder().setTitle("Chargers nearby").addText("Nearest first")
                .setImage(carContext.icon(R.drawable.ic_car_charger)).setBrowsable(true)
                .setOnClickListener { screenManager.push(ChargersScreen(carContext)) }.build())

        CarHub.message.value?.let { m -> car.addItem(Row.Builder().setTitle(m).build()) }

        return b
            .addSectionedList(SectionedItemList.create(charge.build(), "Battery"))
            .addSectionedList(SectionedItemList.create(car.build(), "Car"))
            .addSectionedList(SectionedItemList.create(driving.build(), "Driving"))
            .build()
    }

    private fun serviceLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String {
        val parts = mutableListOf<String>()
        st.kmToService?.let { parts += "service in ${fmtDistance(it, s)}" }
        st.daysToService?.let { d -> parts += if (d >= 60) "${(d / 30).toInt()} months" else "${d.toInt()} days" }
        return if (parts.isEmpty()) "Next service unknown" else "Next " + parts.joinToString(" or ")
    }

    private fun consumption(kwhPer100Km: Double, s: no.lionflow.app.data.AppSettings) =
        if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
        else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwhPer100Km)
}

// ---------------------------------------------------------------- remote actions

class ActionsScreen(ctx: CarContext) : LiveScreen(ctx) {
    override fun onGetTemplate(): Template {
        val st = CarHub.status.value
        val busy = CarHub.busy.value
        val list = ItemList.Builder()
        fun row(title: String, text: String, icon: Int, onClick: () -> Unit) =
            list.addItem(Row.Builder().setTitle(title).addText(text).setImage(carContext.icon(icon))
                .setEnabled(!busy).setOnClickListener(onClick).build())

        val locked = st?.locked == true
        row(if (locked) "Unlock" else "Lock", if (locked) "Doors are locked" else "Doors are unlocked", R.drawable.ic_car_lock) { CarHub.lock(carContext, !locked) }
        val heat = st?.preconditioningOn == true
        row(if (heat) "Stop pre-heating" else "Pre-heat", "Heats or cools the cabin for about 30 minutes", R.drawable.ic_car_climate) { CarHub.preheat(carContext, !heat) }
        val charging = st?.isCharging == true
        row(if (charging) "Stop charging" else "Charge now", st?.let { CarHub.chargingLine(it) } ?: "—", R.drawable.ic_car_bolt) { CarHub.charge(carContext, !charging) }
        row("Flash lights", "Find the car in a car park", R.drawable.ic_car_lights) { CarHub.lights(carContext) }
        row("Horn", "Sounds the horn twice", R.drawable.ic_car_horn) { CarHub.horn(carContext) }

        return ListTemplate.Builder()
            .setTitle(CarHub.message.value ?: "Remote actions")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

// ---------------------------------------------------------------- chargers (list only)

class ChargersScreen(ctx: CarContext) : LiveScreen(ctx) {
    init {
        CarHub.status.value?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> CarHub.loadChargers(ctx, la, lo) } } }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val all = CarHub.chargers.value
        val b = ListTemplate.Builder().setTitle("Chargers near the car").setHeaderAction(Action.BACK)
        if (all.isEmpty()) return b.setLoading(true).build()
        val list = ItemList.Builder()
        all.take(carContext.listLimit()).forEach { ch ->
            val bits = mutableListOf<String>()
            ch.distanceM?.let { m -> bits += if (s.distanceUnit != "mi" && m < 1000) "$m m" else if (s.distanceUnit != "mi" && m < 10_000) String.format(java.util.Locale.US, "%.1f km", m / 1000.0) else fmtDistance(m / 1000.0, s) }
            if (ch.maxKw > 0) bits += "${ch.maxKw.toInt()} kW"
            if (ch.points > 0) bits += "${ch.points} points"
            list.addItem(Row.Builder().setTitle(ch.name).addText(bits.joinToString(" · ").ifBlank { ch.address })
                .setImage(carContext.icon(R.drawable.ic_car_charger, if (ch.maxKw >= 50) CarColor.GREEN else null)).build())
        }
        return b.setSingleList(list.build()).build()
    }
}

// ---------------------------------------------------------------- driving history

class TripsScreen(ctx: CarContext) : Screen(ctx) {
    init {
        CarHub.loadTrips(ctx)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val trips = CarHub.trips.value
        val b = ListTemplate.Builder().setTitle("Driving history").setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("Refresh")
                .setOnClickListener { CarHub.loadTrips(carContext, force = true) }.build()).build())
        if (trips == null) return b.setLoading(true).build()
        val list = ItemList.Builder()
        if (trips.isEmpty()) {
            list.setNoItemsMessage("No trips in the last 30 days")
            return b.setSingleList(list.build()).build()
        }
        val max = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Exception) { 6 }

        // Summary of the last 7 days first.
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val week = trips.filter { (it.startMillis ?: 0L) >= weekAgo }
        val km = week.sumOf { it.distanceKm }
        val kwh = week.mapNotNull { it.kwh }.sum()
        val avg = if (km > 1 && kwh > 0) kwh / km * 100 else null
        list.addItem(Row.Builder()
            .setTitle("Last 7 days: ${fmtDistance(km, s)} · ${week.size} trips")
            .addText(avg?.let { consumption(it, s) } ?: "Consumption not reported")
            .setImage(carContext.icon(R.drawable.ic_car_route)).build())

        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.ENGLISH)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH)
        val today = day.format(java.util.Date())
        trips.take((max - 1).coerceAtLeast(1)).forEach { t ->
            val start = t.startMillis?.let { java.util.Date(it) }
            val whenTxt = start?.let { d -> (if (day.format(d) == today) "Today" else day.format(d)) + " " + time.format(d) } ?: "Trip"
            val bits = mutableListOf<String>()
            t.durationSec?.takeIf { it > 0 }?.let { sec -> val m = (sec / 60).toInt(); bits += if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
            t.avgKwhPer100?.let { bits += consumption(it, s) }
            t.avgSpeedKmh?.takeIf { it > 0 }?.let { v -> bits += if (s.distanceUnit == "mi") "${Math.round(v * 0.621371)} mph avg" else "${Math.round(v)} km/h avg" }
            list.addItem(Row.Builder().setTitle("$whenTxt · ${fmtDistance(t.distanceKm, s)}")
                .addText(bits.joinToString(" · ").ifBlank { "No details" }).build())
        }
        return b.setSingleList(list.build()).build()
    }

    private fun consumption(kwhPer100Km: Double, s: no.lionflow.app.data.AppSettings) =
        if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
        else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwhPer100Km)
}

