package no.lionflow.app.car

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val BG = 0xFF0D0E10.toInt()
private const val CARD = 0xFF16181B.toInt()
private const val RAISED = 0xFF1E2024.toInt()
private const val LINE = 0xFF2A2D32.toInt()
private const val TEXT = 0xFFF2F3F5.toInt()
private const val MUTED = 0xFF9A9EA6.toInt()
private const val LIME = 0xFFC6F36B.toInt()
private const val ORANGE = 0xFFFFB547.toInt()
private const val RED = 0xFFE5484D.toInt()
private const val DRIVEN = 0xFF3A3E45.toInt()

/** Fits a route's points into a box, keeping the shape (longitudes squeezed by latitude). */
private class Fit(lat: DoubleArray, lon: DoubleArray, val box: RectF) {
    private val k = cos(Math.toRadians(lat.average()))
    private val x0: Double; private val y0: Double; private val scale: Double; private val ox: Float; private val oy: Float
    init {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in lat.indices) { val x = lon[i] * k; val y = -lat[i]; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
        val w = max(maxX - minX, 1e-6); val h = max(maxY - minY, 1e-6)
        scale = min(box.width() / w, box.height() / h)
        x0 = minX; y0 = minY
        ox = box.left + ((box.width() - w * scale) / 2).toFloat(); oy = box.top + ((box.height() - h * scale) / 2).toFloat()
    }
    fun x(la: Double, lo: Double) = ox + ((lo * k - x0) * scale).toFloat()
    fun y(la: Double) = oy + ((-la - y0) * scale).toFloat()
}

private fun pctColor(p: Double) = when { p < 10 -> RED; p < 20 -> ORANGE; else -> LIME }

private fun dur(m: Double): String { val t = m.roundToInt().coerceAtLeast(0); return if (t >= 60) "${t / 60} h ${t % 60} min" else "$t min" }

private fun clock(ms: Long): String = java.text.SimpleDateFormat(TimeFmt.pattern("HH:mm"), L10n.locale).format(java.util.Date(ms))

