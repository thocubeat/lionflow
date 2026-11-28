package no.lionflow.app.car

import android.graphics.Canvas
import android.graphics.Paint
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.door.GarageDoor
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.HomeModel
import no.lionflow.app.car.ui.HomePaint
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Garage
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.tr
import kotlin.math.roundToInt

/** "1 h 20 min" / "35 min", in the app's language. */
internal fun carDuration(minutes: Double): String {
    val t = minutes.roundToInt().coerceAtLeast(0)
    return if (t >= 60) tr("${t / 60} h ${t % 60} min") else "$t min"
}

/** What goes under a screen's title: what is happening right now, else how fresh the car's numbers are. */
internal fun carStatusLine(): Pair<String, Int> {
    CarHub.message.value?.let { return tr(it) to Tone.TEXT }
    if (CarHub.loading.value && CarHub.status.value != null) return tr("Updating…") to Tone.MUTED
    return tr(CarHub.updatedLine()) to Tone.MUTED
}

/** "NRK" in a ring, for the button that opens NRK TV. */
internal fun nrkBadge(): CarIcon {
    val n = 96
    val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = n * 0.07f
    c.drawCircle(n / 2f, n / 2f, n / 2f - p.strokeWidth, p)
    p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
    p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD); p.textSize = n * 0.30f
    c.drawText("NRK", n / 2f, n / 2f + p.textSize * 0.36f, p)
    return CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
}

/**
 * The start screen in the car: the car's name, a battery gauge with what the car is doing, and big tiles for the
 * rest. Dark or light (the sun / moon button switches; without a choice it follows the car).
 */
