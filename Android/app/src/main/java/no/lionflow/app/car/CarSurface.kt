package no.lionflow.app.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * The one drawing surface Android Auto gives LionFlow, shared by every screen the app draws itself.
 *
 * The surface gets a virtual display of its own, as a phone screen would, with one view on it that draws the screen on
 * show (its [Client]). Android itself puts the pictures on the surface, with the graphics chip, and takes care of
 * the surface changing size or being replaced. (Drawing on the surface directly, as before, could leave it stuck so
 * nothing new showed up.)
 */
internal class CarSurface(private val ctx: CarContext, private val log: (String, Throwable?) -> Unit) : SurfaceCallback {

    /** A screen that draws itself. All of it is called on the main thread. */
    interface Client {
        /** Draw the whole screen. Must be quick: no files, no network, no waiting. */
        fun paint(c: Canvas, f: Frame)
        fun onTap(x: Float, y: Float) {}
        fun onScroll(dx: Float, dy: Float) {}
        fun onFling(vx: Float, vy: Float) {}
    }

    /** The surface's size, and the parts of it Android Auto leaves free (null until it has said). */
    class Frame(val width: Int, val height: Int, val dpi: Int, val visible: Rect?, val stable: Rect?)

    private val main = Handler(Looper.getMainLooper())
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var stable: Rect? = null
    private var client: Client? = null
    /** The screen the surface is lent to (a video page), which then gets everything Android Auto says about it. */
    private var borrower: SurfaceCallback? = null
    private var closed = false

    private var display: VirtualDisplay? = null
    private var shownOn: Surface? = null
    private var presentation: Presentation? = null
    private var view: View? = null

    /** The view that shows the screen: it draws whatever screen is on show, at the surface's full size. */
    private inner class Board(c: Context) : View(c) {
        override fun onDraw(canvas: Canvas) = paintInto(canvas)
    }

    private fun frame(): Frame? {
        val sc = container ?: return null
        if (sc.width <= 0 || sc.height <= 0) return null
        return Frame(sc.width, sc.height, sc.dpi, visible, stable)
    }

    private fun paintInto(canvas: Canvas) {
        val cl = client ?: return
        val f = frame() ?: return
        // Whatever goes wrong while drawing, the app carries on: what was drawn so far is shown, and it is logged.
        try { cl.paint(canvas, f) } catch (e: Throwable) { log("drawing ${cl.javaClass.simpleName}", e) }
    }

    /** Tells Android Auto that this is where the surface goes. Call once the session exists. */
    fun start() {
        if (closed) return
        try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(this) }
        catch (e: Exception) { log("surface: couldn't ask for it", e) }
    }

    /** [c] is now the screen that draws. */
    fun attach(c: Client) { client = c; invalidate() }

    /** [c] no longer shows. Does nothing when another screen has taken over in the meantime. */
    fun detach(c: Client) { if (client === c) client = null }

    /** Draw again soon. Many calls in a row make one drawing. Safe from any thread. */
    fun invalidate() {
        if (Looper.myLooper() != main.looper) { main.post { invalidate() }; return }
        if (closed || borrower != null) return
        val v = view
        if (v != null) { v.invalidate(); return }
        // No surface to show on (yet): the screen is still laid out, so its tappable parts are known.
        val f = frame() ?: return
        val pic = Picture()
        paintInto(pic.beginRecording(f.width, f.height))
        pic.endRecording()
    }

    /** Puts the virtual display, and the view on it, on the surface Android Auto last gave. */
    private fun show() {
        if (closed || borrower != null) return
        val sc = container ?: return
        val s = sc.surface
        if (s == null || !s.isValid || sc.width <= 0 || sc.height <= 0) { invalidate(); return }
        try {
            val vd = display
            if (vd == null) {
                val d = ctx.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                    "LionFlowCar", sc.width, sc.height, sc.dpi, s, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                display = d
                val p = Presentation(ctx, d.display)
                val b = Board(p.context)
                p.setContentView(b)
                p.show()
                presentation = p; view = b
            } else {
                if (shownOn !== s) vd.surface = s
                vd.resize(sc.width, sc.height, sc.dpi)
            }
            shownOn = s
        } catch (e: Exception) {
            log("surface: couldn't show on it", e)
            hide()
        }
        invalidate()
    }

    /** Takes the display off the surface, so that something else can use it. */
    private fun hide() {
        try { presentation?.dismiss() } catch (_: Exception) { }
        try { display?.release() } catch (_: Exception) { }
        presentation = null; view = null; display = null; shownOn = null
    }

    // ---------------- from Android Auto (main thread) ----------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (closed) return
        container = surfaceContainer
        val b = borrower
        if (b != null) { forward("surface") { b.onSurfaceAvailable(surfaceContainer) }; return }
        show()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        val b = borrower
        if (b != null) { forward("surface gone") { b.onSurfaceDestroyed(surfaceContainer) }; return }
        hide()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (visibleArea != visible) { visible = Rect(visibleArea); invalidate() }
        borrower?.let { b -> forward("visible area") { b.onVisibleAreaChanged(visibleArea) } }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (stableArea != stable) { stable = Rect(stableArea); invalidate() }
        borrower?.let { b -> forward("stable area") { b.onStableAreaChanged(stableArea) } }
    }

    override fun onClick(x: Float, y: Float) {
        val b = borrower
        if (b != null) forward("tap") { b.onClick(x, y) } else forward("tap") { client?.onTap(x, y) }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val b = borrower
        if (b != null) forward("scroll") { b.onScroll(distanceX, distanceY) } else forward("scroll") { client?.onScroll(distanceX, distanceY) }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val b = borrower
        if (b != null) forward("fling") { b.onFling(velocityX, velocityY) } else forward("fling") { client?.onFling(velocityX, velocityY) }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        borrower?.let { b -> forward("pinch") { b.onScale(focusX, focusY, scaleFactor) } }
    }

    /** Nothing a screen does with a tap or the surface may come back to Android Auto as a crash. */
    private inline fun forward(what: String, block: () -> Unit) { try { block() } catch (e: Exception) { log(what, e) } }

    // ---------------- lending the surface to the video screen ----------------

    /**
     * Hands the surface over to a screen that shows something else on it (a video page). The drawn screens' display
     * comes off the surface, and everything Android Auto says about it goes to [to] until [takeBack].
     */
    fun lend(to: SurfaceCallback) {
        if (closed) return
        borrower = to
        hide()
        forward("lending the surface") {
            container?.let { to.onSurfaceAvailable(it) }
            visible?.let { to.onVisibleAreaChanged(it) }
            stable?.let { to.onStableAreaChanged(it) }
        }
    }

    /** [from] is done with the surface (and has let go of it): the drawn screens have it again. */
    fun takeBack(from: SurfaceCallback) {
        if (borrower !== from) return
        borrower = null
        if (closed) return
        show()
    }

    /** The session is over. */
    fun close() {
        if (closed) return
        closed = true
        client = null
        try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { }
        hide()
    }
}
