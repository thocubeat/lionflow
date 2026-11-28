package no.lionflow.app.car

import no.lionflow.app.ui.l10n.tr

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
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
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
 * LionFlow in Android Auto. The screens are drawn by LionFlow itself (see [DrawnScreen]): a start screen with the
 * battery and tiles, the car's details, trips planned and saved, chargers, previous trips. With "Plain lists in the
 * car" on in Settings (for cars without a touch screen) the plain tiles and lists further down in this file are used.
 */
class LionFlowCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Only Android Auto / Google's car hosts may connect (or anything, in a debug build).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(): Session = MainSession()
}

// Icons are sent to the car as bitmaps, not as resource ids: Android Auto caches icons by resource number, and after an
// update (when the numbers shift) it showed the wrong symbols.
private val iconBitmaps = HashMap<Int, android.graphics.Bitmap>()

internal fun CarContext.icon(res: Int, tint: CarColor? = null): CarIcon {
    val bmp = synchronized(iconBitmaps) {
        iconBitmaps.getOrPut(res) {
            val d = androidx.core.content.ContextCompat.getDrawable(this, res)!!.mutate()
            val size = 96
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(android.graphics.Canvas(b))
            b
        }
    }
    return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).apply { tint?.let { setTint(it) } }.build()
}

private fun CarContext.listLimit(): Int = try {
    getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
        .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (_: Exception) { 6 }

/**
 * What a "navigate to …" request points at: a place with coordinates, or words to search for.
 * Android Auto sends these as geo: addresses — "geo:59.91,10.75", "geo:0,0?q=59.91,10.75(Oslo)" or "geo:0,0?q=Storgata 1, Oslo".
 */
internal fun parseNavigate(uri: android.net.Uri?): Pair<no.lionflow.app.plan.Place?, String?> {
    val ssp = uri?.encodedSchemeSpecificPart ?: return null to null
    val pair = Regex("^\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
    fun place(lat: Double, lon: Double, label: String): no.lionflow.app.plan.Place? =
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6)) null
        else no.lionflow.app.plan.Place(label.ifBlank { String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon) }, "", lat, lon)
    val q = ssp.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.substring(2)
        ?.let { android.net.Uri.decode(it.replace("+", " ")) }?.trim()
    if (!q.isNullOrEmpty()) {
        val m = pair.find(q)
        if (m != null) {
            val label = if (q.contains('(')) q.substringAfter('(').substringBeforeLast(')').trim() else ""
            place(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), label)?.let { return it to null }
            if (label.isNotEmpty()) return null to label
        } else return null to q
    }
    val m = pair.find(android.net.Uri.decode(ssp.substringBefore('?'))) ?: return null to null
    return place(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), "") to null
}

class MainSession : Session() {
    private var ui: CarUi? = null

    /**
     * A "navigate to …" request (from the assistant, or from another app in the car) comes to LionFlow because it is
     * listed as a navigation app. LionFlow doesn't navigate: it plans the trip there, or searches for the words.
     */
    private fun navigate(intent: Intent) {
        val ui = ui ?: return
        ui.safe("navigate request") {
            val (place, words) = parseNavigate(intent.data)
            val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
            when {
                place != null -> { sm.popToRoot(); sm.push(TripScreen.plan(carContext, ui, place)) }
                !words.isNullOrBlank() -> { sm.popToRoot(); sm.push(TripSearchScreen(carContext, ui, words)) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == CarContext.ACTION_NAVIGATE) navigate(intent)
    }

    /** Day or night in the car changed: the drawn screens follow (unless a look was chosen with the sun / moon button). */
    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        val ui = ui ?: return
        ui.surface.invalidate()
        try { carContext.getCarService(androidx.car.app.ScreenManager::class.java).top.invalidate() } catch (_: Exception) { }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val ui = CarUi(carContext)
        this.ui = ui
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onCreate(owner: androidx.lifecycle.LifecycleOwner) { ui.guard.start(); ui.surface.start() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                ui.guard.stop(); ui.surface.close()
                owner.lifecycle.removeObserver(this)   // a session that is started again gets a new set
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarHub.notices.collect { m -> try { CarToast.makeText(carContext, tr(m), CarToast.LENGTH_LONG).show() } catch (_: Exception) { } }
            }
        }
        no.lionflow.app.plan.SavedRoutes.preload(carContext)
        CarHub.refresh(carContext)
        CarHub.loadTrips(carContext)
        if (intent.action == CarContext.ACTION_NAVIGATE) android.os.Handler(android.os.Looper.getMainLooper()).post { navigate(intent) }
        // LionFlow draws its own screens, which are tapped on. With "Plain lists in the car" on (for a car without a
        // touch screen), and until someone has signed in, Android Auto's own tiles and lists are used.
        return if (!ui.plain && CarHub.app(carContext).api.isSignedIn) HomeScreen(carContext, ui) else OverviewScreen(carContext, ui)
    }
}

