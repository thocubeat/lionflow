package no.lionflow.app.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import no.lionflow.app.R
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.IconSource
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.Stage
import no.lionflow.app.ui.l10n.tr

private const val BACK_TAP = "carBackTap"

/** What the screens of one Android Auto session share: the drawing surface, the icons, the parked check. */
internal class CarUi(val ctx: CarContext) {
    val guard = ParkGuard(ctx)
    val surface = CarSurface(ctx) { what, e -> log(what, e) }
    val icons = CarIcons(ctx)

    /**
     * Cars without a touch screen can't tap what LionFlow draws: with "Plain lists in the car" on in Settings they
     * get Android Auto's own tiles and lists. (The car doesn't say reliably whether its screen can be touched.)
     */
    val plain: Boolean get() = try { CarHub.app(ctx).settings.value.carPlain } catch (_: Exception) { false }

    /**
     * Whether a tap on the drawn back button (top left, beside Android Auto's own buttons) has ever come through.
     * Until one has, the screens also keep a back button among Android Auto's own, so there is always a way back.
     */
    val backTapSeen: Boolean get() = try { CarHub.app(ctx).settings.raw(BACK_TAP) == "1" } catch (_: Exception) { false }
    fun sawBackTap() { try { if (!backTapSeen) CarHub.app(ctx).settings.setRaw(BACK_TAP, "1") } catch (_: Exception) { } }

    private var lastLog = ""
    private var lastLogAt = 0L

    /** Goes to Settings › Command & error log on the phone, so a problem in the car can be traced afterwards. */
    fun log(what: String, e: Throwable? = null) {
        android.util.Log.w("LionFlowCar", what, e)
        val line = "Android Auto: $what" + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (line == lastLog && now - lastLogAt < 30_000) return   // not the same line for every picture drawn
            lastLog = line; lastLogAt = now
        }
        try { CarHub.app(ctx).remote.addLog(line.take(300)) } catch (_: Exception) { }
    }

    /** Runs what a tap or a button asked for. A failure is logged and said, and never reaches Android Auto as a crash. */
    inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            log(what, e)
            try { CarToast.makeText(ctx, tr("Something went wrong.") + " " + (e.message ?: e.javaClass.simpleName).take(80), CarToast.LENGTH_LONG).show() } catch (_: Exception) { }
        }
    }
}

/** The app's icons as pictures in one colour, each made once per size and colour. */
internal class CarIcons(private val ctx: Context) : IconSource {
    private data class Key(val res: Int, val size: Int, val color: Int)
    private val cache = object : LinkedHashMap<Key, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean = size > 120
    }

    override fun icon(res: Int, size: Int, color: Int): Bitmap? {
        val key = Key(res, size, color)
        synchronized(cache) { cache[key] }?.let { return it }
        return try {
            val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return null
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setTint(color); d.setBounds(0, 0, size, size); d.draw(Canvas(b))
            synchronized(cache) { cache[key] = b }
            b
        } catch (_: Exception) { null }
    }
}

/**
 * A screen LionFlow draws itself. Android Auto shows it as a "map" with a few buttons of its own at the top right;
 * everything else is drawn here, and taps on the screen come back as the name of what was tapped.
 *
 * A subclass says what to draw ([draw]), what its buttons are ([buttons]) and what a tap does ([tapped]).
 */
internal abstract class DrawnScreen(ctx: CarContext, protected val ui: CarUi) : Screen(ctx), CarSurface.Client, DefaultLifecycleObserver {
    /** One of the buttons Android Auto shows at the top right: an icon, or a short text. */
    protected class Btn(val icon: Int = 0, val title: String? = null, val tint: CarColor? = null, val custom: CarIcon? = null, val run: () -> Unit)

    protected val main = Handler(Looper.getMainLooper())
    private var hits: List<Hit> = emptyList()
    private var pressed: String? = null
    private var hostIcons = 1
    private var hostChars = 0

    /** What can be tapped on the screen as it was last drawn (for the tests that tap their way through). */
    @androidx.annotation.VisibleForTesting
    internal val tappable: List<Hit> get() = hits

    /** True while this is the screen on show. */
    protected var showing = false
        private set

    /** The unit the screen was last drawn in (see [Stage.u]); for sizes outside drawing, like how long a swipe is. */
    protected var unit = 7f
        private set

    init { lifecycle.addObserver(this) }

