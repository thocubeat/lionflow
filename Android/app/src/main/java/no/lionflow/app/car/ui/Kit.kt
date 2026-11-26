package no.lionflow.app.car.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Everything in this folder only draws: it gets a canvas, a ready-made model (texts already translated) and the part
 * of the screen it may use, and knows nothing about the car, the app or Android Auto. That keeps it quick, and lets
 * the same code be rendered to pictures in a test.
 */

/** Colours of the screens LionFlow draws in the car. */
class Palette(
    val dark: Boolean,
    val bg: Int, val card: Int, val raised: Int, val line: Int,
    val text: Int, val muted: Int,
    /** The LionFlow green as a fill, as text or a line on the background, and the text colour on top of the fill. */
    val accent: Int, val accentInk: Int, val onAccent: Int,
    val orange: Int, val red: Int,
    /** The part of a route already driven, and other "done" marks. */
    val driven: Int,
) {
    companion object {
        val DARK = Palette(
            true,
            bg = 0xFF0E1013.toInt(), card = 0xFF191C20.toInt(), raised = 0xFF262A30.toInt(), line = 0xFF2B3037.toInt(),
            text = 0xFFF4F5F7.toInt(), muted = 0xFF9BA2AC.toInt(),
            accent = 0xFFC6F36B.toInt(), accentInk = 0xFFC6F36B.toInt(), onAccent = 0xFF10140A.toInt(),
            orange = 0xFFFFA347.toInt(), red = 0xFFFF6164.toInt(), driven = 0xFF4A5059.toInt(),
        )
        val LIGHT = Palette(
            false,
            bg = 0xFFECEEF2.toInt(), card = 0xFFFFFFFF.toInt(), raised = 0xFFE8EBF0.toInt(), line = 0xFFDADEE5.toInt(),
            text = 0xFF14171B.toInt(), muted = 0xFF5D6570.toInt(),
            accent = 0xFFBDEB55.toInt(), accentInk = 0xFF477800.toInt(), onAccent = 0xFF10140A.toInt(),
            orange = 0xFFD9660A.toInt(), red = 0xFFD2383D.toInt(), driven = 0xFFB4BBC5.toInt(),
        )
    }
}

/** Icons as ready-drawn pictures in one colour (made once and kept by whoever provides them). */
fun interface IconSource {
    fun icon(res: Int, size: Int, color: Int): Bitmap?
}

/** A part of the screen that can be tapped, and the name of what it does. */
class Hit(val rect: RectF, val key: String)

/** How a text or value is meant: plain, toned down, good, a warning, bad. */
object Tone {
    const val TEXT = 0
    const val MUTED = 1
    const val GOOD = 2
    const val WARN = 3
    const val BAD = 4
}

/**
 * The part of the car screen a screen may draw on, and the unit everything is sized in.
 *
 * Android Auto gives a surface that can be larger than what shows: beside it there can be a media card, and its own
 * buttons float at the top right. [body] is the free part below those buttons; [header] is the band beside them,
 * used for the title. [u] is one hundredth of the height (or of 62 % of the width on a narrow screen), so text and
 * tiles keep the same share of the screen on every car.
 */
class Stage(val width: Int, val height: Int, val dpi: Int, visible: Rect?, stable: Rect?, hostIcons: Int = 1, hostTextChars: Int = 0) {
    val u: Float
    val header: RectF
    val body: RectF
    /** The title may run to here; the car's own buttons take the rest of the band. */
    val titleRight: Float
    val density: Float = (if (dpi > 0) dpi else 160) / 160f

    init {
        val w = width.toFloat(); val h = height.toFloat()
        // An area that doesn't fit this surface (left over from another size, say) is not believed.
        fun usable(r: Rect?): Rect? = r?.takeIf { it.width() >= w * 0.25f && it.height() >= h * 0.25f && it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height }
        val v = usable(visible); val s = usable(stable)
        val left = max(v?.left ?: 0, s?.left ?: 0).toFloat()
        val right = min(v?.right ?: width, s?.right ?: width).toFloat().coerceAtMost(w)
        val bottom = min(v?.bottom ?: height, s?.bottom ?: height).toFloat().coerceAtMost(h)
        val inset = max(v?.top ?: 0, s?.top ?: 0).toFloat()
        u = (min(bottom, (right - left) * 0.62f) / 100f).coerceAtLeast(2f)
        val band = max(inset, 13.5f * u).coerceAtMost(bottom * 0.3f)
        header = RectF(left, 0f, right, band)
        body = RectF(left + 3f * u, band + 0.4f * u, right - 3f * u, bottom - 3f * u)
        val pitch = max(72f * density, inset * 0.92f)
        val reserved = hostIcons * pitch + (if (hostTextChars > 0) pitch * 0.7f + hostTextChars * 12f * density else 0f) + 14f * density
        titleRight = max(right - reserved, left + (right - left) * 0.36f) - 1.5f * u
    }
}

