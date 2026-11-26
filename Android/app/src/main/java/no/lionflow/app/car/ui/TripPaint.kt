package no.lionflow.app.car.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Web-Mercator view of a map area: the map picture and what is drawn on it use the same. */
class MapView(val box: RectF, val west: Double, val east: Double, val north: Double, val south: Double) {
    private val x0 = mx(west); private val x1 = mx(east); private val y0 = my(north); private val y1 = my(south)
    fun x(lon: Double): Float = box.left + ((mx(lon) - x0) / (x1 - x0) * box.width()).toFloat()
    fun y(lat: Double): Float = box.top + ((my(lat) - y0) / (y1 - y0) * box.height()).toFloat()

    companion object {
        fun mx(lon: Double) = (lon + 180.0) / 360.0
        fun my(lat: Double): Double { val r = Math.toRadians(lat.coerceIn(-85.0, 85.0)); return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 }
        fun lon(x: Double) = x * 360.0 - 180.0
        fun lat(y: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))))

        /** The map area for [box] in which the route's own extent fills [inner] (a part of [box]). */
        fun fitting(lat: DoubleArray, lon: DoubleArray, box: RectF, inner: RectF): MapView {
            var ax = Double.MAX_VALUE; var bx = -Double.MAX_VALUE; var ay = Double.MAX_VALUE; var by = -Double.MAX_VALUE
            for (i in 0 until min(lat.size, lon.size)) { val x = mx(lon[i]); val y = my(lat[i]); ax = min(ax, x); bx = max(bx, x); ay = min(ay, y); by = max(by, y) }
            if (ax > bx) { ax = 0.5; bx = 0.5; ay = 0.3; by = 0.3 }
            // Some room around a short route, so the map shows the area too.
            val least = 0.00006
            if (bx - ax < least) { val m = (ax + bx) / 2; ax = m - least / 2; bx = m + least / 2 }
            if (by - ay < least) { val m = (ay + by) / 2; ay = m - least / 2; by = m + least / 2 }
            val iw = max(inner.width(), 1f); val ih = max(inner.height(), 1f)
            val sc = min(iw / (bx - ax), ih / (by - ay))   // screen px per mercator unit
            val ox = inner.left + (iw - (bx - ax) * sc) / 2; val oy = inner.top + (ih - (by - ay) * sc) / 2
            val west = ax - (ox - box.left) / sc; val east = west + box.width() / sc
            val north = ay - (oy - box.top) / sc; val south = north + box.height() / sc
            return MapView(box, lon(west), lon(east), lat(north), lat(south))
        }
    }
}

/**
 * A trip on the car screen: what is left and the battery now and at the next stop, on a card; the route on a map
 * with the charging stops and the car; and a line showing how far along the car is.
 */
class TripModel(
    val title: String,
    val status: String,
    val statusTone: Int,
    /** [READY], or [WAIT] / [FAILED] with [message] (and [action], the label of a button to try again). */
    val state: Int,
    val message: String = "",
    val action: String? = null,
    /** 0–1, moves the waiting mark round. */
    val phase: Float = 0f,
    /** "123 km" and "left". */
    val big: String = "", val bigUnit: String = "",
    /** "≈ 1 h 25 min · arrive 14:32" */
    val eta: String = "",
    val nowPct: Double? = null, val nowLabel: String = "",
    val thenPct: Double? = null, val thenLabel: String = "",
    val stopTitle: String = "", val stopSub: String = "", val stopLive: Boolean = false,
    /** A line at the bottom of the card: the car is off the route, the trip can't be made, … */
    val note: String? = null, val noteTone: Int = Tone.MUTED,
    val lat: DoubleArray = DoubleArray(0), val lon: DoubleArray = DoubleArray(0), val km: DoubleArray = DoubleArray(0),
    /** The point of the route the car is at, and whether to mark the car there. */
    val at: Int = 0, val showCar: Boolean = false,
    val stops: List<Stop> = emptyList(),
    val endPct: Double? = null, val destName: String = "", val totalLabel: String = "",
    val map: Bitmap? = null, val credit: String = "",
    /** How fast the car goes now (whole km/h or mph), when known. */
    val speed: Int? = null, val speedUnit: String = "km/h",
) {
    class Stop(val lat: Double, val lon: Double, val km: Double, val passed: Boolean, val next: Boolean, val pct: Double?, val about: Boolean)
    companion object {
        const val READY = 0
        const val WAIT = 1
        const val FAILED = 2
    }
}