/** A small picture of a route's shape, for the list of saved routes. */
internal fun routeThumb(r: SavedRoutes.Saved, size: Int = 256): CarIcon {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val pad = size * 0.14f
    val fit = Fit(r.lat, r.lon, RectF(pad, pad, size - pad, size - pad))
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = RAISED }
    c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, p)
    val path = Path()
    for (i in r.lat.indices) { val x = fit.x(r.lat[i], r.lon[i]); val y = fit.y(r.lat[i]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.045f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = LIME
    c.drawPath(path, p)
    p.style = Paint.Style.FILL
    for (s in r.stops) { p.color = BG; c.drawCircle(fit.x(s.lat, s.lon), fit.y(s.lat), size * 0.06f, p); p.color = LIME; c.drawCircle(fit.x(s.lat, s.lon), fit.y(s.lat), size * 0.035f, p) }
    p.color = ORANGE; c.drawCircle(fit.x(r.lat[0], r.lon[0]), fit.y(r.lat[0]), size * 0.045f, p)
    p.color = TEXT; c.drawCircle(fit.x(r.lat.last(), r.lon.last()), fit.y(r.lat.last()), size * 0.05f, p)
    return CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
}

/** Saved routes as tiles, each with a picture of the route. */
class SavedRoutesScreen(ctx: CarContext) : Screen(ctx) {
    init {
        // Trips saved or deleted on the phone show up here at once.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { SavedRoutes.changes.collect { invalidate() } } }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val saved = SavedRoutes.all(carContext)
        if (saved.isEmpty()) return MessageTemplate.Builder(tr("Plan a trip on your phone and tap Save trip.")).setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).build()
        val list = ItemList.Builder()
        for (r in saved) {
            val stops = when (r.stops.size) { 0 -> "no charging stops"; 1 -> "1 charging stop"; else -> "${r.stops.size} charging stops" }
            list.addItem(GridItem.Builder().setTitle(r.name.ifBlank { "—" })
                .setText(fmtDistance(r.totalKm, s) + " · " + tr(stops))
                .setImage(routeThumb(r), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(RouteLiveScreen(carContext, r.id)) }.build())
        }
        return GridTemplate.Builder().setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/**
 * One saved route, drawn on the car screen: a card with what is left and the battery, the route itself with the car on it,
 * and a timeline of the stops. The car is asked for a fresh status every 30 seconds while this is showing.
 */
class RouteLiveScreen(ctx: CarContext, private val id: Long) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var lastFetch = 0L

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { lastFetch = System.currentTimeMillis(); CarHub.refresh(carContext, force = true); delay(30_000L) } }
                launch { CarHub.status.collect { main.post { draw() } } }
                // Deleted on the phone: leave.
                launch { SavedRoutes.changes.collect { if (SavedRoutes.all(carContext).none { it.id == id }) main.post { try { screenManager.pop() } catch (_: Exception) { } } } }
                launch { while (true) { delay(5_000L); main.post { draw() } } }   // keeps "updated … ago" current
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    override fun onDestroy(owner: LifecycleOwner) { try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { } }

    override fun onSurfaceAvailable(c: SurfaceContainer) { surface = c; main.post { draw() } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { draw() } }
    override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) { visible = Rect(stableArea); main.post { draw() } } }

    override fun onGetTemplate(): Template {
        val r = SavedRoutes.all(carContext).firstOrNull { it.id == id }
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_back)).setOnClickListener { screenManager.pop() }.build())
        if (r != null) {
            strip.addAction(Action.Builder().setTitle(tr("Navigate")).setOnClickListener {
                val st = CarHub.status.value
                val live = SavedRoutes.live(carContext, r, st?.lat, st?.lon, st?.batteryPct)
                val n = live.nextStop
                val here = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                if (n != null) openInGoogleMaps(carContext, n.lat, n.lon, n.name, stillHere = here)
                else openInGoogleMaps(carContext, r.lat.last(), r.lon.last(), r.name, stillHere = here)
            }.build())
            strip.addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener {
                SavedRoutes.resetProgress(carContext, id); CarHub.refresh(carContext, force = true); CarHub.say("Updating…")
            }.build())
        }
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    // ---------------- drawing ----------------

    private fun draw() {
        val sc = surface ?: return
        val sf = sc.surface ?: return
        if (!sf.isValid) return
        val canvas = try { sf.lockHardwareCanvas() } catch (_: Exception) { try { sf.lockCanvas(null) } catch (_: Exception) { return } }
        try { paint(canvas, sc) } catch (e: Exception) {
            android.util.Log.e("LionFlowCar", "route draw failed", e)
        } finally { try { sf.unlockCanvasAndPost(canvas) } catch (_: Exception) { } }
    }

    private fun paint(c: Canvas, sc: SurfaceContainer) {
        val d = sc.dpi / 160f
        fun dp(v: Float) = v * d
        val w = sc.width.toFloat(); val h = sc.height.toFloat()
        c.drawColor(BG)
        // The whole screen is used; the car's buttons float on top of the route panel (top right).
        val area = RectF(dp(12f), dp(12f), w - dp(12f), h - dp(12f))
        val buttonsBottom = visible?.top?.toFloat() ?: 0f
        val s = CarHub.app(carContext).settings.value
        val r = SavedRoutes.all(carContext).firstOrNull { it.id == id }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (r == null) { text.textSize = dp(20f); c.drawText(tr("This route was deleted."), area.left, area.centerY(), text); return }
        val st = CarHub.status.value
        val live = SavedRoutes.live(carContext, r, st?.lat, st?.lon, st?.batteryPct)
        val now = System.currentTimeMillis()

        // Card on the left, the route on the right (or the card on top on a tall screen).
        val wide = area.width() > area.height() * 1.15f
        val card = if (wide) RectF(area.left, area.top, area.left + min(dp(330f), area.width() * 0.45f), area.bottom)
        else RectF(area.left, area.top, area.right, area.top + min(dp(300f), area.height() * 0.55f))
        val mapBox = if (wide) RectF(card.right + dp(16f), area.top, area.right, area.bottom) else RectF(area.left, card.bottom + dp(12f), area.right, area.bottom)

        // ---- card
        p.style = Paint.Style.FILL; p.color = CARD
        c.drawRoundRect(card, dp(24f), dp(24f), p)
        val L = card.left + dp(20f); var y = card.top + dp(32f)
        val cw = card.width() - dp(40f)
        text.typeface = bold; text.textSize = dp(15f); text.color = MUTED
        c.drawText(ellipsize(r.name, text, cw), L, y, text)
        y += dp(44f)
        text.textSize = dp(40f); text.color = TEXT
        val big = fmtDistance(live.leftKm, s)
        c.drawText(big, L, y, text)
        val bigW = text.measureText(big)
        text.typeface = Typeface.DEFAULT; text.textSize = dp(16f); text.color = MUTED
        c.drawText(tr("left"), L + bigW + dp(8f), y, text)
        y += dp(26f)
        live.minLeft?.let { m ->
            text.textSize = dp(15f); text.color = TEXT
            c.drawText(ellipsize(tr("≈ {0} · arrive {1}").replace("{0}", dur(m)).replace("{1}", clock(now + (m * 60_000).toLong())), text, cw), L, y, text)
            y += dp(22f)
        }

        // battery: ring with the % expected at the next stop (or the end), and the % now
        y += dp(12f)
        val ringR = dp(38f)
        val cx = L + ringR; val cy = y + ringR
        val pct = live.pctAtNext
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(8f); p.strokeCap = Paint.Cap.ROUND; p.color = RAISED
        c.drawCircle(cx, cy, ringR, p)
        if (pct != null) {
            p.color = pctColor(pct)
            c.drawArc(RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR), -90f, (pct.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, p)
        }
        text.typeface = bold; text.textSize = dp(22f); text.color = TEXT; text.textAlign = Paint.Align.CENTER
        c.drawText(pct?.let { "${it.coerceAtLeast(0.0).roundToInt()}%" } ?: "—", cx, cy + dp(8f), text)
        text.textAlign = Paint.Align.LEFT
        val tx = cx + ringR + dp(16f); val tw = card.right - dp(20f) - tx
        text.typeface = Typeface.DEFAULT; text.textSize = dp(14f); text.color = MUTED
        c.drawText(ellipsize(tr(if (live.nextStop != null) "Expected at the next stop" else "Expected at the end"), text, tw), tx, cy - dp(8f), text)
        text.textSize = dp(16f); text.color = TEXT
        c.drawText(ellipsize(tr("Battery now {0}").replace("{0}", st?.batteryPct?.let { "${it.roundToInt()} %" } ?: "—"), text, tw), tx, cy + dp(16f), text)
        y = cy + ringR + dp(26f)

        // next charging stop
        p.style = Paint.Style.FILL; p.color = RAISED
        val boxH = dp(66f)
        if (y + boxH < card.bottom - dp(30f)) {
            val box = RectF(L, y, card.right - dp(20f), y + boxH)
            c.drawRoundRect(box, dp(16f), dp(16f), p)
            val n = live.nextStop
            boltBadge(c, box.left + dp(26f), box.centerY(), dp(15f), if (n != null) LIME else DRIVEN)
            val bx = box.left + dp(52f); val bw = box.right - dp(12f) - bx
            text.typeface = bold; text.textSize = dp(16f); text.color = TEXT
            c.drawText(ellipsize(if (n != null) n.name else tr("No charging stops left"), text, bw), bx, box.centerY() - dp(4f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = dp(14f); text.color = MUTED
            val sub = if (n != null) tr("In {0}").replace("{0}", fmtDistance(live.toNextKm ?: 0.0, s)) + " · " +
                tr(if (live.stopsLeft == 1) "1 charging stop left" else "{0} charging stops left").replace("{0}", "${live.stopsLeft}")
            else tr("Straight to the destination")
            c.drawText(ellipsize(sub, text, bw), bx, box.centerY() + dp(18f), text)
            y += boxH
        }

        // footer: how fresh the car's numbers are, or that the car is off the route
        text.textSize = dp(13f)
        val foot = if (st == null) tr("Getting the car's status…")
        else if (live.offRouteKm >= 3.0) tr("The car is {0} from the route").replace("{0}", fmtDistance(live.offRouteKm, s))
        else tr(CarHub.updatedLine())
        text.color = if (st != null && live.offRouteKm >= 3.0) ORANGE else MUTED
        c.drawText(ellipsize(foot, text, cw), L, card.bottom - dp(18f), text)

        // ---- the route
        val tlH = dp(64f)
        // The route itself stays clear of the floating buttons; the panel behind it doesn't.
        val routeBox = RectF(mapBox.left + dp(18f), max(mapBox.top + dp(18f), buttonsBottom + dp(6f)), mapBox.right - dp(18f), mapBox.bottom - tlH - dp(18f))
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, mapBox.top, 0f, mapBox.bottom, 0xFF131518.toInt(), BG, Shader.TileMode.CLAMP)
        c.drawRoundRect(mapBox, dp(24f), dp(24f), p)
        p.shader = null
        if (routeBox.height() > dp(60f) && routeBox.width() > dp(60f)) {
            val fit = Fit(r.lat, r.lon, routeBox)
            fun path(from: Int, to: Int): Path { val pa = Path(); for (i in from..to) { val x = fit.x(r.lat[i], r.lon[i]); val yy = fit.y(r.lat[i]); if (i == from) pa.moveTo(x, yy) else pa.lineTo(x, yy) }; return pa }
            val at = live.at.coerceIn(0, r.lat.size - 1)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            if (at > 0) { p.color = DRIVEN; p.strokeWidth = dp(5f); c.drawPath(path(0, at), p) }
            if (at < r.lat.size - 1) {
                val ahead = path(at, r.lat.size - 1)
                p.color = 0x55C6F36B; p.strokeWidth = dp(14f); p.maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL); c.drawPath(ahead, p)
                p.maskFilter = null; p.color = LIME; p.strokeWidth = dp(6f); c.drawPath(ahead, p)
            }
            p.style = Paint.Style.FILL
            // start and destination
            p.color = DRIVEN; c.drawCircle(fit.x(r.lat[0], r.lon[0]), fit.y(r.lat[0]), dp(6f), p)
            val ex = fit.x(r.lat.last(), r.lon.last()); val ey = fit.y(r.lat.last())
            p.color = TEXT; c.drawCircle(ex, ey, dp(11f), p); p.color = BG; c.drawCircle(ex, ey, dp(4.5f), p)
            // charging stops: the next one with the expected %, later ones with the planned %
            for (stp in r.stops) {
                val sx = fit.x(stp.lat, stp.lon); val sy = fit.y(stp.lat)
                val passed = stp.idx <= at
                boltBadge(c, sx, sy, dp(13f), if (passed) DRIVEN else LIME)
                val label = when {
                    passed -> null
                    stp === live.nextStop -> live.pctAtNext?.let { "${it.coerceAtLeast(0.0).roundToInt()}%" }
                    stp.arrivePct > 0 -> "≈${stp.arrivePct.roundToInt()}%"
                    else -> null
                }
                if (label != null) pill(c, label, sx, sy - dp(24f), dp(13f), if (stp === live.nextStop) pctColor(live.pctAtNext ?: 50.0) else RAISED, if (stp === live.nextStop) BG else TEXT, d)
            }
            live.pctAtEnd?.let { pill(c, "${it.coerceAtLeast(0.0).roundToInt()}%", ex, ey - dp(26f), dp(13f), pctColor(it), BG, d) }
            // the car
            if (st?.lat != null && st.lon != null && live.offRouteKm < 3.0) {
                val carX = fit.x(r.lat[at], r.lon[at]); val carY = fit.y(r.lat[at])
                p.color = 0x55FFB547; c.drawCircle(carX, carY, dp(20f), p)
                p.color = TEXT; c.drawCircle(carX, carY, dp(11f), p)
                p.color = ORANGE; c.drawCircle(carX, carY, dp(8f), p)
            }
        }

        // ---- timeline: start → stops → end, with the car on it
        val tl = RectF(mapBox.left + dp(28f), mapBox.bottom - tlH, mapBox.right - dp(28f), mapBox.bottom - dp(18f))
        val ly = tl.top + dp(14f)
        val total = r.totalKm.coerceAtLeast(0.1)
        fun xAt(km: Double) = tl.left + (km / total).toFloat().coerceIn(0f, 1f) * tl.width()
        val doneKm = r.km[live.at.coerceIn(0, r.km.size - 1)]
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = dp(6f)
        p.color = RAISED; c.drawLine(tl.left, ly, tl.right, ly, p)
        p.color = LIME; if (doneKm > 0) c.drawLine(tl.left, ly, xAt(doneKm), ly, p)
        p.style = Paint.Style.FILL
        for (stp in r.stops) boltBadge(c, xAt(stp.km), ly, dp(10f), if (stp.idx <= live.at) DRIVEN else LIME)
        p.color = TEXT; c.drawCircle(tl.right, ly, dp(7f), p)
        p.color = ORANGE; c.drawCircle(xAt(doneKm), ly, dp(8f), p)
        text.typeface = Typeface.DEFAULT; text.textSize = dp(12f); text.color = MUTED
        c.drawText("0", tl.left, ly + dp(26f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(fmtDistance(r.totalKm, s), tl.right, ly + dp(26f), text)
        text.textAlign = Paint.Align.LEFT
    }

    /** A round badge with a lightning bolt. */
    private fun boltBadge(c: Canvas, x: Float, y: Float, r: Float, fill: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = BG; c.drawCircle(x, y, r + r * 0.28f, p)
        p.color = fill; c.drawCircle(x, y, r, p)
        val b = Path()
        val u = r / 10f
        b.moveTo(x + 1.5f * u, y - 6.5f * u); b.lineTo(x - 4f * u, y + 1f * u); b.lineTo(x - 0.2f * u, y + 1f * u)
        b.lineTo(x - 1.5f * u, y + 6.5f * u); b.lineTo(x + 4f * u, y - 1f * u); b.lineTo(x + 0.2f * u, y - 1f * u); b.close()
        p.color = BG; c.drawPath(b, p)
    }

    /** A rounded label centred on (x, y). */
    private fun pill(c: Canvas, label: String, x: Float, y: Float, size: Float, bg: Int, fg: Int, d: Float) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); color = fg; textAlign = Paint.Align.CENTER }
        val w = t.measureText(label) + 16f * d; val h = size + 10f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), h / 2, h / 2, p)
        c.drawText(label, x, y + size * 0.35f, t)
    }

    private fun ellipsize(s: String, p: Paint, width: Float): String {
        if (width <= 0f || p.measureText(s) <= width) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > width) e = e.dropLast(1)
        return "$e…"
    }
}