/** Base screen that redraws whenever the shared car state changes. */
abstract class LiveScreen(ctx: CarContext) : Screen(ctx) {
    abstract fun build(): Template

    /** Never let a bad template take the whole app down: show what went wrong instead. */
    final override fun onGetTemplate(): Template = try { build() } catch (e: Exception) {
        android.util.Log.e("LionFlowCar", "template failed", e)
        try { CarHub.app(carContext).remote.addLog("Android Auto: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Exception) {}
        MessageTemplate.Builder(tr("Something went wrong showing this screen.") + "\n" + (e.message ?: e.javaClass.simpleName).take(200))
            .setTitle(tr("LionFlow")).setHeaderAction(Action.APP_ICON).build()
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(CarHub.status, CarHub.message, CarHub.busy, CarHub.chargers, CarHub.fuel) { _, _, _, _, _ -> Unit }
                    .collect { invalidate() }
            }
        }
    }
}

// ---------------------------------------------------------------- overview

internal class OverviewScreen(ctx: CarContext, private val ui: CarUi) : LiveScreen(ctx) {
    private val guard get() = ui.guard
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { invalidate() } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { guard.parked.collect { invalidate() } }
        }
    }

    override fun build(): Template {
        val app = CarHub.app(carContext)
        if (!app.api.isSignedIn) {
            return MessageTemplate.Builder(tr("Open LionFlow on your phone and sign in with your Peugeot account."))
                .setTitle(tr("LionFlow")).setHeaderAction(Action.APP_ICON).build()
        }
        val s = app.settings.value
        val st = CarHub.status.value
        // The strip holds at most two buttons, icons only. With YouTube and NRK TV both there, updating is on the doors tile.
        val nrk = Where.inNorway(carContext)
        val strip = ActionStrip.Builder()
        if (!nrk) strip.addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener { refreshAll() }.build())
        strip.addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_play, CarColor.RED)).setOnClickListener {
            guard.update()
            if (guard.parked.value) screenManager.push(YouTubeScreen(carContext, ui))
            else CarToast.makeText(carContext, tr(guard.reason), CarToast.LENGTH_LONG).show()
        }.build())
        if (nrk) strip.addAction(Action.Builder().setIcon(nrkBadge()).setOnClickListener {
            guard.update()
            if (guard.parked.value) screenManager.push(YouTubeScreen(carContext, ui, VideoSite.NrkTv))
            else CarToast.makeText(carContext, tr(guard.reason), CarToast.LENGTH_LONG).show()
        }.build())
        val b = GridTemplate.Builder().setTitle(tr(CarHub.message.value ?: s.carName)).setHeaderAction(Action.APP_ICON).setActionStrip(strip.build())
        if (st == null) return b.setLoading(true).build()

        val lion = CarColor.createCustom(0xFFC8F560.toInt(), 0xFFC8F560.toInt())   // LionFlow green
        // Each tile has a priority: when the car only allows a few tiles, the highest numbers are dropped first.
        class T(val item: GridItem, val prio: Int)
        val items = mutableListOf<T>()
        fun tile(prio: Int, title: String, text: String?, icon: Int, tint: CarColor? = null, onClick: (() -> Unit)? = null) {
            items += T(GridItem.Builder().setTitle(tr(title).ifBlank { "—" })
                .apply { text?.takeIf { it.isNotBlank() }?.let { setText(tr(it)) } }
                .setImage(carContext.icon(icon, tint), GridItem.IMAGE_TYPE_ICON)
                .apply { onClick?.let { setOnClickListener(it) } }
                .build(), prio)
        }

        // Order: doors, odometer, service, trips (4th), chargers, YouTube (last).
        // Trips and chargers are green, YouTube red. If the car shows fewer tiles, the status tiles go first.
        tile(4, CarHub.doorsLine(st), CarHub.updatedLine(), R.drawable.ic_car_lock) { refreshAll() }
        tile(5, st.odometerKm?.let { fmtDistance(it, s) } ?: "Odometer", "Total driven", R.drawable.ic_car_gauge)
        tile(6, "Service", serviceLine(st, s), R.drawable.ic_car_refresh)
        if (no.lionflow.app.model.Garage.hasSeveral(app)) tile(3, "Switch car", s.carName, R.drawable.ic_car_switch) { screenManager.push(CarsScreen(carContext)) }
        val trips = CarHub.trips.value
        val week = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }
        val km = week?.sumOf { it.distanceKm } ?: 0.0
        tile(1, if (trips == null) "Trips" else "7 days: ${fmtDistance(km, s)}", if (trips == null) "Loading…" else "${week?.size ?: 0} trips",
            R.drawable.ic_car_history, lion) { screenManager.push(TripsScreen(carContext)) }
        if (no.lionflow.app.door.GarageDoor.configured && "garage" !in no.lionflow.app.data.HomeLayout.hidden(s.carHidden)) {
            val door = no.lionflow.app.door.GarageDoor
            tile(2, "Garage door", door.label(door.state.value).ifEmpty { "Open or close" }, R.drawable.ic_car_garage) { screenManager.push(GarageListScreen(carContext)) }
        }
        if (st.energyType != "Fuel") tile(2, "Plan a trip", "Battery % on arrival", R.drawable.ic_car_search, lion) { screenManager.push(TripSearchScreen(carContext, ui)) }
        if (no.lionflow.app.plan.SavedRoutes.fromPlay(carContext)) {
            val saved = no.lionflow.app.plan.SavedRoutes.all(carContext)
            if (saved.isNotEmpty()) tile(3, "Saved routes", if (saved.size == 1) saved[0].name else "${saved.size} routes", R.drawable.ic_car_route, lion) {
                screenManager.push(SavedRoutesScreen(carContext, ui))
            }
        }
        val fuelOnly = st.energyType == "Fuel"
        tile(2, if (fuelOnly) "Fuel stations" else if (st.energyType == "Hybrid") "Chargers & fuel" else "Chargers", "Nearest first",
            if (fuelOnly) R.drawable.ic_car_fuel else R.drawable.ic_car_charger, lion) { screenManager.push(ChargersScreen(carContext, ui)) }
        val max = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } catch (_: Exception) { 6 }.coerceAtLeast(4)
        while (items.size > max) items.removeAt(items.indexOfLast { it.prio == items.maxOf { t -> t.prio } })
        val list = ItemList.Builder()
        items.forEach { list.addItem(it.item) }
        return b.setSingleList(list.build()).build()
    }

    private fun refreshAll() { CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true); CarHub.say("Updating…") }

    private fun serviceLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String {
        val parts = mutableListOf<String>()
        st.kmToService?.let { parts += "service in ${fmtDistance(it, s)}" }
        st.daysToService?.let { d -> parts += if (d >= 60) "${(d / 30).toInt()} months" else "${d.toInt()} days" }
        return if (parts.isEmpty()) "Next service unknown" else "Next " + parts.joinToString(" or ")
    }
}