/** Where the parts of the trip screen go: the card, the map, and the part of the map the route is fitted into. */
class TripLayout(val card: RectF, val map: RectF, val route: RectF, val panel: RectF, val sideways: Boolean)

object TripPaint {
    const val RETRY = "retry"

    fun layout(k: Kit): TripLayout {
        val u = k.u
        val body = k.st.body
        val gap = 2.2f * u
        // The card to the left of the map; on a tall screen above it, laid out sideways.
        val wide = body.width() >= body.height() * 1.15f
        val card: RectF; val map: RectF
        if (wide) {
            // The full height: the title and back button go into the card, and Android Auto's buttons float over the
            // top of the map.
            val top = 2.6f * u
            val cw = (body.width() * 0.36f).coerceIn(56f * u, 74f * u)
            card = RectF(body.left, top, body.left + cw, body.bottom)
            map = RectF(card.right + gap, top, body.right, body.bottom)
        } else {
            card = RectF(body.left, body.top, body.right, body.top + 36f * u)
            map = RectF(body.left, card.bottom + gap, body.right, body.bottom)
        }
        val panel = RectF(map.left + 2.2f * u, map.bottom - 13.6f * u, map.right - 2.2f * u, map.bottom - 2.2f * u)
        // The route keeps clear of the edges (labels sit above and below its marks) and of the line at the bottom.
        // (and below the band where Android Auto's buttons sit).
        val route = RectF(map.left + 9f * u, max(map.top + 11f * u, k.st.header.bottom + 5f * u), map.right - 9f * u, panel.top - 8.5f * u)
        if (route.width() < 10f * u) { route.left = map.left + 3f * u; route.right = map.right - 3f * u }
        if (route.height() < 10f * u) { route.top = map.top + 4f * u; route.bottom = max(route.top + 2f * u, panel.top - 3f * u) }
        return TripLayout(card, map, route, panel, !wide)
    }

    /** The map area that goes with [l] for a route: ask for a map picture of exactly this. */
    fun view(l: TripLayout, lat: DoubleArray, lon: DoubleArray): MapView = MapView.fitting(lat, lon, l.map, l.route)

    fun draw(c: Canvas, k: Kit, m: TripModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        c.drawColor(pal.bg)
        val body = k.st.body
        val ready = m.state == TripModel.READY && m.lat.size >= 2
        val l = if (ready && body.width() >= 20f * u && body.height() >= 14f * u) layout(k) else null
        // Laid out side by side, the title is in the card; otherwise in the band at the top.
        if (l == null || l.sideways) k.header(c, m.title, m.status, true, pressed, hits, m.statusTone)
        if (body.width() < 20f * u || body.height() < 14f * u) return
        if (l == null) { waiting(c, k, body, m, pressed, hits); return }
        if (l.sideways) cardSideways(c, k, l.card, m) else card(c, k, l.card, m, pressed, hits)
        map(c, k, l, m)
    }

    // ---------------- waiting, or failed ----------------

