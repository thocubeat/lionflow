package no.lionflow.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.car.app.CarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.lionflow.app.R
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.MapView
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.car.ui.TripModel
import no.lionflow.app.car.ui.TripPaint
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.Planner
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import kotlin.math.roundToInt

/**
 * A trip on the car screen: a card with what is left and the battery now and at the next stop, the route on a map
 * with the charging stops and the car, and a line showing how far along the car is.
 *
 * It shows either a trip saved on the phone, or one planned here, to a place picked in the car: then the screen
 * first shows that it is planning, and the trip when the plan is there. The car is asked for a fresh status every
 * 10 seconds while this is on show.
 */
internal class TripScreen private constructor(ctx: CarContext, ui: CarUi, private val savedId: Long?, private val dest: Place?) : DrawnScreen(ctx, ui) {
    companion object {
        /** A trip saved on the phone. */
        fun saved(ctx: CarContext, ui: CarUi, id: Long) = TripScreen(ctx, ui, id, null)
        /** A trip to [dest], planned now from where the car is, with its battery now. */
        fun plan(ctx: CarContext, ui: CarUi, dest: Place) = TripScreen(ctx, ui, null, dest)
    }

    private val app = CarHub.app(carContext)
    private val planner: Planner? = if (dest != null) Planner(lifecycleScope, app, { CarHub.status.value }, { CarHub.trips.value.orEmpty() }, { null }) else null

    /** The planned trip in the form the car screen shows, and the plan it was made from. */
    private var planned: SavedRoutes.Saved? = null
    private var plannedFrom: Any? = null
    private var waitingForCar = false
    private var phase = 0f

