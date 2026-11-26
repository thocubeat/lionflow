package no.lionflow.app.car

import android.graphics.Canvas
import androidx.car.app.CarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.api.Charger
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.ListModel
import no.lionflow.app.car.ui.ListPaint
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.Garage
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import java.util.Locale
import kotlin.math.abs

/** "850 m", "1.2 km", "11 km" (or miles). */
internal fun carShortDistance(meters: Int, s: AppSettings): String = when {
    s.distanceUnit == "mi" -> fmtDistance(meters / 1000.0, s)
    meters < 1000 -> "$meters m"
    meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    else -> fmtDistance(meters / 1000.0, s)
}

internal fun carConsumption(kwhPer100Km: Double, s: AppSettings): String =
    if (s.distanceUnit == "mi") String.format(Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
    else String.format(Locale.US, "%.1f kWh/100 km", kwhPer100Km)

/**
 * A drawn list in pages. The arrows at the side turn the page, and so does a swipe up or down.
 * A subclass builds the rows ([build]) and says what a tap on one does ([picked]).
 */
internal abstract class ListScreen(ctx: CarContext, ui: CarUi) : DrawnScreen(ctx, ui) {
    private var page = 0
    private var pages = 1
    private var model: ListModel? = null
    private var swipe = 0f
    private var swipeAt = 0L
    private var turnedAt = 0L

    protected abstract fun build(): ListModel
    protected open fun picked(key: String) {}
    protected open fun refresh() {}

    /** The rows changed: build them again and draw. */
    protected fun changed() { model = null; redraw() }

    override val swipes: Boolean get() = true
    override fun shown() { model = null }
    override fun buttons() = listOf(Btn(R.drawable.ic_car_refresh) { refresh() })

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) {
        val m = model ?: build().also { model = it }
        val look = ListPaint.draw(c, k, m, page, pressed, hits)
        page = look.page; pages = look.pages
    }

    override fun tapped(key: String) {
        when (key) {
            ListPaint.UP -> turn(-1)
            ListPaint.DOWN -> turn(1)
            else -> picked(key)
        }
    }

    private fun turn(by: Int) {
        val p = (page + by).coerceIn(0, (pages - 1).coerceAtLeast(0))
        if (p != page) { page = p; redraw() }
    }

    /** One swipe turns one page: the distance adds up, and after a turn the rest of that swipe is ignored. */
    override fun onScroll(dx: Float, dy: Float) {
        val now = System.currentTimeMillis()
        if (now - turnedAt < 450) return
        if (now - swipeAt > 500) swipe = 0f
        swipeAt = now
        swipe += dy
        if (abs(swipe) >= unit * 12f) {
            turn(if (swipe > 0) 1 else -1)   // the car reports how far the content should move up
            swipe = 0f; turnedAt = now
        }
    }
}

// ---------------------------------------------------------------- previous trips

