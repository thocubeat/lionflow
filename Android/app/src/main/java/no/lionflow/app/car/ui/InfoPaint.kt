package no.lionflow.app.car.ui

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Everything the car reports about itself, as a grid of small cards with a label and a value. */
class InfoModel(
    val title: String,
    val status: String,
    val statusTone: Int,
    val cells: List<Cell>,
    /** Shown instead of the grid while there is nothing to show yet. */
    val waiting: String,
) {
    /** [bar] (0–100) draws a level under the value, for the battery and the like. */
    class Cell(val label: String, val value: String, val tone: Int = Tone.TEXT, val bar: Double? = null)
}

object InfoPaint {
    fun draw(c: Canvas, k: Kit, m: InfoModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        c.drawColor(pal.bg)
        k.header(c, m.title, m.status, true, pressed, hits, m.statusTone)
        val body = k.st.body
        if (body.width() < 20f * u || body.height() < 12f * u) return
        if (m.cells.isEmpty()) { k.message(c, body, m.waiting); return }

        val gap = 1.8f * u
        var n = m.cells.size
        var cols = ((body.width() + gap) / (36f * u + gap)).toInt().coerceIn(2, 6)
        fun rows(count: Int, cl: Int) = (count + cl - 1) / cl
        fun cellH(count: Int, cl: Int) = (body.height() - gap * (rows(count, cl) - 1)) / rows(count, cl)
        // More, narrower columns before the cards get too low to read; then leave out the last ones.
        while (cellH(n, cols) < 12.5f * u && cols < 6 && (body.width() - gap * cols) / (cols + 1) >= 27f * u) cols++
        while (cellH(n, cols) < 11.5f * u && n > cols) n--
        val cw = (body.width() - gap * (cols - 1)) / cols
        val ch = min(cellH(n, cols), 23f * u)
        for (i in 0 until n) {
            val cell = m.cells[i]
            val x = body.left + (i % cols) * (cw + gap); val y = body.top + (i / cols) * (ch + gap)
            val r = RectF(x, y, x + cw, y + ch)
            k.card(c, r, 2.6f * u)
            val pad = 2.8f * u
            val lp = k.text(3.3f, pal.muted, Kit.medium)
            val vp = k.text(5f, k.tone(cell.tone), Kit.bold)
            val bar = cell.bar
            // Label and value sit at the same height in every card; a level bar goes under them where there is room.
            val blockH = 3.3f * u * 0.72f + 2.3f * u + 5f * u * 0.72f
            val top = r.top + ((r.height() - blockH) / 2f - (if (r.height() < 16f * u) 1.1f * u else 0f)).coerceAtLeast(1.5f * u)
            c.drawText(k.ellipsize(cell.label, lp, cw - pad * 2), r.left + pad, top + 3.3f * u * 0.72f, lp)
            c.drawText(k.fit(cell.value, vp, cw - pad * 2, 0.66f), r.left + pad, top + blockH, vp)
            if (bar != null && top + blockH + 1.3f * u <= r.bottom - 2.6f * u) {
                val by = r.bottom - 2.6f * u; val bh = max(2f, 0.9f * u)
                val track = RectF(r.left + pad, by, r.right - pad, by + bh)
                c.drawRoundRect(track, bh / 2f, bh / 2f, k.fill(pal.raised))
                val w = track.width() * (bar.coerceIn(0.0, 100.0) / 100.0).toFloat()
                if (w > bh) c.drawRoundRect(RectF(track.left, by, track.left + w, by + bh), bh / 2f, bh / 2f, k.fill(if (cell.tone == Tone.TEXT) pal.accentInk else k.tone(cell.tone)))
            }
        }
    }
}