    protected abstract fun buttons(): List<Btn>
    protected abstract fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>)
    protected abstract fun tapped(key: String)

    /** Whether the sun / moon button is added (the start screen has its own). */
    protected open val themeButton: Boolean get() = true

    /** Whether swipes on the screen are wanted (lists turn pages with them). */
    protected open val swipes: Boolean get() = false
    protected open fun shown() {}
    protected open fun hidden() {}

    override fun onStart(owner: LifecycleOwner) { showing = true; ui.surface.attach(this); ui.safe("showing ${javaClass.simpleName}") { shown() } }
    override fun onStop(owner: LifecycleOwner) { showing = false; ui.surface.detach(this); ui.safe("hiding ${javaClass.simpleName}") { hidden() } }

    /** Draw again (soon; many calls make one drawing). */
    protected fun redraw() { if (showing) ui.surface.invalidate() }

    protected fun goBack() { try { screenManager.pop() } catch (e: Exception) { ui.log("going back", e) } }

    final override fun paint(c: Canvas, f: CarSurface.Frame) {
        val stage = Stage(f.width, f.height, f.dpi, f.visible, f.stable, hostIcons, hostChars)
        unit = stage.u
        val out = ArrayList<Hit>()
        draw(c, Kit(CarTheme.refresh(carContext), stage, ui.icons), pressed, out)
        hits = out
    }

    final override fun onTap(x: Float, y: Float) {
        // Where areas overlap, the one drawn last is on top.
        val h = hits.lastOrNull { it.rect.contains(x, y) } ?: return
        // Shown pressed for a moment; what it does happens at once.
        pressed = h.key
        redraw()
        main.postDelayed({ if (pressed == h.key) { pressed = null; redraw() } }, 170)
        ui.safe("tap on ${h.key}") { if (h.key == "back") { ui.sawBackTap(); goBack() } else tapped(h.key) }
    }

    final override fun onGetTemplate(): Template {
        val list = try { buttons().take(4) } catch (e: Exception) { ui.log("buttons of ${javaClass.simpleName}", e); emptyList() }
        val btns = ArrayList<Btn>()
        // A back button among Android Auto's own too (beside the drawn one at the top left), so there is always a
        // sure way back; and the sun / moon to switch between the dark and light look on every screen.
        if (screenManager.stackSize > 1) btns += Btn(R.drawable.ic_car_back) { goBack() }
        if (themeButton) btns += Btn(if (CarTheme.refresh(carContext).dark) R.drawable.ic_car_sun else R.drawable.ic_car_moon) {
            CarTheme.toggle(carContext); invalidate(); redraw()
        }
        btns += list
        if (btns.isEmpty()) btns += Btn(R.drawable.ic_car_refresh) { CarHub.refresh(carContext, force = true) }
        val shownBtns = btns.take(4)
        // The title is drawn beside these buttons, so it needs to know how much room they take.
        val icons = shownBtns.count { it.title == null }; val chars = shownBtns.sumOf { it.title?.length ?: 0 }
        if (icons != hostIcons || chars != hostChars) { hostIcons = icons; hostChars = chars; redraw() }
        return try { template(shownBtns, true) } catch (e: Exception) {
            ui.log("template of ${javaClass.simpleName}", e)
            template(shownBtns.filter { it.title == null }.ifEmpty { listOf(Btn(R.drawable.ic_car_refresh) { CarHub.refresh(carContext, force = true) }) }, false)
        }
    }

    private fun template(btns: List<Btn>, full: Boolean): Template {
        val strip = ActionStrip.Builder()
        // Android Auto draws its buttons dark; in the light look they are made light, with dark icons.
        val light = !CarTheme.refresh(carContext).dark
        val ink = CarColor.createCustom(0xFF1F2328.toInt(), 0xFF1F2328.toInt())
        for (b in btns) {
            val a = Action.Builder()
            if (b.title != null) a.setTitle(b.title)
            else a.setIcon(b.custom?.let { c -> if (light && b.tint == null) CarIcon.Builder(c).setTint(ink).build() else c }
                ?: carContext.icon(if (b.icon != 0) b.icon else R.drawable.ic_car_refresh, b.tint ?: if (light) ink else null))
            if (full && light && b.title == null) a.setBackgroundColor(CarColor.createCustom(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            a.setOnClickListener { ui.safe("button") { b.run() } }
            // Kept on show: buttons that fade away would make the free part of the screen, and so the layout, jump.
            if (full) a.setFlags(Action.FLAG_IS_PERSISTENT)
            strip.addAction(a.build())
        }
        val t = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (full && swipes) t.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        return t.build()
    }
}