    private fun waiting(c: Canvas, k: Kit, body: RectF, m: TripModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        k.card(c, body, 3.6f * u)
        val failed = m.state == TripModel.FAILED
        val p = k.text(4.6f, if (failed) pal.text else pal.muted, Kit.medium, Paint.Align.CENTER)
        val lines = k.wrap(m.message, p, min(body.width() - 10f * u, 120f * u), 4)
        val markH = if (failed) 0f else 17f * u
        val btnH = if (failed && m.action != null) 17f * u else 0f
        var y = body.centerY() - (markH + lines.size * 6.4f * u + btnH) / 2f
        if (!failed) {
            // A ring with a part going round.
            val r = 5.4f * u; val cy = y + r + 1f * u
            c.drawCircle(body.centerX(), cy, r, k.stroke(pal.raised, 1.3f * u))
            c.drawArc(RectF(body.centerX() - r, cy - r, body.centerX() + r, cy + r), m.phase * 360f - 90f, 95f, false, k.stroke(pal.accentInk, 1.3f * u))
            y += markH
        }
        for (ln in lines) { c.drawText(ln, body.centerX(), y + 4.2f * u, p); y += 6.4f * u }
        val label = m.action
        if (failed && label != null) {
            val bp = k.text(4.4f, pal.onAccent, Kit.bold, Paint.Align.CENTER)
            val w = bp.measureText(label) + 12f * u; val h = 11f * u
            val r = RectF(body.centerX() - w / 2f, y + 5f * u, body.centerX() + w / 2f, y + 5f * u + h)
            c.drawRoundRect(r, h / 2f, h / 2f, k.fill(if (pressed == RETRY) k.pressedFill(pal.accent) else pal.accent))
            c.drawText(label, r.centerX(), k.mid(bp, r.centerY()), bp)
            hits += Hit(RectF(r.left - 3f * u, r.top - 3f * u, r.right + 3f * u, r.bottom + 3f * u), RETRY)
        }
    }

    // ---------------- the card ----------------

    private fun bigLine(c: Canvas, k: Kit, m: TripModel, x: Float, base: Float, room: Float) {
        val bp = k.text(9.6f, k.pal.text, Kit.bold)
        val up = k.text(4f, k.pal.muted, Kit.medium)
        val unitW = if (m.bigUnit.isEmpty()) 0f else up.measureText(m.bigUnit) + 1.8f * k.u
        val big = k.fit(m.big, bp, room - unitW, 0.6f)
        c.drawText(big, x, base, bp)
        if (m.bigUnit.isNotEmpty()) c.drawText(m.bigUnit, x + bp.measureText(big) + 1.8f * k.u, base, up)
    }

    /** One battery ring with the level in it and a label under it. */
    private fun levelRing(c: Canvas, k: Kit, cx: Float, cy: Float, r: Float, pct: Double?, color: Int, label: String, labelRoom: Float) {
        val u = k.u
        k.ring(c, cx, cy, r, r * 0.2f, pct, color)
        val tp = k.text(0f, k.pal.text, Kit.bold, Paint.Align.CENTER).apply { textSize = r * 0.62f }
        c.drawText(pct?.let { "${it.coerceIn(0.0, 100.0).roundToInt()}%" } ?: "–", cx, k.mid(tp, cy), tp)
        val lp = k.text(3.5f, k.pal.muted, Kit.medium, Paint.Align.CENTER)
        c.drawText(k.fit(label, lp, labelRoom, 0.8f), cx, cy + r * 1.1f + 4.6f * u, lp)
    }

    /** The two rings, now and then, with an arrow from one to the other. [cy] is the middle of the rings. */
    private fun rings(c: Canvas, k: Kit, m: TripModel, left: Float, right: Float, cy: Float, r: Float) {
        val u = k.u; val pal = k.pal
        val w = right - left
        val c1 = left + w * 0.25f; val c2 = left + w * 0.75f
        levelRing(c, k, c1, cy, r, m.nowPct, pal.muted, m.nowLabel, w * 0.5f - 1f * u)
        val then = m.thenPct
        levelRing(c, k, c2, cy, r, then, if (then != null) k.level(then) else pal.raised, m.thenLabel, w * 0.5f - 1f * u)
        val a0 = c1 + r * 1.1f + 2f * u; val a1 = c2 - r * 1.1f - 2f * u
        if (a1 - a0 >= 5f * u) c.drawLine(a0, cy, a1 - 0.6f * u, cy, k.stroke(pal.muted, 0.5f * u))
        k.chevron(c, min(a1, (c1 + c2) / 2f + max(0f, (a1 - a0) / 2f)) - 0.9f * u, cy, 3f * u, 0, pal.muted, 0.5f * u)
    }