internal class HomeScreen(ctx: CarContext, ui: CarUi) : DrawnScreen(ctx, ui) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(CarHub.status, CarHub.message, CarHub.loading, CarHub.trips) { _, _, _, _ -> Unit }.collect { redraw() } }
                launch { SavedRoutes.changes.collect { redraw() } }
                launch { GarageDoor.config.collect { invalidate() } }
                launch { while (true) { delay(10_000L); CarHub.refresh(carContext, force = true); CarHub.askCar(carContext) } }   // fresh numbers while it shows
                launch { CarHub.live.collect { redraw() } }   // battery and range straight from the car
                // The speed is read again every 5 seconds (that also keeps "reported … ago" right).
                launch { while (true) { ui.guard.update(); redraw(); delay(5_000L) } }
            }
        }
    }

    /** Whether there is more than one car to switch between (looked up when the screen comes on show). */
    private var several = false

    override fun shown() {
        several = Garage.hasSeveral(CarHub.app(carContext))
        ui.guard.askPermissions()
        CarHub.refresh(carContext)
        CarHub.askCar(carContext)
        CarHub.loadTrips(carContext)
        // Whether the NRK button belongs here is looked up in the background the first time.
        Where.warm(carContext) { invalidate() }
    }

    private fun openVideo(site: VideoSite) {
        ui.guard.update()
        if (ui.guard.parked.value) screenManager.push(YouTubeScreen(carContext, ui, site))
        else CarToast.makeText(carContext, tr(ui.guard.reason), CarToast.LENGTH_LONG).show()
    }

    override val themeButton: Boolean get() = false

    override fun buttons(): List<Btn> {
        val pal = CarTheme.refresh(carContext)
        val out = ArrayList<Btn>()
        // Sun in the dark look (switch to light), moon in the light look.
        out += Btn(if (pal.dark) R.drawable.ic_car_sun else R.drawable.ic_car_moon) { CarTheme.toggle(carContext); invalidate(); redraw() }
        val refresh = Btn(R.drawable.ic_car_refresh) { CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true) }
        out += refresh
        // The garage door, just before YouTube (set up on the phone: Settings → Integrations).
        if (GarageDoor.configured && "garage" !in no.lionflow.app.data.HomeLayout.hidden(CarHub.app(carContext).settings.value.carHidden)) {
            out += Btn(R.drawable.ic_car_garage) { screenManager.push(GarageScreen(carContext, ui)) }
        }
        out += Btn(R.drawable.ic_car_play, tint = CarColor.RED) { openVideo(VideoSite.YouTube) }
        if (Where.inNorway(carContext)) out += Btn(custom = nrkBadge()) { openVideo(VideoSite.NrkTv) }
        // Android Auto shows at most four. This screen updates itself every 10 seconds, so refresh is the one to go.
        if (out.size > 4) out.remove(refresh)
        return out
    }

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) = HomePaint.draw(c, k, model(), pressed, hits)

    override fun tapped(key: String) {
        when (key) {
            "plan" -> screenManager.push(TripSearchScreen(carContext, ui))
            "chargers" -> screenManager.push(ChargerListScreen(carContext, ui))
            "saved" -> screenManager.push(SavedListScreen(carContext, ui))
            "trips" -> screenManager.push(TripListScreen(carContext, ui))
            "info", HomePaint.GAUGE -> screenManager.push(CarInfoScreen(carContext, ui))
            "switch" -> screenManager.push(CarListScreen(carContext, ui))
        }
    }

    private fun model(): HomeModel {
        val s = CarHub.app(carContext).settings.value
        val st = CarHub.status.value
        val (status, tone) = carStatusLine()
        val fuelOnly = st?.energyType == "Fuel"

        // Which tiles and lines are shown is chosen on the phone (Settings → Home screens → Android Auto).
        val hidden = no.lionflow.app.data.HomeLayout.hidden(s.carHidden)
        val tiles = ArrayList<HomeModel.Tile>()
        // Planning is about the battery: not for cars that only burn fuel.
        if (!fuelOnly && "plan" !in hidden) tiles += HomeModel.Tile("plan", tr("Plan a trip"), tr("Battery % on arrival"), R.drawable.ic_car_search)
        if ("chargers" !in hidden) tiles += HomeModel.Tile("chargers", tr(if (fuelOnly) "Fuel stations" else if (st?.energyType == "Hybrid") "Chargers & fuel" else "Chargers"), tr("Nearest first"),
            if (fuelOnly) R.drawable.ic_car_fuel else R.drawable.ic_car_charger)
        val saved = if (SavedRoutes.fromPlay(carContext) && "saved" !in hidden) SavedRoutes.all(carContext) else emptyList()
        if (saved.isNotEmpty()) tiles += HomeModel.Tile("saved", tr("Saved routes"), if (saved.size == 1) saved[0].name else tr("${saved.size} routes"), R.drawable.ic_car_route)
        val trips = CarHub.trips.value
        val weekKm = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }?.sumOf { it.distanceKm }
        if ("trips" !in hidden) tiles += HomeModel.Tile("trips", tr("Previous trips"), if (weekKm == null) tr("Loading…") else tr("7 days: ${fmtDistance(weekKm, s)}"), R.drawable.ic_car_history)
        if ("info" !in hidden) tiles += HomeModel.Tile("info", tr("Car Info"), st?.odometerKm?.let { fmtDistance(it, s) } ?: tr("Details from the car"), R.drawable.ic_car_gauge)
        if (several) tiles += HomeModel.Tile("switch", tr("Switch car"), s.carName, R.drawable.ic_car_switch)

        return HomeModel(s.carName, status, tone, st?.let { gauge(it, s) }, tr("Getting the car's status…"), tiles)
    }

    private fun gauge(st: VehicleStatus, s: no.lionflow.app.data.AppSettings): HomeModel.Gauge {
        val fuelOnly = st.energyType == "Fuel"
        // The car's own reading (live, while Android Auto runs) before the one from the brand's server.
        val live = CarHub.live.value
        val pct = if (fuelOnly) st.fuelPct ?: st.batteryPct else live.pct ?: st.batteryPct
        val range = if (fuelOnly) st.fuelRangeKm ?: st.rangeKm else live.rangeKm ?: st.rangeKm
        val hidden = no.lionflow.app.data.HomeLayout.hidden(s.carHidden)
        val lines = ArrayList<HomeModel.Line>()
        // How fast the car is going right now, from the car itself (else the phone's GPS). First, so it is never cut off.
        if ("speed" !in hidden) (ui.guard.speed.value ?: 0f.takeIf { ui.guard.parked.value })?.let { v ->
            val miles = s.distanceUnit == "mi"
            val shown = (v * (if (miles) 2.23694f else 3.6f)).roundToInt()
            lines += HomeModel.Line(R.drawable.ic_car_gauge, "$shown ${if (miles) "mph" else "km/h"}", Tone.TEXT)
        }
        val doors = HomeModel.Line(R.drawable.ic_car_lock, CarHub.doorsLine(st), if (st.openDoors.isNotEmpty() || st.locked == false) Tone.WARN else Tone.TEXT)
        val showDoors = "doors" !in hidden
        val showCharging = "charging" !in hidden
        if (fuelOnly) { if (showDoors) lines += doors }
        else when {
            st.isCharging -> {
                if (showCharging) {
                    lines += HomeModel.Line(R.drawable.ic_car_bolt, tr("Charging") + (ChargePower.label(st)?.let { " · $it" } ?: ""), Tone.GOOD)
                    st.remainingMinutes?.takeIf { it > 0 }?.let { lines += HomeModel.Line(R.drawable.ic_car_history, tr("Full in") + " " + carDuration(it.toDouble()), Tone.GOOD) }
                }
                if (showDoors) lines += doors
            }
            else -> {
                if (showDoors) lines += doors
                if (showCharging) lines += HomeModel.Line(R.drawable.ic_car_bolt, tr(when { st.chargingStatus == "Finished" -> "Charging complete"; st.plugged -> "Plugged in"; else -> "Not plugged in" }),
                    if (st.plugged || st.chargingStatus == "Finished") Tone.GOOD else Tone.TEXT)
            }
        }
        // A plug-in hybrid: the tank in one short line (its range is in Car Info).
        if (!fuelOnly && st.fuelPct != null) lines += HomeModel.Line(R.drawable.ic_car_fuel, tr("Fuel") + " ${st.fuelPct.roundToInt()} %", Tone.TEXT)
        if ("outside" !in hidden) st.outsideTempC?.let { lines += HomeModel.Line(R.drawable.ic_car_climate, fmtTemp(it, s) + " " + tr("outside"), Tone.TEXT) }
        if (st.preconditioningOn) lines += HomeModel.Line(R.drawable.ic_car_climate, tr("Pre-heating"), Tone.WARN)
        if ("odometer" !in hidden) st.odometerKm?.let { lines += HomeModel.Line(R.drawable.ic_car_gauge, fmtDistance(it, s), Tone.TEXT) }
        if ("service" !in hidden) st.kmToService?.let { lines += HomeModel.Line(R.drawable.ic_car_history, tr("Next service") + " · " + fmtDistance(it, s), Tone.TEXT) }
        return HomeModel.Gauge(pct, range?.let { fmtDistance(it, s) } ?: "—", lines)
    }
}
