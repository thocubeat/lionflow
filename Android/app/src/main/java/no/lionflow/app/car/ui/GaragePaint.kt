package no.lionflow.app.car.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** Open, close or cancel for the garage door: three big buttons, the likely one in green. */
class GarageModel(val title: String, val status: String, val statusTone: Int, val choices: List<Choice>) {
    /** [icon] 0 draws an arrow: up for "open", down for "close". [main] is filled green. */
    class Choice(val key: String, val label: String, val icon: Int = 0, val main: Boolean = false)
}

object GaragePaint {
    fun draw(c: Canvas, k: Kit, m: GarageModel, pressed: String?, hits: MutableList<Hit>) {
        val u = k.u; val pal = k.pal
        c.drawColor(pal.bg)
        k.header(c, m.title, m.status, true, pressed, hits, m.statusTone)
        val body = k.st.body
        val n = m.choices.size
        if (n == 0 || body.width() < 20f * u || body.height() < 12f * u) return
        val gap = 2.4f * u
        // Side by side on a wide screen; on top of each other where they would get too narrow.
        val across = (body.width() - gap * (n - 1)) / n >= 24f * u
        val w = if (across) (body.width() - gap * (n - 1)) / n else body.width()
        val h = if (across) min(body.height(), 46f * u) else min((body.height() - gap * (n - 1)) / n, 22f * u)
        val top = if (across) body.centerY() - h / 2f else body.top + (body.height() - (h * n + gap * (n - 1))) / 2f
        m.choices.forEachIndexed { i, ch ->
            val x = if (across) body.left + i * (w + gap) else body.left
            val y = if (across) top else top + i * (h + gap)
            val r = RectF(x, y, x + w, y + h)
            val fill = if (ch.main) pal.accent else pal.card
            val ink = if (ch.main) pal.onAccent else pal.text
            k.card(c, r, 4f * u, fill, if (ch.main) null else pal.line, pressed == ch.key)
            val label = k.text(5.6f, ink, Kit.bold, Paint.Align.CENTER)
            if (across) {
                val cy = r.top + r.height() * 0.40f
                val size = min(r.width(), r.height()) * 0.26f
                symbol(c, k, ch, r.centerX(), cy, size, ink)
                c.drawText(k.fit(ch.label, label, r.width() - 4f * u, 0.7f), r.centerX(), k.mid(label, r.top + r.height() * 0.74f), label)
            } else {
                val size = r.height() * 0.42f
                symbol(c, k, ch, r.left + 4f * u + size / 2f, r.centerY(), size, ink)
                label.textAlign = Paint.Align.LEFT
                c.drawText(k.fit(ch.label, label, r.width() - 10f * u - size, 0.7f), r.left + 7f * u + size, k.mid(label, r.centerY()), label)
            }
            hits += Hit(r, ch.key)
        }
    }

    private fun symbol(c: Canvas, k: Kit, ch: GarageModel.Choice, cx: Float, cy: Float, size: Float, ink: Int) {
        if (ch.icon != 0) k.icon(c, ch.icon, cx, cy, size, ink)
        else k.chevron(c, cx, cy, size, if (ch.key == "open") 2 else 3, ink, 1.2f * k.u)
    }
}