    private fun stopBox(c: Canvas, k: Kit, m: TripModel, box: RectF) {
        val u = k.u; val pal = k.pal
        c.drawRoundRect(box, 2.6f * u, 2.6f * u, k.fill(pal.raised))
        val br = min(3.5f * u, box.height() * 0.28f)
        k.bolt(c, box.left + 2.6f * u + br, box.centerY(), br, if (m.stopLive) pal.accent else pal.driven, if (m.stopLive) pal.onAccent else pal.raised, pal.raised)
        val tx = box.left + 2.6f * u + br * 2 + 2.6f * u; val room = box.right - 2.4f * u - tx
        val tp = k.text(4.2f, pal.text, Kit.bold); val sp = k.text(3.4f, pal.muted, Kit.medium)
        if (m.stopSub.isEmpty()) c.drawText(k.fit(m.stopTitle, tp, room, 0.78f), tx, k.mid(tp, box.centerY()), tp)
        else {
            c.drawText(k.fit(m.stopTitle, tp, room, 0.78f), tx, box.centerY() - 0.5f * u, tp)
            c.drawText(k.fit(m.stopSub, sp, room, 0.82f), tx, box.centerY() + 4.2f * u, sp)
        }
    }

    private fun card(c: Canvas, k: Kit, whole: RectF, m: TripModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        k.card(c, whole, 3.6f * u)
        // At the top of the card: back, the destination and how fresh the car's numbers are.
        val br = 4.4f * u
        val bx = whole.left + 3f * u + br; val by = whole.top + 3f * u + br
        c.drawCircle(bx, by, br, k.fill(if (pressed == "back") k.pressedFill(pal.raised) else pal.raised))
        k.chevron(c, bx - 0.12f * br, by, br * 0.92f, 1, pal.text, max(2f, 0.6f * u))
        hits += Hit(RectF(whole.left, whole.top, bx + br + 3f * u, by + br + 3f * u), "back")
        val tx = bx + br + 2.6f * u; val titleRoom = whole.right - 3.2f * u - tx
        val tp = k.text(5.4f, pal.text, Kit.bold)
        c.drawText(k.fit(m.title, tp, titleRoom, 0.72f), tx, by + 0.4f * u, tp)
        if (m.status.isNotBlank()) {
            val sp = k.text(3.3f, k.tone(m.statusTone), Kit.medium)
            c.drawText(k.ellipsize(m.status, sp, titleRoom), tx, by + 4.9f * u, sp)
        }
        val r = RectF(whole.left, by + br + 1.2f * u, whole.right, whole.bottom)
        val pad = 3.6f * u
        val left = r.left + pad; val right = r.right - pad
        val hasEta = m.eta.isNotEmpty()
        // The note (the car is off the route, the trip can't be made) may take two lines.
        val np = k.text(3.5f, k.tone(m.noteTone), Kit.medium)
        val noteLines = m.note?.takeIf { it.isNotBlank() }?.let { k.wrap(it, np, right - left, 2) } ?: emptyList()
        val hasNote = noteLines.isNotEmpty()
        var ringR = 8.6f * u
        val hA = 6.9f * u + (if (hasEta) 6.2f * u else 0f)
        var hB = ringR * 2.2f + 7.6f * u
        var hC = 15f * u
        val hD = if (hasNote) 3.4f * u + (noteLines.size - 1) * 4.6f * u else 0f
        val room = r.height() - pad * 2f
        val parts = if (hasNote) 4 else 3
        // When the card is low, smaller rings and a lower stop box before anything is left out.
        if (hA + hB + hC + hD + (parts - 1) * 2.4f * u > room) { ringR = 7f * u; hB = ringR * 2.2f + 7.6f * u; hC = 12.5f * u }
        val showStop = hA + hB + hC + hD + (parts - 1) * 2.2f * u <= room
        val used = hA + hB + (if (showStop) hC else 0f) + hD
        val n = parts - (if (showStop) 0 else 1)
        val gapY = ((room - used) / (n - 1).coerceAtLeast(1)).coerceIn(1.6f * u, 9f * u)
        var y = r.top + pad + ((room - used - gapY * (n - 1)) / 2f).coerceAtLeast(0f)

        bigLine(c, k, m, left, y + 6.9f * u, right - left)
        if (hasEta) { val ep = k.text(4f, pal.text, Kit.medium); c.drawText(k.fit(m.eta, ep, right - left, 0.76f), left, y + 6.9f * u + 6f * u, ep) }
        y += hA + gapY
        rings(c, k, m, left, right, y + ringR * 1.1f, ringR)
        y += hB + gapY
        if (showStop) { stopBox(c, k, m, RectF(left, y, right, y + hC)); y += hC + gapY }
        noteLines.forEachIndexed { i, line -> c.drawText(line, left, y + 2.8f * u + i * 4.6f * u, np) }
    }