// ---------------------------------------------------------------- chargers (list only)

internal class ChargersScreen(ctx: CarContext, private val ui: CarUi) : LiveScreen(ctx) {
    init {
        CarHub.status.value?.let { st ->
            st.lat?.let { la -> st.lon?.let { lo ->
                if (st.energyType != "Fuel") CarHub.loadChargers(ctx, la, lo)
                if (st.energyType != "Electric") CarHub.loadFuel(ctx, la, lo)
            } }
        }
    }

    /** Plans the trip to a charger: how long it takes and the battery left on arrival. */
    private fun planTo(ch: no.lionflow.app.api.Charger) = screenManager.push(TripScreen.plan(carContext, ui, no.lionflow.app.plan.Place(ch.name, ch.address, ch.lat, ch.lon)))

    override fun build(): Template {
        val s = CarHub.app(carContext).settings.value
        val type = CarHub.status.value?.energyType ?: "Electric"
        val chargers = if (type == "Fuel") emptyList() else CarHub.chargers.value
        val fuel = if (type == "Electric") emptyList() else CarHub.fuel.value
        val title = when (type) { "Fuel" -> "Fuel stations near the car"; "Hybrid" -> "Chargers and fuel near the car"; else -> "Chargers near the car" }
        val b = ListTemplate.Builder().setTitle(tr(title)).setHeaderAction(Action.BACK)
        if (chargers.isEmpty() && fuel.isEmpty()) return b.setLoading(true).build()

        // Plug-in hybrids get both kinds: half the rows each (nearest first), then merged by distance.
        val limit = carContext.listLimit()
        val shown = when {
            fuel.isEmpty() -> chargers.take(limit).map { it to false }
            chargers.isEmpty() -> fuel.take(limit).map { it to true }
            else -> (chargers.take((limit + 1) / 2).map { it to false } + fuel.take(limit / 2).map { it to true })
                .sortedBy { it.first.distanceM ?: Int.MAX_VALUE }
        }
        val list = ItemList.Builder()
        shown.forEach { (ch, isFuel) ->
            val bits = mutableListOf<String>()
            ch.distanceM?.let { m -> bits += if (s.distanceUnit != "mi" && m < 1000) "$m m" else if (s.distanceUnit != "mi" && m < 10_000) String.format(java.util.Locale.US, "%.1f km", m / 1000.0) else fmtDistance(m / 1000.0, s) }
            if (!isFuel && ch.maxKw > 0) bits += "${ch.maxKw.toInt()} kW"
            if (!isFuel && ch.points > 0) bits += tr("${ch.points} points")
            list.addItem(Row.Builder().setTitle(ch.name.ifBlank { "—" }).addText(bits.joinToString(" · ").ifBlank { ch.address.ifBlank { "—" } })
                .setImage(carContext.icon(if (isFuel) R.drawable.ic_car_fuel else R.drawable.ic_car_charger, if (!isFuel && ch.maxKw >= 50) CarColor.GREEN else null))
                .apply { if (!isFuel) setOnClickListener { planTo(ch) } }.build())
        }
        return b.setSingleList(list.build()).build()
    }
}