/** Drawing helpers shared by the car screens, sized in [Stage.u]. */
class Kit(val pal: Palette, val st: Stage, private val icons: IconSource?) {
    val u: Float = st.u

    companion object {
        val regular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
        val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val bold: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(x: Int, s: Int) = (x shr s) and 0xFF
            fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt().coerceIn(0, 255)
            return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }

        /** The same colour, see-through: [a] from 0 (invisible) to 1. */
        fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }

    fun tone(t: Int): Int = when (t) { Tone.MUTED -> pal.muted; Tone.GOOD -> pal.accentInk; Tone.WARN -> pal.orange; Tone.BAD -> pal.red; else -> pal.text }

    /** Battery colour: red under 10 %, orange under 20 %, else green. */
    fun level(pct: Double): Int = when { pct < 10 -> pal.red; pct < 20 -> pal.orange; else -> pal.accent }

    fun text(size: Float, color: Int, face: Typeface = regular, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * u; this.color = color; typeface = face; textAlign = align }

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** The baseline that puts capitals and digits of [p] in the middle at [cy]. */
    fun mid(p: Paint, cy: Float): Float = cy + p.textSize * 0.355f

    fun pressedFill(fill: Int): Int = if (pal.dark) mix(fill, 0xFFFFFFFF.toInt(), 0.10f) else mix(fill, 0xFF000000.toInt(), 0.07f)

    /** A flat card with a fine edge. */
    fun card(c: Canvas, r: RectF, radius: Float = 3.2f * u, fill: Int = pal.card, edge: Int? = pal.line, pressed: Boolean = false) {
        if (r.width() <= 0f || r.height() <= 0f) return
        val rad = min(radius, min(r.width(), r.height()) / 2f)
        c.drawRoundRect(r, rad, rad, fill(if (pressed) pressedFill(fill) else fill))
        if (edge != null) {
            val w = max(1f, 0.18f * u); val h = w / 2f
            c.drawRoundRect(RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), rad - h, rad - h, stroke(edge, w))
        }
    }

    fun icon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        if (res == 0 || size < 2f) return
        // A few fixed sizes, so only a handful of pictures are ever made.
        val px = (ceil(size / 16f).toInt() * 16).coerceIn(16, 256)
        val b = icons?.icon(res, px, color) ?: return
        val h = size / 2f
        c.drawBitmap(b, null, RectF(cx - h, cy - h, cx + h, cy + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** A round badge with an icon in it. */
    fun badge(c: Canvas, cx: Float, cy: Float, r: Float, res: Int, fill: Int, ink: Int) {
        c.drawCircle(cx, cy, r, fill(fill))
        icon(c, res, cx, cy, r * 1.12f, ink)
    }

    fun ellipsize(s: String, p: Paint, width: Float): String {
        if (width <= 0f) return ""
        if (p.measureText(s) <= width) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (p.measureText(s.substring(0, m).trimEnd() + "…") <= width) lo = m else hi = m - 1
        }
        return if (lo <= 0) "…" else s.substring(0, lo).trimEnd() + "…"
    }

    /** Makes [s] fit in [width]: the text is first made a little smaller (down to [least] of its size), then cut. */
    fun fit(s: String, p: Paint, width: Float, least: Float = 0.8f): String {
        if (width <= 0f) return ""
        val w = p.measureText(s)
        if (w <= width) return s
        val f = width / w
        if (f >= least) { p.textSize = p.textSize * f * 0.995f; return s }
        p.textSize = p.textSize * least
        return ellipsize(s, p, width)
    }

    /** Breaks [s] into at most [maxLines] lines that fit in [width]; the last one is cut if it must be. */
    fun wrap(s: String, p: Paint, width: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        val words = s.split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size) {
            val next = if (line.isEmpty()) words[i] else line + " " + words[i]
            if (p.measureText(next) <= width || line.isEmpty()) { line = next; i++ }
            else {
                if (out.size == maxLines - 1) { out += ellipsize(line + " " + words.subList(i, words.size).joinToString(" "), p, width); return out }
                out += line; line = ""
            }
        }
        if (line.isNotEmpty()) out += if (p.measureText(line) > width) ellipsize(line, p, width) else line
        return out
    }

    /** A ring that is coloured as far as [pct] goes (0–100; null leaves it empty). */
    fun ring(c: Canvas, cx: Float, cy: Float, r: Float, width: Float, pct: Double?, color: Int, track: Int = pal.raised) {
        c.drawCircle(cx, cy, r, stroke(track, width))
        val sweep = ((pct ?: 0.0).coerceIn(0.0, 100.0) * 3.6).toFloat()
        if (sweep > 0.5f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, stroke(color, width))
    }

    /** A small arrow head: 0 right, 1 left, 2 up, 3 down. */
    fun chevron(c: Canvas, cx: Float, cy: Float, size: Float, dir: Int, color: Int, width: Float = 0.55f * u) {
        val h = size / 2f; val q = size / 4f
        val p = Path()
        when (dir) {
            0 -> { p.moveTo(cx - q, cy - h); p.lineTo(cx + q, cy); p.lineTo(cx - q, cy + h) }
            1 -> { p.moveTo(cx + q, cy - h); p.lineTo(cx - q, cy); p.lineTo(cx + q, cy + h) }
            2 -> { p.moveTo(cx - h, cy + q); p.lineTo(cx, cy - q); p.lineTo(cx + h, cy + q) }
            else -> { p.moveTo(cx - h, cy - q); p.lineTo(cx, cy + q); p.lineTo(cx + h, cy - q) }
        }
        c.drawPath(p, stroke(color, width))
    }

    /** A rounded label centred on ([cx], [cy]). Returns its box. */
    fun pill(c: Canvas, label: String, cx: Float, cy: Float, size: Float, bg: Int, fg: Int, edge: Int? = null): RectF {
        val t = text(size, fg, medium, Paint.Align.CENTER)
        val w = t.measureText(label) + size * u * 1.1f; val h = size * u * 1.7f
        val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        c.drawRoundRect(r, h / 2f, h / 2f, fill(bg))
        if (edge != null) c.drawRoundRect(r, h / 2f, h / 2f, stroke(edge, max(1f, 0.16f * u)))
        c.drawText(label, cx, mid(t, cy), t)
        return r
    }

    /** A round mark with a lightning bolt (a charging stop), with a rim in the background colour so it stands out. */
    fun bolt(c: Canvas, x: Float, y: Float, r: Float, fill: Int, ink: Int, rim: Int = pal.bg) {
        c.drawCircle(x, y, r * 1.26f, fill(rim))
        c.drawCircle(x, y, r, fill(fill))
        val k = r / 10f
        val b = Path()
        b.moveTo(x + 1.5f * k, y - 6.5f * k); b.lineTo(x - 4f * k, y + 1f * k); b.lineTo(x - 0.2f * k, y + 1f * k)
        b.lineTo(x - 1.5f * k, y + 6.5f * k); b.lineTo(x + 4f * k, y - 1f * k); b.lineTo(x + 0.2f * k, y - 1f * k); b.close()
        c.drawPath(b, fill(ink))
    }

    /**
     * The band at the top: a back button (on every screen but the first), the title and a line under it.
     * The car's own buttons sit at the right end of the same band.
     */
    fun header(c: Canvas, title: String, subtitle: String?, back: Boolean, pressed: String?, hits: MutableList<Hit>, subTone: Int = Tone.MUTED) {
        val sub = subtitle?.takeIf { it.isNotBlank() }
        val h = st.header
        val cy = h.centerY()
        var x = h.left + 3.2f * u
        if (back) {
            val r = min(h.height() * 0.34f, 4.9f * u)
            c.drawCircle(x + r, cy, r, fill(if (pressed == "back") pressedFill(pal.raised) else pal.raised))
            chevron(c, x + r - 0.12f * r, cy, r * 0.92f, 1, pal.text, max(2f, 0.6f * u))
            hits += Hit(RectF(h.left, h.top, x + 2f * r + 2.4f * u, h.bottom), "back")
            x += 2f * r + 2.6f * u
        }
        val room = st.titleRight - x
        if (room <= 4f * u) return
        val tp = text(if (sub != null) 6.1f else 6.4f, pal.text, bold)
        val t = fit(title, tp, room, 0.72f)
        if (sub == null) { c.drawText(t, x, mid(tp, cy), tp); return }
        val sp = text(3.5f, tone(subTone), medium)
        c.drawText(t, x, cy + 0.5f * u, tp)
        c.drawText(ellipsize(sub, sp, room), x, cy + 5.2f * u, sp)
    }

    /** One centred message filling [r] (while loading, or when there is nothing to show), in up to three lines. */
    fun message(c: Canvas, r: RectF, text: String, tone: Int = Tone.MUTED) {
        val p = text(4.4f, tone(tone), medium, Paint.Align.CENTER)
        val lines = wrap(text, p, r.width() - 8f * u, 3)
        var y = r.centerY() - (lines.size - 1) * 3f * u
        for (l in lines) { c.drawText(l, r.centerX(), mid(p, y), p); y += 6f * u }
    }
}
