package no.lionflow.app.car

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import no.lionflow.app.R
import no.lionflow.app.car.ui.GarageModel
import no.lionflow.app.car.ui.GaragePaint
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.HomeModel
import no.lionflow.app.car.ui.HomePaint
import no.lionflow.app.car.ui.InfoModel
import no.lionflow.app.car.ui.InfoPaint
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.ListModel
import no.lionflow.app.car.ui.ListPaint
import no.lionflow.app.car.ui.Palette
import no.lionflow.app.car.ui.Stage
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.car.ui.TripModel
import no.lionflow.app.car.ui.TripPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the Android Auto screens to pictures, with the real Android drawing code, for a few car screen sizes, in
 * split and full screen and in both looks. Run with `gradle :app:testDebugUnitTest -PcarPreviews`; the pictures
 * land in app/build/car-previews. (Left out of ordinary test runs: it needs Android's graphics from the network.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPreviewTest {
    /** A car screen: its size, and the part Android Auto leaves free (beside a media card, below its buttons). */
    private class Scene(val name: String, val w: Int, val h: Int, val dpi: Int, val visible: Rect)

    private val scenes = listOf(
        Scene("wide-full", 1920, 720, 160, Rect(0, 104, 1920, 720)),
        Scene("wide-split", 1920, 720, 160, Rect(0, 104, 1216, 720)),
        Scene("hd", 1280, 720, 160, Rect(0, 100, 1280, 720)),
        Scene("small", 800, 480, 160, Rect(0, 76, 800, 480)),
        Scene("tall", 1080, 1500, 240, Rect(0, 150, 1080, 1500)),
    )

    private val out = File(System.getProperty("carPreviewDir") ?: "build/car-previews").apply { mkdirs() }
    private val icons by lazy { CarIcons(RuntimeEnvironment.getApplication()) }

    /** Draws one screen (through a recording, as in the car), then roughly what Android Auto puts on top, and saves it. */
    private fun shot(file: String, s: Scene, pal: Palette, hostIcons: Int, draw: (Canvas, Kit, MutableList<Hit>) -> Unit): List<Hit> {
        val stage = Stage(s.w, s.h, s.dpi, s.visible, s.visible, hostIcons, 0)
        val kit = Kit(pal, stage, icons)
        val hits = ArrayList<Hit>()
        val bmp = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pic = Picture()
        draw(pic.beginRecording(s.w, s.h), kit, hits)
        pic.endRecording()
        c.drawPicture(pic)

        val d = s.dpi / 160f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val size = 76f * d
        var x = s.visible.right - 16f * d
        val cy = s.visible.top / 2f + 4f * d
        repeat(hostIcons) {
            p.color = if (pal.dark) 0xE6202124.toInt() else 0xF2FFFFFF.toInt()
            c.drawCircle(x - size / 2, cy, size / 2, p)
            p.color = if (pal.dark) 0xFFE8EAED.toInt() else 0xFF3C4043.toInt()
            c.drawCircle(x - size / 2, cy, size * 0.12f, p)
            x -= size + 12f * d
        }
        if (s.visible.right < s.w) {
            p.color = 0xFF000000.toInt()
            c.drawRect(RectF(s.visible.right.toFloat(), 0f, s.w.toFloat(), s.h.toFloat()), p)
            p.color = 0xFF2A2C31.toInt()
            c.drawRoundRect(RectF(s.visible.right + 14f, 14f, s.w - 14f, s.h - 14f), 28f, 28f, p)
        }
        FileOutputStream(File(out, "$file.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // What can be tapped must be on the part of the screen that shows.
        for (h in hits) {
            assertTrue("$file: '${h.key}' is outside the free part of the screen: ${h.rect}",
                h.rect.left >= s.visible.left - 1f && h.rect.right <= s.visible.right + 1f && h.rect.top >= -1f && h.rect.bottom <= s.visible.bottom + 1f)
        }
        return hits
    }

    // ---------------- sample data ----------------

    private fun home(nb: Boolean, tiles: Int = 6, charging: Boolean = false, loading: Boolean = false): HomeModel {
        val all = listOf(
            HomeModel.Tile("plan", if (nb) "Planlegg en tur" else "Plan a trip", if (nb) "Batteri-% ved ankomst" else "Battery % on arrival", R.drawable.ic_car_search, true),
            HomeModel.Tile("chargers", if (nb) "Ladere" else "Chargers", if (nb) "Nærmeste først" else "Nearest first", R.drawable.ic_car_charger),
            HomeModel.Tile("saved", if (nb) "Lagrede ruter" else "Saved routes", if (nb) "3 ruter" else "3 routes", R.drawable.ic_car_route),
            HomeModel.Tile("trips", if (nb) "Tidligere turer" else "Previous trips", if (nb) "7 dager: 214 km" else "7 days: 214 km", R.drawable.ic_car_history),
            HomeModel.Tile("info", if (nb) "Bilinfo" else "Car Info", "23,418 km", R.drawable.ic_car_gauge),
            HomeModel.Tile("switch", if (nb) "Bytt bil" else "Switch car", "E-5008", R.drawable.ic_car_switch),
        )
        val lines = if (charging) listOf(
            HomeModel.Line(R.drawable.ic_car_bolt, (if (nb) "Lading" else "Charging") + " · 10.8 kW", Tone.GOOD),
            HomeModel.Line(R.drawable.ic_car_history, if (nb) "Fullt om 1 h 20 min" else "Full in 1 h 20 min", Tone.GOOD),
            HomeModel.Line(R.drawable.ic_car_lock, if (nb) "Låst" else "Locked", Tone.TEXT),
            HomeModel.Line(R.drawable.ic_car_climate, if (nb) "3°C ute" else "3°C outside", Tone.TEXT),
            HomeModel.Line(R.drawable.ic_car_climate, if (nb) "Forvarming" else "Pre-heating", Tone.WARN),
        ) else listOf(
            HomeModel.Line(R.drawable.ic_car_lock, if (nb) "Låst" else "Locked", Tone.TEXT),
            HomeModel.Line(R.drawable.ic_car_bolt, if (nb) "Ikke tilkoblet" else "Not plugged in", Tone.TEXT),
            HomeModel.Line(R.drawable.ic_car_climate, if (nb) "3°C ute" else "3°C outside", Tone.TEXT),
        )
        return HomeModel(
            "E-5008", if (nb) "Bilen rapporterte 4 min siden" else "Car reported 4 min ago", Tone.MUTED,
            if (loading) null else HomeModel.Gauge(if (charging) 64.0 else 79.0, "312 km", lines),
            if (nb) "Henter bilens status…" else "Getting the car's status…", all.take(tiles),
        )
    }

    private fun info(nb: Boolean, charging: Boolean = false) = InfoModel(
        if (nb) "Bilinfo" else "Car Info", if (nb) "Bilen rapporterte 4 min siden" else "Car reported 4 min ago", Tone.MUTED,
        listOfNotNull(
            InfoModel.Cell(if (nb) "Batteri" else "Battery", "79 %", Tone.GOOD, 79.0),
            InfoModel.Cell(if (nb) "Rekkevidde" else "Range", "312 km"),
            InfoModel.Cell(if (nb) "Energi i batteriet" else "Energy in the battery", "57.6 / 73.0 kWh"),
            InfoModel.Cell(if (nb) "Batterihelse" else "Battery health", "97 %", Tone.TEXT, 97.0),
            InfoModel.Cell(if (nb) "Lading" else "Charging", if (charging) (if (nb) "Lading" else "Charging") else (if (nb) "Ikke tilkoblet" else "Not plugged in"), if (charging) Tone.GOOD else Tone.TEXT),
            if (charging) InfoModel.Cell(if (nb) "Ladehastighet" else "Charge speed", "48 km/h") else null,
            if (charging) InfoModel.Cell(if (nb) "Fullt om" else "Full in", "1 h 20 min") else null,
            InfoModel.Cell(if (nb) "Planlagt lading" else "Scheduled charging", "23:30"),
            InfoModel.Cell(if (nb) "Kilometerstand" else "Odometer", "23,418 km"),
            InfoModel.Cell(if (nb) "Neste service" else "Next service", if (nb) "11,200 km · 214 dager" else "11,200 km · 214 days"),
            InfoModel.Cell(if (nb) "Dører" else "Doors", if (nb) "Låst" else "Locked"),
            InfoModel.Cell(if (nb) "Ute" else "Outside", "3°C"),
            InfoModel.Cell(if (nb) "Forvarming" else "Pre-heating", if (charging) (if (nb) "På" else "On") else (if (nb) "Av" else "Off"), if (charging) Tone.WARN else Tone.TEXT),
            InfoModel.Cell(if (nb) "12 V-batteri" else "12 V battery", "12.6 V"),
            InfoModel.Cell(if (nb) "Fart" else "Speed", if (nb) "Parkert" else "Parked"),
            InfoModel.Cell(if (nb) "Tenning" else "Ignition", if (nb) "Stopp" else "Stop"),
            InfoModel.Cell(if (nb) "Personvernmodus" else "Privacy mode", if (nb) "Ingen" else "None"),
        ),
        "Getting the car's status…",
    )

    private fun trips(count: Int = 14) = ListModel(
        "Previous trips", null,
        summary = ListModel.Summary(R.drawable.ic_car_history, "Last 7 days: 214 km · 9 trips", "17.8 kWh/100 km"),
        rows = (0 until count).map { i ->
            ListModel.Row(null, listOf("Today 16:42", "Today 07:31", "Mon 23 Nov 15:10", "Mon 23 Nov 07:28", "Sun 22 Nov 12:05", "Sat 21 Nov 18:40")[i % 6],
                listOf("24 min · 17.2 kWh/100 km · 52 km/h avg", "31 min · 19.4 kWh/100 km · 44 km/h avg", "1 h 12 min · 16.8 kWh/100 km · 71 km/h avg")[i % 3],
                R.drawable.ic_car_route, end = listOf("21 km", "23 km", "86 km", "7 km")[i % 4])
        },
        empty = "No trips in the last 30 days",
    )

    private fun chargers() = ListModel(
        "Ladere nær bilen", null,
        rows = listOf(
            ListModel.Row("c0", "Circle K Verdal", "150 kW · 6 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "850 m"),
            ListModel.Row("c1", "Recharge Verdal Amfi", "22 kW · 4 ladepunkter", R.drawable.ic_car_charger, end = "1.2 km"),
            ListModel.Row("c2", "Tesla Supercharger Levanger", "250 kW · 16 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "11 km"),
            ListModel.Row("c3", "Mer Levanger Magneten kjøpesenter med et veldig langt navn", "62 kW · 2 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "12 km"),
            ListModel.Row("c4", "Eviny Stiklestad", "300 kW · 8 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "5.4 km"),
            ListModel.Row("c5", "Kople Inderøy", "50 kW · 2 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "24 km"),
            ListModel.Row("c6", "Ionity Steinkjer", "350 kW · 6 ladepunkter", R.drawable.ic_car_charger, lit = true, end = "31 km"),
        ),
        empty = "Laster…",
    )

    private fun road(n: Int = 700): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val lat = DoubleArray(n); val lon = DoubleArray(n); val km = DoubleArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1.0)
            lat[i] = 63.79 - 3.9 * t + 0.25 * sin(t * 9.0) + 0.05 * sin(t * 40.0)
            lon[i] = 11.48 - 0.4 * t + 0.9 * sin(t * 5.0) + 0.08 * cos(t * 33.0)
            km[i] = 573.0 * t
        }
        return Triple(lat, lon, km)
    }

    private fun saved(): ListModel {
        val (la, lo, _) = road(200)
        return ListModel(
            "Lagrede ruter", null,
            rows = listOf(
                ListModel.Row("r1", "Verdal → Oslo", "573 km · 2 ladestopp", shape = ListModel.Shape(la, lo)),
                ListModel.Row("r2", "Tønsberg ⇄", "1,180 km · 5 ladestopp", shape = ListModel.Shape(la.reversedArray(), lo)),
                ListModel.Row("r3", "Verdal → Trondheim lufthavn Værnes", "62 km · ingen ladestopp", shape = ListModel.Shape(la.copyOf(40), lo.copyOf(40))),
            ),
            empty = "",
        )
    }

    private fun cars() = ListModel(
        "Switch car", null,
        rows = listOf(
            ListModel.Row("e0", "E-5008", "Peugeot · Selected", R.drawable.ic_car_switch, lit = true, selected = true),
            ListModel.Row("e1", "Corsa-e", "Opel", R.drawable.ic_car_switch),
        ),
        empty = "",
    )

    private fun trip(nb: Boolean, at: Int, map: Bitmap? = null, note: String? = null): TripModel {
        val (la, lo, km) = road()
        val lastLeg = at >= 480
        val stops = listOf(245, 480).map { i ->
            val next = i > at && (i == 245 || at >= 245)
            TripModel.Stop(la[i], lo[i], km[i], passed = i <= at, next = next, pct = if (i <= at) null else if (next) 23.0 else 18.0, about = !next)
        }
        return TripModel(
            "Verdal → Oslo", if (nb) "Bilen rapporterte 1 min siden" else "Car reported 1 min ago", Tone.MUTED, TripModel.READY,
            big = if (lastLeg) "99 km" else "448 km", bigUnit = if (nb) "igjen" else "left",
            eta = if (nb) "≈ 6 h 40 min · fremme 19:12" else "≈ 6 h 40 min · arrive 19:12",
            nowPct = 64.0, nowLabel = if (nb) "Nå" else "Now",
            thenPct = if (lastLeg) 31.0 else 23.0, thenLabel = if (lastLeg) (if (nb) "Ankomst" else "Arrival") else (if (nb) "Neste stopp" else "Next stop"),
            stopTitle = if (lastLeg) (if (nb) "Ingen ladestopp igjen" else "No charging stops left") else "Circle K Berkåk",
            stopSub = if (lastLeg) (if (nb) "Rett til målet" else "Straight to the destination") else (if (nb) "Om 67 km · 2 stopp igjen" else "In 67 km · 2 stops left"),
            stopLive = !lastLeg, note = note, noteTone = Tone.WARN,
            lat = la, lon = lo, km = km, at = at, showCar = true, stops = stops,
            endPct = if (lastLeg) 31.0 else null, destName = "Oslo", totalLabel = "573 km", map = map, credit = "© OpenFreeMap © OpenStreetMap",
        )
    }

    /** Something map-like to draw the route on (the real map comes from the network in the car). */
    private fun fakeMap(w: Int, h: Int, dark: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(if (dark) 0xFF1B2026.toInt() else 0xFFE9EDF0.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (dark) 0xFF12222E.toInt() else 0xFFBFDDEE.toInt()
        c.drawCircle(w * 0.05f, h * 0.9f, h * 0.6f, p)
        p.color = if (dark) 0xFF2A3038.toInt() else 0xFFFFFFFF.toInt(); p.strokeWidth = 3f
        for (i in 0 until 14) c.drawLine(((i * 97) % w).toFloat(), 0f, ((i * 211 + 80) % w).toFloat(), h.toFloat(), p)
        return b
    }

    private fun garage(open: Boolean) = GarageModel("Garage door", if (open) "Door is open" else "Door is closed", if (open) Tone.WARN else Tone.MUTED, listOf(
        GarageModel.Choice("open", "Open", main = !open),
        GarageModel.Choice("close", "Close", main = open),
        GarageModel.Choice("cancel", "Cancel", R.drawable.ic_car_close),
    ))

    // ---------------- the pictures ----------------

    @Test fun pictures() {
        for (s in scenes) {
            val hits = shot("home-${s.name}", s, Palette.DARK, 4) { c, k, h -> HomePaint.draw(c, k, home(true), null, h) }
            assertTrue("home: every tile can be tapped", listOf("plan", "chargers", "saved", "trips", "info", "switch", HomePaint.GAUGE).all { key -> hits.any { it.key == key } })
            shot("info-${s.name}", s, Palette.DARK, 1) { c, k, h -> InfoPaint.draw(c, k, info(true), null, h) }
            shot("trips-${s.name}", s, Palette.DARK, 1) { c, k, h -> ListPaint.draw(c, k, trips(), 0, null, h) }
            shot("trip-${s.name}", s, Palette.DARK, 1) { c, k, h -> TripPaint.draw(c, k, trip(true, 150), null, h) }
            val g = shot("garage-${s.name}", s, Palette.DARK, 1) { c, k, h -> GaragePaint.draw(c, k, garage(false), null, h) }
            assertTrue("garage: open, close and cancel can be tapped", listOf("open", "close", "cancel").all { key -> g.any { it.key == key } })
        }
        shot("garage-wide-split-light-open", scenes[1], Palette.LIGHT, 1) { c, k, h -> GaragePaint.draw(c, k, garage(true), "close", h) }
        val split = scenes[1]; val full = scenes[0]; val hd = scenes[2]
        shot("home-wide-split-light", split, Palette.LIGHT, 4) { c, k, h -> HomePaint.draw(c, k, home(false, charging = true), "chargers", h) }
        shot("home-wide-full-light", full, Palette.LIGHT, 3) { c, k, h -> HomePaint.draw(c, k, home(false, 5), null, h) }
        shot("home-wide-split-5", split, Palette.DARK, 2) { c, k, h -> HomePaint.draw(c, k, home(true, 5, charging = true), null, h) }
        shot("home-hd-loading", hd, Palette.DARK, 2) { c, k, h -> HomePaint.draw(c, k, home(false, 4, loading = true), null, h) }
        shot("info-wide-split-light", split, Palette.LIGHT, 1) { c, k, h -> InfoPaint.draw(c, k, info(false, true), null, h) }
        shot("chargers-wide-split", split, Palette.DARK, 1) { c, k, h -> ListPaint.draw(c, k, chargers(), 0, "c1", h) }
        shot("chargers-wide-split-light-2", split, Palette.LIGHT, 1) { c, k, h ->
            val look = ListPaint.draw(c, k, chargers(), 1, null, h)
            assertEquals("second page", 1, look.page); assertTrue(look.pages >= 2)
        }
        shot("saved-wide-split", split, Palette.DARK, 1) { c, k, h -> ListPaint.draw(c, k, saved(), 0, null, h) }
        shot("cars-hd-light", hd, Palette.LIGHT, 1) { c, k, h -> ListPaint.draw(c, k, cars(), 0, null, h) }
        shot("trips-empty", split, Palette.DARK, 1) { c, k, h -> ListPaint.draw(c, k, ListModel("Previous trips", null, rows = emptyList(), empty = "No trips in the last 30 days"), 0, null, h) }
        shot("trip-wide-split-light-map", split, Palette.LIGHT, 1) { c, k, h ->
            val l = TripPaint.layout(k)
            TripPaint.draw(c, k, trip(false, 330, fakeMap(l.map.width().toInt(), l.map.height().toInt(), false), "The car is 4 km from the route"), null, h)
        }
        shot("trip-wide-full-map-last", full, Palette.DARK, 1) { c, k, h ->
            val l = TripPaint.layout(k)
            TripPaint.draw(c, k, trip(true, 560, fakeMap(l.map.width().toInt(), l.map.height().toInt(), true)), null, h)
        }
        shot("trip-wait", split, Palette.DARK, 1) { c, k, h -> TripPaint.draw(c, k, TripModel("Oslo", "", Tone.MUTED, TripModel.WAIT, "Finner ruten…", phase = 0.3f), null, h) }
        val failed = shot("trip-failed", split, Palette.LIGHT, 1) { c, k, h ->
            TripPaint.draw(c, k, TripModel("Oslo", "", Tone.MUTED, TripModel.FAILED, "Couldn't plan the trip — no connection to the route server. Check the phone's connection.", "Try again"), null, h)
        }
        assertTrue("a failed plan can be tried again", failed.any { it.key == TripPaint.RETRY })
        println("car pictures in ${out.absolutePath}: ${out.list()?.size}")
    }

    @Test fun navigateRequests() {
        parseNavigate(Uri.parse("geo:59.91,10.75")).let { (place, words) -> assertEquals(59.91, place!!.lat, 1e-9); assertEquals(10.75, place.lon, 1e-9); assertNull(words) }
        parseNavigate(Uri.parse("geo:0,0?q=59.91,10.75(Oslo%20S)")).let { (place, _) -> assertEquals("Oslo S", place!!.name); assertEquals(59.91, place.lat, 1e-9) }
        parseNavigate(Uri.parse("geo:63.4,10.4?q=63.4,10.4(Work)")).let { (place, _) -> assertEquals("Work", place!!.name); assertEquals(10.4, place.lon, 1e-9) }
        parseNavigate(Uri.parse("geo:0,0?q=Storgata+1,+Oslo")).let { (place, words) -> assertNull(place); assertEquals("Storgata 1, Oslo", words) }
        parseNavigate(Uri.parse("geo:0,0?q=0.0,0.0")).let { (place, words) -> assertNull(place); assertNull(words) }
        parseNavigate(Uri.parse("geo:0,0")).let { (place, words) -> assertNull(place); assertNull(words) }
        parseNavigate(null).let { (place, words) -> assertNull(place); assertNull(words) }
        assertNotNull(parseNavigate(Uri.parse("geo:-33.8688,151.2093")).first)
    }
}
