package no.lionflow.app.car.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/** The start screen: the car's name, a battery gauge with the car's state, and the tiles. */
class HomeModel(
    val title: String,
    val status: String,
    val statusTone: Int,
    /** Null until the car's status has come. */
    val gauge: Gauge?,
    val waiting: String,
    val tiles: List<Tile>,
) {
    class Gauge(
        /** Battery (or fuel) level, 0–100. */
        val pct: Double?,
        val range: String,
        val lines: List<Line>,
    )
    class Line(val icon: Int, val text: String, val tone: Int)
    class Tile(val key: String, val title: String, val sub: String, val icon: Int, val primary: Boolean = false)
}

object HomePaint {
    /** Key of the gauge card (tapping it opens the car's details). */
    const val GAUGE = "gauge"

    fun draw(c: Canvas, k: Kit, m: HomeModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        c.drawColor(pal.bg)
        k.header(c, m.title, m.status, false, pressed, hits, m.statusTone)

        val body = k.st.body
        if (body.width() < 20f * u || body.height() < 20f * u) return
        val gap = 2.2f * u
        val wide = body.width() >= body.height() * 1.2f
        val gauge: RectF; val grid: RectF
        if (wide) {
            val gw = (body.width() * 0.28f).coerceIn(42f * u, 64f * u)
            gauge = RectF(body.left, body.top, body.left + gw, body.bottom)
            grid = RectF(gauge.right + gap, body.top, body.right, body.bottom)
        } else {
            val gh = (body.height() * 0.30f).coerceIn(34f * u, 52f * u)
            gauge = RectF(body.left, body.top, body.right, body.top + gh)
            grid = RectF(body.left, gauge.bottom + gap, body.right, body.bottom)
        }
        drawGauge(c, k, gauge, m, pressed == GAUGE)
        hits += Hit(gauge, GAUGE)
        drawTiles(c, k, grid, m.tiles, pressed, hits)
    }

    private fun drawGauge(c: Canvas, k: Kit, r: RectF, m: HomeModel, pressed: Boolean) {
        val u = k.u; val pal = k.pal
        k.card(c, r, 3.6f * u, pressed = pressed)
        val g = m.gauge
        if (g == null) { k.message(c, r, m.waiting); return }
        val pad = 3.4f * u
        val step = 6.6f * u; val ic = 4.6f * u
        // The ring beside the lines on a low, wide card; above them otherwise.
        val beside = r.width() > r.height() * 1.45f
        val ringR = if (beside) min(r.height() * 0.5f - pad - 1.2f * u, r.width() * 0.2f) else min(r.width() * 0.5f - pad - 1.6f * u, r.height() * 0.27f)
        val sw = ringR * 0.17f
        val ringBox = ringR * 2f + sw
        val lines = if (beside) g.lines else g.lines.take((((r.height() - pad * 2f - ringBox - 3f * u) / step).toInt()).coerceAtLeast(0))
        val tp = k.text(4.1f, pal.text, Kit.medium)
        val cx: Float; val cy: Float; val left: Float; var y: Float; val right: Float
        if (beside) {
            // Ring and lines together in the middle of the card.
            val textW = (lines.maxOfOrNull { tp.measureText(it.text) } ?: 0f) + ic + 2f * u
            val gapX = 4.4f * u
            val room = r.width() - pad * 2f
            val blockW = min(ringBox + gapX + textW, room)
            val x0 = r.left + pad + (room - blockW) / 2f
            cx = x0 + ringBox / 2f; cy = r.centerY()
            left = x0 + ringBox + gapX; right = r.right - pad
            y = cy - (lines.size - 1) * step / 2f
        } else {
            val gapY = 5.2f * u
            val blockH = ringBox + (if (lines.isEmpty()) 0f else gapY + (lines.size - 1) * step + 2.2f * u)
            val y0 = r.top + ((r.height() - blockH) / 2f).coerceAtLeast(pad)
            cx = r.centerX(); cy = y0 + ringBox / 2f
            left = r.left + pad + 0.8f * u; right = r.right - pad
            y = y0 + ringBox + gapY
        }
        val pct = g.pct
        k.ring(c, cx, cy, ringR, sw, pct, if (pct != null) k.level(pct) else pal.raised)

        // The level in the middle, with the range under it.
        val num = pct?.let { "${it.coerceIn(0.0, 100.0).roundToInt()}" } ?: "–"
        val np = k.text(0f, pal.text, Kit.bold).apply { textSize = ringR * 0.74f }
        val pp = k.text(0f, pal.muted, Kit.medium).apply { textSize = ringR * 0.28f }
        val nw = np.measureText(num); val pw = if (pct != null) pp.measureText("%") + ringR * 0.04f else 0f
        val nx = cx - (nw + pw) / 2f
        val base = cy + ringR * 0.16f
        c.drawText(num, nx, base, np)
        if (pct != null) c.drawText("%", nx + nw + ringR * 0.04f, base, pp)
        val rp = k.text(0f, pal.muted, Kit.medium, Paint.Align.CENTER).apply { textSize = ringR * 0.25f }
        c.drawText(k.ellipsize(g.range, rp, ringR * 1.5f), cx, base + ringR * 0.40f, rp)

        // What the car is doing, one line each with a small icon.
        for (ln in lines) {
            val col = k.tone(ln.tone)
            k.icon(c, ln.icon, left + ic / 2f, y, ic, if (ln.tone == Tone.TEXT) pal.muted else col)
            val lp = k.text(4.1f, col, Kit.medium)
            val tx = left + ic + 2f * u
            c.drawText(k.fit(ln.text, lp, right - tx, 0.8f), tx, k.mid(lp, y), lp)
            y += step
        }
    }

