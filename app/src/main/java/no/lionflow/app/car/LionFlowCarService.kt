package no.lionflow.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp

/**
 * LionFlow in Android Auto: a navigation-category car app so it can draw its own 3D map
 * on the centre screen and, in cars that offer it, on the driver display (cluster).
 */
class LionFlowCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Only Android Auto / Google's car hosts may connect (or anything, in a debug build).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) ClusterSession() else MainSession()

    @Deprecated("Older hosts")
    override fun onCreateSession(): Session = MainSession()
}

private fun CarContext.icon(res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let { setTint(it) } }.build()

// ---------------------------------------------------------------- centre screen

class MainSession : Session() {
    private lateinit var renderer: CarMapRenderer

    override fun onCreateScreen(intent: Intent): Screen {
        renderer = CarMapRenderer(carContext, this, cluster = false)
        val nav = carContext.getCarService(NavigationManager::class.java)
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                // Another app (e.g. Google Maps) started navigating: give the driver display back.
                CarHub.driverDisplay.value = false
            }
            override fun onAutoDriveEnabled() {}
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (CarHub.driverDisplay.value) try { nav.navigationEnded() } catch (_: Exception) { }
                CarHub.driverDisplay.value = false
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarHub.notices.collect { m -> CarToast.makeText(carContext, m, CarToast.LENGTH_LONG).show() }
            }
        }
        CarHub.refresh(carContext)
        // Offer our map to the driver display straight away, if the user wants that.
        if (CarHub.app(carContext).settings.value.carCluster && CarHub.app(carContext).api.isSignedIn) setDriverDisplay(true)
        return MapScreen(carContext, renderer, ::setDriverDisplay)
    }

    private fun setDriverDisplay(on: Boolean) {
        val nav = carContext.getCarService(NavigationManager::class.java)
        try {
            if (on) nav.navigationStarted() else nav.navigationEnded()
            CarHub.driverDisplay.value = on
        } catch (e: Exception) {
            CarHub.driverDisplay.value = false
            CarToast.makeText(carContext, "Driver display isn't available right now", CarToast.LENGTH_LONG).show()
        }
    }
}

/** Base screen that redraws whenever the shared car state changes. */
abstract class LiveScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(CarHub.status, CarHub.message, CarHub.busy, CarHub.driverDisplay, CarHub.chargers) { _, _, _, _, _ -> Unit }
                    .collect { invalidate() }
            }
        }
    }
}

class MapScreen(
    ctx: CarContext,
    private val renderer: CarMapRenderer,
    private val setDriverDisplay: (Boolean) -> Unit,
) : LiveScreen(ctx) {
    init {
        renderer.onStateChanged = { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val app = CarHub.app(carContext)
        if (!app.api.isSignedIn) {
            return MessageTemplate.Builder("Open LionFlow on your phone and sign in with your Peugeot account.")
                .setTitle("LionFlow")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Car").setIcon(carContext.icon(R.drawable.ic_car_battery))
                .setOnClickListener { screenManager.push(StatusScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("Chargers").setIcon(carContext.icon(R.drawable.ic_car_charger))
                .setOnClickListener { screenManager.push(ChargersScreen(carContext, renderer)) }.build())
            .addAction(Action.Builder()
                .setIcon(carContext.icon(R.drawable.ic_car_cluster, if (CarHub.driverDisplay.value) CarColor.GREEN else null))
                .setOnClickListener {
                    val on = !CarHub.driverDisplay.value
                    setDriverDisplay(on)
                    CarToast.makeText(carContext, if (on) "Map offered to the driver display" else "Driver display released", CarToast.LENGTH_SHORT).show()
                }.build())
            .build()

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                if (renderer.following)
                    Action.Builder().setIcon(carContext.icon(if (renderer.is3d) R.drawable.ic_car_2d else R.drawable.ic_car_3d))
                        .setOnClickListener { renderer.toggle3d() }.build()
                else
                    Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_center))
                        .setOnClickListener { renderer.recenter() }.build()
            )
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_plus)).setOnClickListener { renderer.zoom(1.0) }.build())
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_minus)).setOnClickListener { renderer.zoom(-1.0) }.build())
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setBackgroundColor(CarColor.SECONDARY)
            .build()
    }
}

// ---------------------------------------------------------------- car status + actions

class StatusScreen(ctx: CarContext) : LiveScreen(ctx) {
    init { CarHub.refresh(ctx) }

