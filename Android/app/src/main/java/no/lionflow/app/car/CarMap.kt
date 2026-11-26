package no.lionflow.app.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import no.lionflow.app.api.Http
import no.lionflow.app.car.ui.MapView
import okhttp3.Request
import java.io.File
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The map behind a route in the car, put together from ready-made map pictures (tiles) fetched over the network.
 * Runs on a background thread only: nothing here may be called on the main thread. (The map library the phone app
 * uses could hold up the main thread in the car, so it is not used here.)
 */
internal object CarMap {
    const val CREDIT = "© OpenStreetMap"

    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * One map tile from OpenStreetMap's own tile server (no key needed; used as its tile policy asks: the app named in
     * the User-Agent, tiles kept on the phone for 30 days, only what is shown is fetched).
     */
    private fun tile(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        val dir = File(ctx.cacheDir, "car-tiles/osm/$z/$x").apply { mkdirs() }
        val file = File(dir, "$y.png")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 86_400_000L
        val bytes = if (fresh) file.readBytes() else try {
            val req = Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").header("User-Agent", Http.CONTACT_AGENT).build()
            Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                ?.also { try { file.writeBytes(it) } catch (_: Exception) { } }
        } catch (_: Exception) { if (file.exists()) file.readBytes() else null }
        val b = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        mem.put(key, b)
        return b
    }

    /** OpenStreetMap's map is light and colourful: toned down for the light look, turned dark for the dark look. */
    private fun look(dark: Boolean): ColorMatrixColorFilter {
        val m = ColorMatrix()
        if (dark) {
            m.set(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            m.postConcat(ColorMatrix().apply { setSaturation(0.18f) })
            m.postConcat(ColorMatrix(floatArrayOf(0.62f, 0f, 0f, 0f, 12f, 0f, 0.64f, 0f, 0f, 14f, 0f, 0f, 0.7f, 0f, 19f, 0f, 0f, 0f, 1f, 0f)))
        } else {
            m.setSaturation(0.45f)
        }
        return ColorMatrixColorFilter(m)
    }

    /** The map for [view], at about [scale] screen pixels per map pixel (1 = full sharpness). Null if nothing came. */
    fun picture(ctx: Context, view: MapView, dark: Boolean, scale: Float = 1f): Bitmap? {
        val w = max(64, (view.box.width() / scale).toInt()); val h = max(64, (view.box.height() / scale).toInt())
        val x0 = MapView.mx(view.west); val x1 = MapView.mx(view.east)
        val y0 = MapView.my(view.north); val y1 = MapView.my(view.south)
        if (x1 <= x0 || y1 <= y0) return null
        // The zoom where one tile (256 px) is about as big as it will be drawn.
        val z = floor(ln(w / (x1 - x0) / 256.0) / ln(2.0) + 0.5).toInt().coerceIn(2, 18)
        val n = 1 shl z
        val tx0 = floor(x0 * n).toInt(); val tx1 = floor(x1 * n).toInt()
        val ty0 = max(0, floor(y0 * n).toInt()); val ty1 = min(n - 1, floor(y1 * n).toInt())
        if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 60) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(if (dark) 0xFF0E1013.toInt() else 0xFFECEEF2.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = look(dark) }
        var any = false
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val b = tile(ctx, z, ((tx % n) + n) % n, ty) ?: continue
            any = true
            val l = ((tx.toDouble() / n - x0) / (x1 - x0) * w).toFloat(); val r = (((tx + 1).toDouble() / n - x0) / (x1 - x0) * w).toFloat()
            val t = ((ty.toDouble() / n - y0) / (y1 - y0) * h).toFloat(); val bo = (((ty + 1).toDouble() / n - y0) / (y1 - y0) * h).toFloat()
            c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(l, t, r, bo), p)
        }
        return if (any) out else null
    }
}