    init {
        if (planner != null && dest != null) {
            planner.to = dest
            lifecycleScope.launch {
                // The trip starts where the car is: wait for the car's status if it hasn't come yet.
                if (CarHub.status.value == null) {
                    waitingForCar = true
                    CarHub.refresh(carContext)
                    withTimeoutOrNull(20_000L) { CarHub.status.first { it != null } }
                    waitingForCar = false
                }
                planner.go()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(CarHub.status, CarHub.message, CarHub.loading) { _, _, _ -> Unit }.collect { redraw() } }
                launch { while (true) { CarHub.refresh(carContext, force = true); CarHub.askCar(carContext); delay(10_000L) } }
                // The phone's GPS and the car's speed: the car's place on the route and the speed follow live.
                launch { combine(ui.guard.fix, ui.guard.speed, CarHub.live) { _, _, _ -> Unit }.collect { redraw() } }
                launch { while (true) { delay(1_000L); ui.guard.update() } }
                // A saved trip deleted on the phone: nothing left to show here.
                if (savedId != null) launch { SavedRoutes.changes.drop(1).collect { if (SavedRoutes.all(carContext).none { it.id == savedId }) goBack() } }
                // The planner's state is watched here; while it works, a mark goes round.
                if (planner != null) launch {
                    var seen: Any? = null
                    while (true) {
                        val key = Triple(planner.busy, planner.plan, planner.error)
                        val waiting = planner.plan == null && planner.error == null
                        if (waiting) phase = (phase + 0.07f) % 1f
                        if (key != seen || waiting) { seen = key; redraw() }
                        delay(if (waiting) 90L else 300L)
                    }
                }
            }
        }
    }

    override fun shown() { if (mapKey != null && mapPic == null && mapJob == null) startMap() }

    override fun buttons() = listOf(Btn(R.drawable.ic_car_refresh) {
        if (planner != null && planner.plan == null && planner.error != null) planner.go()
        else {
            savedId?.let { SavedRoutes.resetProgress(carContext, it) }
            CarHub.refresh(carContext, force = true)
        }
    })

    override fun tapped(key: String) { if (key == TripPaint.RETRY) planner?.go() }

    private fun clock(ms: Long): String = java.text.SimpleDateFormat(TimeFmt.pattern("HH:mm"), L10n.locale).format(java.util.Date(ms))

    /** The trip to show, when there is one. */
    private fun route(): SavedRoutes.Saved? {
        if (savedId != null) return SavedRoutes.all(carContext).firstOrNull { it.id == savedId }
        val p = planner ?: return null
        val plan = p.plan ?: return null
        if (plan !== plannedFrom || planned == null) { planned = p.preview(); plannedFrom = plan }
        return planned
    }

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) {
        val r = route()
        val title = dest?.name?.ifBlank { null } ?: r?.name ?: tr("Trip")
        val (status, tone) = carStatusLine()
        val m: TripModel = when {
            r != null && r.lat.size >= 2 -> ready(r, title, status, tone, k)
            savedId != null -> TripModel(tr("Saved routes"), "", Tone.MUTED, TripModel.FAILED, tr("This route was deleted."))
            planner?.error != null -> {
                val e = planner.error ?: ""
                TripModel(title, "", Tone.MUTED, TripModel.FAILED, tr(if (e == "Choose where the trip starts") "The car hasn't shared its position" else e), tr("Try again"))
            }
            else -> TripModel(title, "", Tone.MUTED, TripModel.WAIT, tr(if (waitingForCar) "Getting the car's status…" else planner?.busy ?: "Planning…"), phase = phase)
        }
        TripPaint.draw(c, k, m, pressed, hits)
    }

    private fun ready(r: SavedRoutes.Saved, title: String, status: String, tone: Int, k: Kit): TripModel {
        val s = app.settings.value
        val st = CarHub.status.value
        // Where the car is: the phone's GPS when it has a fresh fix (it rides along), else where the car last reported.
        val fix = ui.guard.fix.value?.takeIf { System.currentTimeMillis() - it.time < 30_000L }
        val lat = fix?.latitude ?: st?.lat; val lon = fix?.longitude ?: st?.lon
        val nowPct = CarHub.live.value.pct ?: st?.batteryPct
        val live = SavedRoutes.live(carContext, r, lat, lon, nowPct)
        val next = live.nextStop
        val offRoute = lat != null && live.offRouteKm >= 3.0
        val plan = planner?.plan

        val note: String?; val noteTone: Int
        when {
            plan != null && !plan.ok -> {
                note = if ((planner?.startNow() ?: 100) <= (planner?.minPct ?: 0)) tr("The battery is at or below your minimum")
                    else tr("No fast charger within reach after {0}").replace("{0}", fmtDistance(plan.failKm, s))
                noteTone = Tone.BAD
            }
            offRoute -> { note = tr("The car is {0} from the route").replace("{0}", fmtDistance(live.offRouteKm, s)); noteTone = Tone.WARN }
            else -> { note = null; noteTone = Tone.MUTED }
        }

        val stops = r.stops.map { sp ->
            val passed = sp.idx <= live.at
            val isNext = sp === next
            TripModel.Stop(sp.lat, sp.lon, sp.km, passed, isNext, if (passed) null else if (isNext) live.pctAtNext else sp.arrivePct.takeIf { it > 0 }, about = !isNext)
        }
        val left = if (live.stopsLeft == 1) tr("1 stop left") else tr("{0} stops left").replace("{0}", "${live.stopsLeft}")

        // The map behind the route: asked for once per route, size and look, and drawn when it has come.
        val layout = TripPaint.layout(k)
        val view = TripPaint.view(layout, r.lat, r.lon)
        val key = "${r.id}:${layout.map.width().toInt()}x${layout.map.height().toInt()}:${k.pal.dark}"
        if (key != mapKey) {
            mapKey = key; mapPic = null; mapTries = 0
            mapView = view; mapDark = k.pal.dark
            main.post { startMap() }
        }

        return TripModel(
            title, status, tone, TripModel.READY,
            big = fmtDistance(live.leftKm, s), bigUnit = tr("left"),
            eta = live.minLeft?.let { tr("≈ {0} · arrive {1}").replace("{0}", carDuration(it)).replace("{1}", clock(System.currentTimeMillis() + (it * 60_000).toLong())) } ?: "",
            nowPct = nowPct, nowLabel = tr("Now"),
            thenPct = live.pctAtNext, thenLabel = tr(if (next != null) "Next stop" else "Arrival"),
            stopTitle = next?.name ?: tr("No charging stops left"),
            stopSub = if (next != null) tr("In {0}").replace("{0}", fmtDistance(live.toNextKm ?: 0.0, s)) + " · " + left else tr("Straight to the destination"),
            stopLive = next != null,
            note = note, noteTone = noteTone,
            lat = r.lat, lon = r.lon, km = r.km, at = live.at, showCar = lat != null && lon != null && !offRoute,
            speed = ui.guard.speed.value?.let { v -> if (s.distanceUnit == "mi") (v * 2.23694f).roundToInt() else (v * 3.6f).roundToInt() },
            speedUnit = if (s.distanceUnit == "mi") "mph" else "km/h",
            stops = stops, endPct = live.pctAtEnd,
            // A round trip ends where it started: no name at the end of the line then.
            destName = if (r.name.trimEnd().endsWith("⇄")) "" else r.name.substringAfterLast("→ ").trim(), totalLabel = fmtDistance(r.totalKm, s),
            map = mapPic, credit = CarMap.CREDIT,
        )
    }

    // ---------------- the map picture ----------------

    /** The map behind the route, made once per route, size and look, on a background thread. */
    private var mapPic: Bitmap? = null
    private var mapKey: String? = null
    private var mapView: MapView? = null
    private var mapDark = true
    private var mapTries = 0
    private var mapJob: kotlinx.coroutines.Job? = null

    private fun startMap() {
        val key = mapKey ?: return
        val v = mapView ?: return
        if (!showing || mapPic != null) return
        mapJob?.cancel()
        mapTries++
        val dark = mapDark
        val app = carContext.applicationContext
        mapJob = lifecycleScope.launch {
            val pic = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CarMap.picture(app, v, dark) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { ui.log("map picture", e); null }
            mapJob = null
            if (mapKey != key) return@launch
            if (pic != null) { mapPic = pic; redraw() }
            else if (mapTries < 3) { delay(8_000L); if (mapKey == key && mapPic == null && showing) startMap() }
        }
    }
}