    /** The same card laid out in a row, for a tall screen where it sits above the map. */
    private fun cardSideways(c: Canvas, k: Kit, r: RectF, m: TripModel) {
        val u = k.u; val pal = k.pal
        k.card(c, r, 3.6f * u)
        val pad = 3.6f * u
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val w = inner.width()
        val aW = w * 0.30f; val bW = w * 0.34f
        val hasNote = !m.note.isNullOrEmpty()
        val cy = inner.centerY()
        bigLine(c, k, m, inner.left, cy - (if (hasNote) 3.4f * u else 0.6f * u), aW - 2f * u)
        val ep = k.text(3.8f, pal.text, Kit.medium)
        if (m.eta.isNotEmpty()) c.drawText(k.fit(m.eta, ep, aW - 2f * u, 0.7f), inner.left, cy + (if (hasNote) 2.8f * u else 5.6f * u), ep)
        if (hasNote) { val np = k.text(3.3f, k.tone(m.noteTone), Kit.medium); c.drawText(k.fit(m.note ?: "", np, aW - 2f * u, 0.7f), inner.left, cy + 8.6f * u, np) }
        val ringR = min(8.2f * u, (inner.height() - 7.6f * u) / 2.2f)
        rings(c, k, m, inner.left + aW, inner.left + aW + bW, inner.top + ringR * 1.1f + ((inner.height() - ringR * 2.2f - 7.6f * u) / 2f).coerceAtLeast(0f), ringR)
        val box = RectF(inner.left + aW + bW + 2f * u, cy - 8f * u, inner.right, cy + 8f * u)
        if (box.width() > 30f * u) stopBox(c, k, m, box)
    }

    /** The speed in a round dial: the number large, the unit under it, and a thin arc that fills up to 130 km/h. */
    private fun speedDial(c: Canvas, k: Kit, cx: Float, cy: Float, r: Float, speed: Int, unit: String) {
        val u = k.u; val pal = k.pal
        c.drawCircle(cx, cy, r + 0.5f * u, k.fill(Kit.alpha(pal.bg, 0.55f)))
        c.drawCircle(cx, cy, r, k.fill(Kit.alpha(pal.card, 0.96f)))
        // A gauge open at the bottom, like a speedometer.
        val top = if (unit == "mph") 80f else 130f
        val a = r - 1.1f * u
        val oval = RectF(cx - a, cy - a, cx + a, cy + a)
        c.drawArc(oval, 135f, 270f, false, k.stroke(Kit.alpha(pal.text, 0.12f), 0.6f * u))
        val sweep = 270f * (speed / top).coerceIn(0f, 1f)
        if (sweep > 1f) c.drawArc(oval, 135f, sweep, false, k.stroke(pal.accentInk, 0.6f * u))
        val np = k.text(0f, pal.text, Kit.bold, Paint.Align.CENTER).apply { textSize = r * (if (speed >= 100) 0.66f else 0.76f) }
        c.drawText("$speed", cx, cy + r * 0.16f, np)
        val up = k.text(0f, pal.muted, Kit.medium, Paint.Align.CENTER).apply { textSize = r * 0.27f }
        c.drawText(unit, cx, cy + r * 0.52f, up)
    }

    // ---------------- the map ----------------