internal class TripListScreen(ctx: CarContext, ui: CarUi) : ListScreen(ctx, ui) {
    init {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { changed() } } }
    }

    override fun shown() { super.shown(); CarHub.loadTrips(carContext) }
    override fun refresh() { CarHub.loadTrips(carContext, force = true); CarHub.say("Updating…") }

    override fun build(): ListModel {
        val s = CarHub.app(carContext).settings.value
        val trips = CarHub.trips.value
        if (trips == null) return ListModel(tr("Previous trips"), null, rows = emptyList(), empty = tr("Loading…"))
        if (trips.isEmpty()) return ListModel(tr("Previous trips"), null, rows = emptyList(), empty = tr("No trips in the last 30 days"))
        val mildHybrid = CarHub.status.value?.isMildHybrid == true

        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val week = trips.filter { (it.startMillis ?: 0L) >= weekAgo }
        val km = week.sumOf { it.distanceKm }
        val kwh = if (mildHybrid) 0.0 else week.mapNotNull { it.kwh }.sum()
        val avg = if (km > 1 && kwh > 0) kwh / km * 100 else null
        val summary = ListModel.Summary(R.drawable.ic_car_history, tr("Last 7 days: ${fmtDistance(km, s)} · ${week.size} trips"), tr(avg?.let { carConsumption(it, s) } ?: "Consumption not reported"))

        val day = java.text.SimpleDateFormat("EEE d MMM", L10n.locale)
        val time = java.text.SimpleDateFormat(TimeFmt.pattern("HH:mm"), L10n.locale)
        val today = day.format(java.util.Date())
        val rows = trips.take(60).map { t ->
            val start = t.startMillis?.let { java.util.Date(it) }
            val title = start?.let { d -> (if (day.format(d) == today) tr("Today") else day.format(d)) + " " + time.format(d) } ?: tr("Trip")
            val bits = ArrayList<String>()
            t.durationSec?.takeIf { it > 0 }?.let { bits += carDuration(it / 60.0) }
            if (mildHybrid) t.avgLPer100?.let { bits += String.format(Locale.US, "%.1f l/100 km", it) }
            else t.avgKwhPer100?.let { bits += carConsumption(it, s) }
            t.avgSpeedKmh?.takeIf { it > 0 }?.let { v -> bits += tr(if (s.distanceUnit == "mi") "${Math.round(v * 0.621371)} mph avg" else "${Math.round(v)} km/h avg") }
            ListModel.Row(null, title, bits.joinToString(" · "), R.drawable.ic_car_route, end = fmtDistance(t.distanceKm, s))
        }
        return ListModel(tr("Previous trips"), null, summary = summary, rows = rows, empty = "")
    }
}

// ---------------------------------------------------------------- chargers and fuel stations near the car

internal class ChargerListScreen(ctx: CarContext, ui: CarUi) : ListScreen(ctx, ui) {
    /** What is listed, in the order shown: the place, and whether it is a fuel station. */
    private var listed: List<Pair<Charger, Boolean>> = emptyList()

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(CarHub.chargers, CarHub.fuel, CarHub.status) { _, _, _ -> Unit }.collect { changed() } }
                // Distances follow the car while the list shows; far from where the list was made, it is made again.
                launch { while (true) { kotlinx.coroutines.delay(5_000L); ui.guard.update(); if (movedFar()) load(); changed() } }
            }
        }
    }

    override fun shown() { super.shown(); load() }
    override fun refresh() { CarHub.refresh(carContext, force = true); load() }

    /** Where the car is now: the phone's GPS when fresh, else where the car last reported. */
    private fun here(): Pair<Double, Double>? {
        ui.guard.fix.value?.takeIf { System.currentTimeMillis() - it.time < 30_000L }?.let { return it.latitude to it.longitude }
        val st = CarHub.status.value ?: return null
        return (st.lat ?: return null) to (st.lon ?: return null)
    }
    private var loadedAt: Pair<Double, Double>? = null

    private fun movedFar(): Boolean {
        val a = loadedAt ?: return false; val b = here() ?: return false
        return CarHub.distanceM(a.first, a.second, b.first, b.second) > 3_000
    }

    private fun load() {
        val st = CarHub.status.value ?: return
        val (la, lo) = here() ?: return
        loadedAt = la to lo
        if (st.energyType != "Fuel") CarHub.loadChargers(carContext, la, lo)
        if (st.energyType != "Electric") CarHub.loadFuel(carContext, la, lo)
    }

    override fun build(): ListModel {
        val s = CarHub.app(carContext).settings.value
        val st = CarHub.status.value
        val type = st?.energyType ?: "Electric"
        val chargers = if (type == "Fuel") emptyList() else CarHub.chargers.value
        val fuel = if (type == "Electric") emptyList() else CarHub.fuel.value
        val title = tr(when (type) { "Fuel" -> "Fuel stations near the car"; "Hybrid" -> "Chargers and fuel near the car"; else -> "Chargers near the car" })
        // Nearest first, chargers and fuel stations together for a plug-in hybrid.
        val here = here()
        fun dist(c: Charger): Int? = here?.let { (la, lo) -> CarHub.distanceM(la, lo, c.lat, c.lon).toInt() } ?: c.distanceM
        listed = (chargers.take(40).map { it to false } + fuel.take(40).map { it to true }).sortedBy { dist(it.first) ?: Int.MAX_VALUE }.take(60)
        val rows = listed.mapIndexed { i, (ch, isFuel) ->
            val bits = ArrayList<String>()
            if (!isFuel && ch.maxKw > 0) bits += "${ch.maxKw.toInt()} kW"
            if (!isFuel && ch.points > 0) bits += tr("${ch.points} points")
            if (bits.isEmpty() && ch.address.isNotBlank()) bits += ch.address
            // A charger can be tapped: that plans the trip there, with the battery left on arrival.
            ListModel.Row(if (isFuel) null else "c$i", ch.name, bits.joinToString(" · "), if (isFuel) R.drawable.ic_car_fuel else R.drawable.ic_car_charger,
                lit = !isFuel && ch.maxKw >= 50, end = dist(ch)?.let { carShortDistance(it, s) } ?: "")
        }
        val empty = when {
            st == null -> tr("Getting the car's status…")
            here == null -> tr("The car hasn't shared its position")
            else -> tr("Loading…")
        }
        return ListModel(title, null, rows = rows, empty = empty)
    }

    override fun picked(key: String) {
        val (ch, _) = listed.getOrNull(key.removePrefix("c").toIntOrNull() ?: return) ?: return
        screenManager.push(TripScreen.plan(carContext, ui, Place(ch.name, ch.address, ch.lat, ch.lon)))
    }
}

