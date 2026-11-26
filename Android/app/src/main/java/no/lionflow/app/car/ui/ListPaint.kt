package no.lionflow.app.car.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A list in pages: previous trips, chargers, saved routes, the cars to switch between. */
class ListModel(
    val title: String,
    val status: String?,
    val statusTone: Int = Tone.MUTED,
    /** A wider line above the rows (the last 7 days, say). */
    val summary: Summary? = null,
    val rows: List<Row>,
    /** Shown when there are no rows: "still loading" or "nothing here". */
    val empty: String,
) {
    class Summary(val icon: Int, val title: String, val sub: String)

    /**
     * One row. With a [key] it can be tapped. [end] and [endSub] stand at the right (a distance, a battery level).
     * [shape] draws the outline of a route instead of the icon.
     */
    class Row(
        val key: String?, val title: String, val sub: String, val icon: Int = 0, val lit: Boolean = false,
        val end: String = "", val endSub: String = "", val endTone: Int = Tone.TEXT,
        val shape: Shape? = null, val selected: Boolean = false,
    )
    class Shape(val lat: DoubleArray, val lon: DoubleArray)
}

/** Which page was drawn, and how many there are (for the buttons and swipes that turn pages). */
class ListLook(val page: Int, val pages: Int)

object ListPaint {
    const val UP = "page-up"
    const val DOWN = "page-down"

    fun draw(c: Canvas, k: Kit, m: ListModel, wantedPage: Int, pressed: String?, hits: MutableList<Hit>): ListLook {
        val u = k.u; val pal = k.pal
        c.drawColor(pal.bg)
        k.header(c, m.title, m.status, true, pressed, hits, m.statusTone)
        val body = k.st.body
        if (body.width() < 20f * u || body.height() < 12f * u) return ListLook(0, 1)
        val gap = 1.8f * u
        var top = body.top

        m.summary?.let { s ->
            val r = RectF(body.left, top, body.right, top + 13f * u)
            k.card(c, r, 3f * u, Kit.mix(pal.card, pal.accent, if (pal.dark) 0.10f else 0.22f), null)
            val br = 4.4f * u
            k.badge(c, r.left + 3f * u + br, r.centerY(), br, s.icon, pal.accent, pal.onAccent)
            val tx = r.left + 3f * u + br * 2 + 2.8f * u
            val tp = k.text(4.6f, pal.text, Kit.bold); val sp = k.text(3.5f, pal.muted, Kit.medium)
            c.drawText(k.fit(s.title, tp, r.right - 3f * u - tx, 0.75f), tx, r.centerY() - 0.5f * u, tp)
            c.drawText(k.ellipsize(s.sub, sp, r.right - 3f * u - tx), tx, r.centerY() + 4.5f * u, sp)
            top = r.bottom + gap
        }

        val area = RectF(body.left, top, body.right, body.bottom)
        if (m.rows.isEmpty()) { k.message(c, area, m.empty); return ListLook(0, 1) }

        // Two columns on a wide screen. Rows about 15 units high; as many as fit make a page.
        val cols = if (area.width() >= 190f * u) 2 else 1
        val perCol = max(1, ((area.height() + gap) / (15f * u + gap)).toInt())
        val perPage = perCol * cols
        val pages = max(1, (m.rows.size + perPage - 1) / perPage)
        val page = wantedPage.coerceIn(0, pages - 1)
        val pagerW = if (pages > 1) 12f * u else 0f
        val listRight = area.right - (if (pages > 1) pagerW + gap else 0f)
        val rw = (listRight - area.left - gap * (cols - 1)) / cols
        val rh = min((area.height() - gap * (perCol - 1)) / perCol, 20f * u)

        for (i in 0 until perPage) {
            val row = m.rows.getOrNull(page * perPage + i) ?: break
            // Down the first column, then the second.
            val x = area.left + (i / perCol) * (rw + gap); val y = area.top + (i % perCol) * (rh + gap)
            val r = RectF(x, y, x + rw, y + rh)
            drawRow(c, k, r, row, row.key != null && pressed == row.key)
            row.key?.let { hits += Hit(r, it) }
        }

        if (pages > 1) {
            val pr = RectF(area.right - pagerW, area.top, area.right, area.top + perCol * rh + (perCol - 1) * gap)
            k.card(c, pr, 3f * u)
            val half = pr.height() / 2f
            val up = RectF(pr.left, pr.top, pr.right, pr.top + half); val down = RectF(pr.left, pr.top + half, pr.right, pr.bottom)
            val canUp = page > 0; val canDown = page < pages - 1
            if (pressed == UP && canUp) c.drawRoundRect(RectF(up.left + 0.8f * u, up.top + 0.8f * u, up.right - 0.8f * u, up.bottom - 3f * u), 2.4f * u, 2.4f * u, k.fill(pal.raised))
            if (pressed == DOWN && canDown) c.drawRoundRect(RectF(down.left + 0.8f * u, down.top + 3f * u, down.right - 0.8f * u, down.bottom - 0.8f * u), 2.4f * u, 2.4f * u, k.fill(pal.raised))
            val edge = min(half * 0.42f, 9f * u)
            k.chevron(c, pr.centerX(), pr.top + edge, 4.4f * u, 2, if (canUp) pal.text else pal.line, 0.7f * u)
            k.chevron(c, pr.centerX(), pr.bottom - edge, 4.4f * u, 3, if (canDown) pal.text else pal.line, 0.7f * u)
            val np = k.text(3.6f, pal.muted, Kit.medium, Paint.Align.CENTER)
            c.drawText("${page + 1}/$pages", pr.centerX(), k.mid(np, pr.centerY()), np)
            if (canUp) hits += Hit(up, UP)
            if (canDown) hits += Hit(down, DOWN)
        }
        return ListLook(page, pages)
    }

