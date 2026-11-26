package no.lionflow.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.ScreenManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.RemoteControl
import no.lionflow.app.api.StellantisApi
import no.lionflow.app.data.SecureStore
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Goes through the real Android Auto screens as in the car: the start screen with a car's status put in place, a
 * tap on a tile, Car Info, Previous trips, Saved routes and a saved trip, in Norwegian. Nothing may fail on the
 * way, and each screen is saved as a picture (app/build/car-previews/real-*.png).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CarRealScreensTest.CarApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarRealScreensTest {
    /** The app with only what the car screens read: no sign-in, no background work, no notifications. */
    class CarApp : LionFlowApp() {
        override fun onCreate() {
            settings = SettingsStore(this)
            L10n.init(this, "nb")
            TimeFmt.setPreference(this, "24")
            secure = SecureStore(this)
            api = StellantisApi(this, secure, settings)
            remote = RemoteControl(api, secure)
            ChargePower.init(this, settings, null)
            ChargeLog.init(this, null)
        }
    }

    private val out = File(System.getProperty("carPreviewDir") ?: "build/car-previews").apply { mkdirs() }
    private val w = 1920; private val h = 720
    private val free = Rect(0, 104, 1216, 720)   // split screen: a media card takes the rest

    private fun idle() = CarTestKit.idle()

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** A status as the car's service sends it. */
    private fun status(kind: String, lat: Double? = 63.79, lon: Double? = 11.48): VehicleStatus {
        val now = iso(System.currentTimeMillis() - 4 * 60_000L)
        val charging = kind == "charging"
        val electric = """{"type":"Electric","level":${if (charging) 64 else 79},"autonomy":312,"updatedAt":"$now","extension":{"electric":{
            "charging":{"status":"${if (charging) "InProgress" else "Disconnected"}","plugged":$charging,"chargingRate":48,"remainingTime":"PT1H20M","chargingMode":"Slow","nextDelayedTime":"PT23H30M","type":"Partial"},
            "battery":{"load":{"residual":57600,"capacity":73000},"health":{"capacity":97}}}}}"""
        val fuel = """{"type":"Fuel","level":41,"autonomy":520}"""
        val energies = when (kind) { "fuel" -> fuel; "hybrid" -> "$electric,$fuel"; else -> electric }
        val position = if (lat != null && lon != null) ""","lastPosition":{"geometry":{"coordinates":[$lon,$lat]}}""" else ""
        val j = JSONObject("""{"updatedAt":"$now","energies":[$energies],"odometer":{"mileage":23418.4},
            "doorsState":{"lockedStates":["Locked"],"opening":[{"identifier":"Driver","state":"Closed"}]},
            "environment":{"air":{"temp":3.0}},"preconditioning":{"airConditioning":{"status":"${if (charging) "Enabled" else "Disabled"}","programs":[]}},
            "ignition":{"type":"Stop"},"kinetic":{"moving":false,"speed":0},"battery":{"voltage":12.6},"privacy":{"state":"None"},
            "maintenance":{"mileageBeforeMaintenance":11200,"daysBeforeMaintenance":214}$position}""")
        return ChargePower.record(VehicleStatus.parse(j, null))
    }

    private fun trips(): List<Trip> {
        val now = System.currentTimeMillis()
        return (0 until 23).map { i ->
            val start = now - i * 9 * 3600_000L - 40 * 60_000L
            val km = listOf(21.4, 23.0, 86.2, 7.3, 142.0)[i % 5]
            Trip("t$i", start, start + 31 * 60_000L, km, listOf(1440.0, 1860.0, 4320.0, 600.0, 6900.0)[i % 5], km * 0.178, listOf(17.2, 19.4, 16.8, 21.0, 18.1)[i % 5], listOf(52.0, 44.0, 71.0, 38.0, 74.0)[i % 5])
        }
    }

    /** A saved trip from Verdal towards Oslo with two charging stops. */
    private fun savedTrip(id: Long): SavedRoutes.Saved {
        val n = 700
        val lat = DoubleArray(n); val lon = DoubleArray(n); val km = DoubleArray(n); val kwh = DoubleArray(n); val min = DoubleArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1.0)
            lat[i] = 63.79 - 3.9 * t + 0.25 * sin(t * 9.0) + 0.05 * sin(t * 40.0)
            lon[i] = 11.48 - 0.4 * t + 0.9 * sin(t * 5.0) + 0.08 * cos(t * 33.0) - 0.08
            km[i] = 573.0 * t; kwh[i] = 104.0 * t; min[i] = 420.0 * t
        }
        val stops = listOf(245, 480).map { i -> SavedRoutes.Stop(if (i == 245) "Circle K Berkåk" else "Ionity Dombås", lat[i], lon[i], i, km[i], 22, 18.0, 80.0) }
        return SavedRoutes.Saved(id, "Verdal → Oslo", lat, lon, km, kwh, 73.0, stops, 0, null, min)
    }

    private fun save(name: String, s: DrawnScreen) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        s.paint(Canvas(bmp), CarSurface.Frame(w, h, 160, free, free))
        FileOutputStream(File(out, "real-$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun throughTheRealScreens() {
        val app = RuntimeEnvironment.getApplication() as CarApp
        fun faults() = app.remote.log.filter { it.contains("Android Auto:") && !it.contains("map picture") }
        val ctx = TestCarContext.createCarContext(app)
        val ui = CarUi(ctx)
        ui.surface.start()
        val car: SurfaceCallback = ctx.getCarService(TestAppManager::class.java).surfaceCallback!!
        // The dark look (without a choice it follows the car, which is in day mode here).
        app.settings.setRaw("carTheme", "dark")
        val home = HomeScreen(ctx, ui)
        CarTestKit.onShow(ctx)
        val screens = ctx.getCarService(ScreenManager::class.java)
        screens.push(home); idle()
        assertTrue("the start screen is started", home.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        fun top() = screens.top
        fun tap(key: String) {
            val r = (top() as DrawnScreen).tappable.first { it.key == key }.rect
            car.onClick(r.centerX(), r.centerY()); idle()
        }
        car.onSurfaceAvailable(SurfaceContainer(null, w, h, 160)); car.onVisibleAreaChanged(free); car.onStableAreaChanged(free); idle()

        // Before the car has answered.
        home.onGetTemplate(); save("home-waiting", home)

        // A parked car, then one that is charging, a plug-in hybrid and a petrol car.
        CarHub.show(status("parked"), trips()); idle(); save("home", home)
        CarHub.show(status("charging"), trips()); idle(); save("home-charging", home)
        CarHub.show(status("hybrid"), trips()); idle(); save("home-hybrid", home)
        CarHub.show(status("fuel"), trips()); idle(); save("home-fuel", home)
        assertEquals(emptyList<String>(), faults())

        // Car Info, by its tile; back with the drawn back button.
        CarHub.show(status("charging"), trips()); idle()
        tap("info")
        assertTrue("the Car Info tile opens Car Info", top() is CarInfoScreen)
        top().onGetTemplate(); save("info", top() as DrawnScreen)
        CarHub.show(status("fuel"), trips()); idle(); save("info-fuel", top() as DrawnScreen)
        app.settings.setRaw("carTheme", "light")
        CarHub.show(status("hybrid"), trips()); idle(); save("info-hybrid-light", top() as DrawnScreen)
        app.settings.setRaw("carTheme", "dark")
        tap("back")
        assertEquals(home, top())

        // The battery gauge opens Car Info too.
        CarHub.show(status("parked"), trips()); idle()
        tap("gauge"); assertTrue(top() is CarInfoScreen); tap("back")

        // Previous trips: the first page, then the next one with the arrow.
        tap("trips")
        assertTrue(top() is TripListScreen)
        top().onGetTemplate(); save("trips", top() as DrawnScreen)
        tap("page-down"); save("trips-2", top() as DrawnScreen)
        tap("back")

        // Plan a trip opens the search (one of Android Auto's own screens).
        tap("plan")
        assertTrue(top() is TripSearchScreen)
        top().onGetTemplate()
        screens.pop(); idle()
        assertEquals(home, top())

        // A trip to a place when the car hasn't shared where it is: the screen says so and offers to try again.
        CarHub.show(status("parked", null, null), trips()); idle()
        screens.push(TripScreen.plan(ctx, ui, Place("Oslo S", "Oslo", 59.911, 10.753))); idle()
        top().onGetTemplate(); save("trip-no-position", top() as DrawnScreen)
        assertTrue("a plan that failed can be tried again", (top() as DrawnScreen).tappable.any { it.key == "retry" })
        tap("back")

        // A saved trip, with the car a good way along it, then well off the route.
        val trip = savedTrip(4711L)
        SavedRoutes.add(app, trip)
        screens.push(SavedListScreen(ctx, ui)); idle()
        top().onGetTemplate(); save("saved", top() as DrawnScreen)
        tap("r4711")
        assertTrue("a saved route opens the trip", top() is TripScreen)
        CarHub.show(status("parked", trip.lat[150], trip.lon[150]), trips()); idle()
        top().onGetTemplate(); save("trip", top() as DrawnScreen)
        CarHub.show(status("parked", trip.lat[150] + 0.4, trip.lon[150] + 1.2), trips()); idle()
        save("trip-off-route", top() as DrawnScreen)
        // Deleted on the phone while it shows: the screen closes.
        SavedRoutes.delete(app, 4711L); idle()
        assertTrue(top() is SavedListScreen)
        save("saved-none", top() as DrawnScreen)
        tap("back")
        assertEquals(home, top())

        assertEquals(emptyList<String>(), faults())
        ui.surface.close()
    }
}