// ---------------------------------------------------------------- trips saved on the phone

internal class SavedListScreen(ctx: CarContext, ui: CarUi) : ListScreen(ctx, ui) {
    init {
        // Trips saved or deleted on the phone show up here at once.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { SavedRoutes.changes.collect { changed() } } }
    }

    override fun refresh() { changed() }

    override fun build(): ListModel {
        val s = CarHub.app(carContext).settings.value
        val rows = SavedRoutes.all(carContext).map { r ->
            val stops = when (r.stops.size) { 0 -> "no charging stops"; 1 -> "1 charging stop"; else -> "${r.stops.size} charging stops" }
            ListModel.Row("r${r.id}", r.name.ifBlank { "—" }, fmtDistance(r.totalKm, s) + " · " + tr(stops), shape = ListModel.Shape(r.lat, r.lon))
        }
        return ListModel(tr("Saved routes"), null, rows = rows, empty = tr("Plan a trip on your phone and tap Save trip."))
    }

    override fun picked(key: String) {
        val id = key.removePrefix("r").toLongOrNull() ?: return
        screenManager.push(TripScreen.saved(carContext, ui, id))
    }
}

// ---------------------------------------------------------------- switch car

/** All registered cars (any brand). Only reachable when there is more than one. */
internal class CarListScreen(ctx: CarContext, ui: CarUi) : ListScreen(ctx, ui) {
    private var entries: List<Garage.Entry> = emptyList()

    override fun refresh() { changed() }

    override fun build(): ListModel {
        val app = CarHub.app(carContext)
        val cur = Garage.current(app)
        entries = Garage.entries(app)
        val rows = entries.mapIndexed { i, e ->
            ListModel.Row("e$i", Garage.name(app, e).ifBlank { e.brand.display }, e.brand.display + if (e == cur) " · " + tr("Selected") else "",
                R.drawable.ic_car_switch, lit = e == cur, selected = e == cur)
        }
        return ListModel(tr("Switch car"), null, rows = rows, empty = "")
    }

    override fun picked(key: String) {
        val e = entries.getOrNull(key.removePrefix("e").toIntOrNull() ?: return) ?: return
        if (e != Garage.current(CarHub.app(carContext))) CarHub.switchCar(carContext, e)
        goBack()
    }
}