    private fun drawRow(c: Canvas, k: Kit, r: RectF, row: ListModel.Row, pressed: Boolean) {
        val u = k.u; val pal = k.pal
        k.card(c, r, 2.8f * u, pal.card, if (row.selected) pal.accentInk else pal.line, pressed)
        val pad = 2.8f * u
        var x = r.left + pad
        val shape = row.shape
        if (shape != null && shape.lat.size >= 2) {
            val side = r.height() - 2.4f * u
            val box = RectF(x - 0.8f * u, r.centerY() - side / 2f, x - 0.8f * u + side, r.centerY() + side / 2f)
            c.drawRoundRect(box, 2f * u, 2f * u, k.fill(pal.raised))
            drawShape(c, k, box, shape)
            x = box.right + 2.8f * u
        } else if (row.icon != 0) {
            val br = min(r.height() * 0.31f, 4.9f * u)
            k.badge(c, x + br, r.centerY(), br, row.icon, if (row.lit) pal.accent else pal.raised, if (row.lit) pal.onAccent else pal.text)
            x += br * 2 + 2.8f * u
        }
        var right = r.right - pad
        if (row.key != null) { k.chevron(c, right - 0.8f * u, r.centerY(), 3f * u, 0, pal.muted); right -= 4.4f * u }
        if (row.end.isNotEmpty()) {
            val ep = k.text(4.5f, k.tone(row.endTone), Kit.bold, Paint.Align.RIGHT)
            val sp = k.text(3.3f, pal.muted, Kit.medium, Paint.Align.RIGHT)
            val two = row.endSub.isNotEmpty()
            c.drawText(row.end, right, if (two) r.centerY() - 0.4f * u else k.mid(ep, r.centerY()), ep)
            if (two) c.drawText(row.endSub, right, r.centerY() + 4.4f * u, sp)
            right -= max(ep.measureText(row.end), if (two) sp.measureText(row.endSub) else 0f) + 2.6f * u
        }
        val tp = k.text(4.5f, pal.text, Kit.bold); val sp = k.text(3.5f, pal.muted, Kit.medium)
        val room = right - x
        if (row.sub.isEmpty()) c.drawText(k.fit(row.title, tp, room, 0.78f), x, k.mid(tp, r.centerY()), tp)
        else {
            c.drawText(k.fit(row.title, tp, room, 0.78f), x, r.centerY() - 0.4f * u, tp)
            c.drawText(k.ellipsize(row.sub, sp, room), x, r.centerY() + 4.4f * u, sp)
        }
    }

    /** The outline of a route, fitted into [box]. */
    private fun drawShape(c: Canvas, k: Kit, box: RectF, s: ListModel.Shape) {
        val n = min(s.lat.size, s.lon.size)
        val kx = cos(Math.toRadians(s.lat.average()))
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) { val x = s.lon[i] * kx; val y = -s.lat[i]; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
        val inner = RectF(box.left + box.width() * 0.16f, box.top + box.height() * 0.16f, box.right - box.width() * 0.16f, box.bottom - box.height() * 0.16f)
        val w = max(maxX - minX, 1e-6); val h = max(maxY - minY, 1e-6)
        val sc = min(inner.width() / w, inner.height() / h)
        val ox = inner.left + ((inner.width() - w * sc) / 2).toFloat(); val oy = inner.top + ((inner.height() - h * sc) / 2).toFloat()
        fun px(i: Int) = ox + ((s.lon[i] * kx - minX) * sc).toFloat()
        fun py(i: Int) = oy + ((-s.lat[i] - minY) * sc).toFloat()
        val path = Path()
        // A point for about every pixel is plenty.
        val step = max(1, n / 160)
        var i = 0
        while (i < n) { if (i == 0) path.moveTo(px(i), py(i)) else path.lineTo(px(i), py(i)); i += step }
        path.lineTo(px(n - 1), py(n - 1))
        c.drawPath(path, k.stroke(k.pal.accentInk, max(2f, box.width() * 0.055f)))
        c.drawCircle(px(0), py(0), box.width() * 0.055f, k.fill(k.pal.muted))
        c.drawCircle(px(n - 1), py(n - 1), box.width() * 0.07f, k.fill(k.pal.text))
    }
}