    private fun drawTiles(c: Canvas, k: Kit, grid: RectF, tiles: List<HomeModel.Tile>, pressed: String?, hits: MutableList<Hit>) {
        if (tiles.isEmpty() || grid.width() <= 0f || grid.height() <= 0f) return
        val u = k.u
        val gap = 2.2f * u
        val n = tiles.size
        var cols = when {
            grid.width() >= 3 * 52f * u + 2 * gap && n >= 3 -> 3
            grid.width() >= 2 * 38f * u + gap && n >= 2 -> 2
            else -> 1
        }
        // Never more rows than there is room for readable tiles.
        while (cols < 4 && ((n + cols - 1) / cols) * 15f * u + ((n + cols - 1) / cols - 1) * gap > grid.height() && cols < n) cols++
        val rows = (n + cols - 1) / cols
        // The empty places in the last row go to the first tile, so the grid is always full.
        val spare = rows * cols - n
        val tw = (grid.width() - gap * (cols - 1)) / cols
        val th = ((grid.height() - gap * (rows - 1)) / rows).coerceAtMost(48f * u)
        // Tall tiles have the icon at the top and the text at the bottom; low ones the icon to the left of the text.
        // All tiles of a grid the same way, also the first one when it is wider.
        val stacked = th >= 31f * u && tw < th * 2.6f
        var col = 0; var row = 0
        tiles.forEachIndexed { i, t ->
            val span = if (i == 0) 1 + spare else 1
            val x = grid.left + col * (tw + gap); val y = grid.top + row * (th + gap)
            val r = RectF(x, y, x + tw * span + gap * (span - 1), y + th)
            drawTile(c, k, r, t, stacked, pressed == t.key)
            hits += Hit(r, t.key)
            col += span
            if (col >= cols) { col = 0; row++ }
        }
    }

    private fun drawTile(c: Canvas, k: Kit, r: RectF, t: HomeModel.Tile, stacked: Boolean, pressed: Boolean) {
        val u = k.u; val pal = k.pal
        val fill = if (t.primary) pal.accent else pal.card
        val ink = if (t.primary) pal.onAccent else pal.text
        val soft = if (t.primary) Kit.alpha(pal.onAccent, 0.72f) else pal.muted
        k.card(c, r, 3.4f * u, fill, if (t.primary) null else pal.line, pressed)
        val pad = 3.3f * u
        val badgeFill = if (t.primary) Kit.alpha(pal.onAccent, 0.12f) else pal.raised
        val iconInk = if (t.primary) pal.onAccent else pal.accentInk
        val tp = k.text(5.3f, ink, Kit.bold)
        val sp = k.text(3.8f, soft, Kit.medium)
        if (stacked) {
            val br = 6.6f * u
            k.badge(c, r.left + pad + br, r.top + pad + br, br, t.icon, badgeFill, iconInk)
            k.chevron(c, r.right - pad - 1.2f * u, r.top + pad + br, 3.4f * u, 0, soft)
            val maxW = r.width() - pad * 2
            c.drawText(k.fit(t.title, tp, maxW, 0.78f), r.left + pad, r.bottom - pad - 6.8f * u, tp)
            c.drawText(k.fit(t.sub, sp, maxW, 0.86f), r.left + pad, r.bottom - pad - 0.9f * u, sp)
        } else {
            val br = min(r.height() * 0.30f, 6.6f * u)
            k.badge(c, r.left + pad + br, r.centerY(), br, t.icon, badgeFill, iconInk)
            // The arrow only where there is room to spare for it.
            val arrow = r.width() >= 74f * u
            if (arrow) k.chevron(c, r.right - pad - 0.8f * u, r.centerY(), 3.2f * u, 0, soft)
            val tx = r.left + pad + br * 2 + 2.8f * u
            val maxW = r.right - pad - (if (arrow) 4.6f * u else 0f) - tx
            c.drawText(k.fit(t.title, tp, maxW, 0.78f), tx, r.centerY() - 0.7f * u, tp)
            c.drawText(k.fit(t.sub, sp, maxW, 0.86f), tx, r.centerY() + 5.2f * u, sp)
        }
    }
}