    override fun onGetTemplate(): Template {
        val st = CarHub.status.value
        val s = CarHub.app(carContext).settings.value
        val pane = Pane.Builder()
        if (st == null) {
            pane.setLoading(true)
        } else {
            pane.addRow(Row.Builder().setTitle(CarHub.batteryLine(carContext)).addText(CarHub.chargingLine(st))
                .setImage(carContext.icon(R.drawable.ic_car_battery)).build())
            pane.addRow(Row.Builder().setTitle(if (st.preconditioningOn) "Pre-heating is on" else "Pre-heating is off")
                .addText(st.outsideTempC?.let { "${fmtTemp(it, s)} outside" } ?: "Outside temperature unknown")
                .setImage(carContext.icon(R.drawable.ic_car_climate)).build())
            pane.addRow(Row.Builder().setTitle("Doors: ${CarHub.doorsLine(st)}")
                .addText(st.odometerKm?.let { "Odometer ${fmtDistance(it, s)}" } ?: CarHub.updatedLine())
                .setImage(carContext.icon(R.drawable.ic_car_lock)).build())
            pane.addRow(Row.Builder().setTitle(CarHub.message.value ?: CarHub.updatedLine()).build())

            if (CarHub.actionsAllowed(carContext) == null) {
                pane.addAction(Action.Builder().setTitle(if (st.preconditioningOn) "Stop pre-heat" else "Pre-heat")
                    .setFlags(Action.FLAG_PRIMARY).setEnabled(!CarHub.busy.value)
                    .setOnClickListener { CarHub.preheat(carContext, !st.preconditioningOn) }.build())
                pane.addAction(Action.Builder().setTitle(if (st.isCharging) "Stop charging" else "Charge now")
                    .setEnabled(!CarHub.busy.value && (st.plugged || st.isCharging))
                    .setOnClickListener { CarHub.charge(carContext, !st.isCharging) }.build())
            }
        }
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Refresh").setOnClickListener { CarHub.refresh(carContext, force = true); CarHub.say("Updating…") }.build())
        if (CarHub.actionsAllowed(carContext) == null)
            strip.addAction(Action.Builder().setTitle("More").setOnClickListener { screenManager.push(ActionsScreen(carContext)) }.build())
        return PaneTemplate.Builder(pane.build())
            .setTitle(CarHub.app(carContext).settings.value.carName)
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip.build())
            .build()
    }
}

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

class ChargersScreen(ctx: CarContext, private val renderer: CarMapRenderer) : LiveScreen(ctx) {
    init {
        CarHub.status.value?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> CarHub.loadChargers(ctx, la, lo) } } }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val all = CarHub.chargers.value
        val b = ListTemplate.Builder().setTitle("Chargers nearby").setHeaderAction(Action.BACK)
        if (all.isEmpty()) return b.setLoading(true).build()
        val max = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Exception) { 6 }
        val list = ItemList.Builder()
        all.take(max).forEach { ch ->
            val bits = mutableListOf<String>()
            ch.distanceM?.let { m -> bits += if (s.distanceUnit != "mi" && m < 1000) "$m m" else if (s.distanceUnit != "mi" && m < 10_000) String.format(java.util.Locale.US, "%.1f km", m / 1000.0) else fmtDistance(m / 1000.0, s) }
            if (ch.maxKw > 0) bits += "${ch.maxKw.toInt()} kW"
            if (ch.points > 0) bits += "${ch.points} points"
            list.addItem(Row.Builder().setTitle(ch.name).addText(bits.joinToString(" · ").ifBlank { ch.address })
                .setImage(carContext.icon(R.drawable.ic_car_charger, if (ch.maxKw >= 50) CarColor.GREEN else null))
                .setOnClickListener {
                    CarHub.focus.value = ch
                    screenManager.pop()
                }.build())
        }
        return b.setSingleList(list.build()).build()
    }
}

// ---------------------------------------------------------------- driver display

class ClusterSession : Session() {
    private var keep: CarMapRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = CarMapRenderer(carContext, this, cluster = true)
        return object : Screen(carContext) {
            override fun onGetTemplate(): Template = NavigationTemplate.Builder()
                // The cluster has no input; the host only shows the map. An action strip is still required.
                .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("LionFlow").setOnClickListener { }.build()).build())
                .build()
        }.also { keep = renderer }
    }
}