// ---------------------------------------------------------------- switch car

/** All registered cars (any brand). Only reachable when there is more than one. */
class CarsScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val app = CarHub.app(carContext)
        val cur = no.lionflow.app.model.Garage.current(app)
        val list = ItemList.Builder()
        no.lionflow.app.model.Garage.entries(app).take(6).forEach { e ->
            list.addItem(Row.Builder()
                .setTitle(no.lionflow.app.model.Garage.name(app, e).ifBlank { e.brand.display })
                .addText(e.brand.display + if (e == cur) " · " + tr("Selected") else "")
                .setImage(carContext.icon(R.drawable.ic_car_switch))
                .setOnClickListener {
                    if (e != cur) CarHub.switchCar(carContext, e)
                    screenManager.pop()
                }.build())
        }
        return ListTemplate.Builder().setTitle(tr("Switch car")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

// ---------------------------------------------------------------- saved routes (list only)

internal class SavedRoutesScreen(ctx: CarContext, private val ui: CarUi) : Screen(ctx) {
    init {
        // Trips saved or deleted on the phone show up here at once.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { no.lionflow.app.plan.SavedRoutes.changes.collect { invalidate() } } }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val saved = no.lionflow.app.plan.SavedRoutes.all(carContext)
        if (saved.isEmpty()) return MessageTemplate.Builder(tr("Plan a trip on your phone and tap Save trip.")).setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).build()
        val list = ItemList.Builder()
        for (r in saved.take(carContext.listLimit())) {
            val stops = when (r.stops.size) { 0 -> "no charging stops"; 1 -> "1 charging stop"; else -> "${r.stops.size} charging stops" }
            list.addItem(Row.Builder().setTitle(r.name.ifBlank { "—" }).addText(fmtDistance(r.totalKm, s) + " · " + tr(stops))
                .setImage(carContext.icon(R.drawable.ic_car_route))
                .setOnClickListener { screenManager.push(TripScreen.saved(carContext, ui, r.id)) }.build())
        }
        return ListTemplate.Builder().setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
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
        val b = ListTemplate.Builder().setTitle(tr("Previous trips")).setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle(tr("Refresh"))
                .setOnClickListener { CarHub.loadTrips(carContext, force = true) }.build()).build())
        if (trips == null) return b.setLoading(true).build()
        val list = ItemList.Builder()
        if (trips.isEmpty()) {
            list.setNoItemsMessage(tr("No trips in the last 30 days"))
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
        val mildHybrid = CarHub.status.value?.isMildHybrid == true
        val kwh = if (mildHybrid) 0.0 else week.mapNotNull { it.kwh }.sum()
        val avg = if (km > 1 && kwh > 0) kwh / km * 100 else null
        list.addItem(Row.Builder()
            .setTitle(tr("Last 7 days: ${fmtDistance(km, s)} · ${week.size} trips"))
            .addText(tr(avg?.let { consumption(it, s) } ?: "Consumption not reported"))
            .setImage(carContext.icon(R.drawable.ic_car_history)).build())

        val day = java.text.SimpleDateFormat("EEE d MMM", no.lionflow.app.ui.l10n.L10n.locale)
        val time = java.text.SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
        val today = day.format(java.util.Date())
        trips.take((max - 1).coerceAtLeast(1)).forEach { t ->
            val start = t.startMillis?.let { java.util.Date(it) }
            val whenTxt = start?.let { d -> (if (day.format(d) == today) "Today" else day.format(d)) + " " + time.format(d) } ?: "Trip"
            val bits = mutableListOf<String>()
            t.durationSec?.takeIf { it > 0 }?.let { sec -> val m = (sec / 60).toInt(); bits += if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
            if (mildHybrid) t.avgLPer100?.let { bits += String.format(java.util.Locale.US, "%.1f l/100 km", it) }
            else t.avgKwhPer100?.let { bits += consumption(it, s) }
            t.avgSpeedKmh?.takeIf { it > 0 }?.let { v -> bits += if (s.distanceUnit == "mi") "${Math.round(v * 0.621371)} mph avg" else "${Math.round(v)} km/h avg" }
            list.addItem(Row.Builder().setTitle(tr("$whenTxt · ${fmtDistance(t.distanceKm, s)}"))
                .addText(tr(bits.joinToString(" · ").ifBlank { "No details" })).build())
        }
        return b.setSingleList(list.build()).build()
    }

    private fun consumption(kwhPer100Km: Double, s: no.lionflow.app.data.AppSettings) =
        if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
        else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwhPer100Km)
}