    private fun map(c: Canvas, k: Kit, l: TripLayout, m: TripModel) {
        val u = k.u; val pal = k.pal
        val box = l.map
        val rad = 3.6f * u
        val view = view(l, m.lat, m.lon)
        val n = min(m.lat.size, m.lon.size)

        c.save()
        c.clipPath(Path().apply { addRoundRect(box, rad, rad, Path.Direction.CW) })
        val pic = m.map
        if (pic != null) {
            c.drawBitmap(pic, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            // A light veil, so the route and the stops stand out from the map.
            c.drawRect(box, k.fill(Kit.alpha(pal.bg, if (pal.dark) 0.20f else 0.10f)))
        } else {
            // No map picture (yet): a quiet panel with a faint dot grid.
            c.drawRect(box, k.fill(pal.card))
            val dot = k.fill(Kit.alpha(pal.text, if (pal.dark) 0.07f else 0.10f))
            val step = 4.6f * u
            var gy = box.top + step
            while (gy < box.bottom - u) { var gx = box.left + step; while (gx < box.right - u) { c.drawCircle(gx, gy, max(1f, 0.2f * u), dot); gx += step }; gy += step }
        }

        // The route: what is driven in grey, what is ahead in green on a soft band.
        val at = m.at.coerceIn(0, n - 1)
        val stepPts = max(1, n / 900)
        fun path(from: Int, to: Int): Path {
            val p = Path()
            var i = from
            p.moveTo(view.x(m.lon[from]), view.y(m.lat[from]))
            while (i < to) { i = min(to, i + stepPts); p.lineTo(view.x(m.lon[i]), view.y(m.lat[i])) }
            return p
        }
        val line = if (pal.dark) pal.accent else pal.accentInk
        if (at > 0) {
            val driven = path(0, at)
            c.drawPath(driven, k.stroke(Kit.alpha(pal.bg, 0.55f), 1.9f * u))
            c.drawPath(driven, k.stroke(pal.driven, 1.05f * u))
        }
        if (at < n - 1) {
            val ahead = path(at, n - 1)
            c.drawPath(ahead, k.stroke(Kit.alpha(if (pal.dark) pal.bg else 0xFFFFFFFF.toInt(), 0.75f), 2.3f * u))
            c.drawPath(ahead, k.stroke(line, 1.15f * u))
        }
        c.restore()
        if (pic == null) c.drawRoundRect(RectF(box.left + 0.5f, box.top + 0.5f, box.right - 0.5f, box.bottom - 0.5f), rad, rad, k.stroke(pal.line, max(1f, 0.18f * u)))

        fun keepX(x: Float, halfW: Float) = x.coerceIn(box.left + halfW + 1.2f * u, max(box.left + halfW + 1.2f * u, box.right - halfW - 1.2f * u))
        fun labelAt(text: String, x: Float, y: Float, bg: Int, fg: Int, edge: Int?) {
            val t = k.text(3.3f, fg, Kit.medium)
            val half = (t.measureText(text) + 3.3f * u * 1.1f) / 2f
            k.pill(c, text, keepX(x, half), y.coerceIn(box.top + 3.6f * u, l.panel.top - 3.6f * u), 3.3f, bg, fg, edge)
        }

        // Start and destination.
        c.drawCircle(view.x(m.lon[0]), view.y(m.lat[0]), 1.3f * u, k.fill(pal.bg))
        c.drawCircle(view.x(m.lon[0]), view.y(m.lat[0]), 0.9f * u, k.fill(pal.driven))
        val ex = view.x(m.lon[n - 1]); val ey = view.y(m.lat[n - 1])
        c.drawCircle(ex, ey, 2.7f * u, k.fill(pal.bg))
        c.drawCircle(ex, ey, 2.2f * u, k.fill(pal.text))
        c.drawCircle(ex, ey, 0.9f * u, k.fill(pal.bg))

        // Charging stops: the next one with the level expected there, later ones with the planned level.
        for (s in m.stops) {
            val sx = view.x(s.lon); val sy = view.y(s.lat)
            k.bolt(c, sx, sy, 2.5f * u, if (s.passed) pal.driven else pal.accent, if (s.passed) pal.card else pal.onAccent)
            val pct = s.pct
            if (!s.passed && pct != null) {
                val text = (if (s.about) "≈" else "") + "${pct.coerceIn(0.0, 100.0).roundToInt()}%"
                if (s.next) labelAt(text, sx, sy - 6.2f * u, k.level(pct), pal.onAccent, null)
                else labelAt(text, sx, sy - 6.2f * u, pal.card, pal.text, pal.line)
            }
        }
        m.endPct?.let { labelAt("${it.coerceIn(0.0, 100.0).roundToInt()}%", ex, ey - 6.4f * u, k.level(it), pal.onAccent, null) }
        if (m.destName.isNotEmpty()) {
            val t = k.text(3.3f, pal.text, Kit.medium)
            labelAt(k.ellipsize(m.destName, t, 34f * u), ex, ey + 6.4f * u, pal.card, pal.text, pal.line)
        }

        // The car.
        if (m.showCar) {
            val cx = view.x(m.lon[at]); val cy = view.y(m.lat[at])
            c.drawCircle(cx, cy, 4.4f * u, k.fill(Kit.alpha(pal.orange, 0.28f)))
            c.drawCircle(cx, cy, 2.5f * u, k.fill(0xFFFFFFFF.toInt()))
            c.drawCircle(cx, cy, 1.8f * u, k.fill(pal.orange))
        }

        // The speed, in a small round dial at the top left of the map.
        m.speed?.let { v -> speedDial(c, k, box.left + 2.4f * u + 7.6f * u, box.top + 2.4f * u + 7.6f * u, 7.6f * u, v, m.speedUnit) }

        // The line at the bottom: start → stops → end, with the car on it.
        val panel = l.panel
        c.drawRoundRect(panel, 3f * u, 3f * u, k.fill(Kit.alpha(pal.card, 0.94f)))
        c.drawRoundRect(panel, 3f * u, 3f * u, k.stroke(pal.line, max(1f, 0.18f * u)))
        if (pic != null && m.credit.isNotEmpty()) {
            val cp = k.text(2.5f, pal.muted, Kit.regular, Paint.Align.RIGHT)
            val w = cp.measureText(m.credit)
            c.drawRoundRect(RectF(panel.right - w - 2.4f * u, panel.top - 4.6f * u, panel.right, panel.top - 1f * u), 1.2f * u, 1.2f * u, k.fill(Kit.alpha(pal.card, 0.8f)))
            c.drawText(m.credit, panel.right - 1.2f * u, panel.top - 1.95f * u, cp)
        }
        val tl = RectF(panel.left + 4.4f * u, panel.top, panel.right - 4.4f * u, panel.bottom)
        val ly = panel.top + 4.4f * u
        val total = (if (m.km.isNotEmpty()) m.km[m.km.size - 1] else 0.0).coerceAtLeast(0.1)
        fun xAt(km: Double) = tl.left + (km / total).toFloat().coerceIn(0f, 1f) * tl.width()
        val done = if (m.km.isNotEmpty()) m.km[at.coerceIn(0, m.km.size - 1)] else 0.0
        c.drawLine(tl.left, ly, tl.right, ly, k.stroke(pal.raised, 1.1f * u))
        if (done > 0.0) c.drawLine(tl.left, ly, xAt(done), ly, k.stroke(line, 1.1f * u))
        for (s in m.stops) k.bolt(c, xAt(s.km), ly, 2f * u, if (s.passed) pal.driven else pal.accent, if (s.passed) pal.card else pal.onAccent, pal.card)
        c.drawCircle(tl.right, ly, 1.5f * u, k.fill(pal.text))
        if (m.showCar || done > 0.0) {
            c.drawCircle(xAt(done), ly, 2f * u, k.fill(0xFFFFFFFF.toInt()))
            c.drawCircle(xAt(done), ly, 1.45f * u, k.fill(pal.orange))
        }
        val sp = k.text(3.2f, pal.muted, Kit.medium)
        c.drawText("0", tl.left - 0.6f * u, panel.bottom - 1.9f * u, sp)
        sp.textAlign = Paint.Align.RIGHT
        c.drawText(m.totalLabel, tl.right + 0.6f * u, panel.bottom - 1.9f * u, sp)
    }
}
