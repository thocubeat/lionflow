package no.lionflow.app.car

import android.app.Application
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import no.lionflow.app.R
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Follows taps, swipes and screen changes through the parts every drawn Android Auto screen shares, with Android
 * Auto's own test stand-ins for the car: the right screen gets the tap, the buttons make a valid template, and the
 * surface can be lent out and taken back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarScreensTest {
    /** A drawn screen with a title, a back button and one big tappable area. */
    private class Probe(
        ctx: CarContext, ui: CarUi, private val name: String, private val log: MutableList<String>,
        private val count: Int = 1, private val text: Boolean = false, private val swipe: Boolean = false,
    ) : DrawnScreen(ctx, ui) {
        override val swipes: Boolean get() = swipe
        override val themeButton: Boolean get() = false
        override fun buttons(): List<Btn> = (0 until count).map { i -> if (text && i == 0) Btn(title = "Save trip") { } else Btn(R.drawable.ic_car_refresh) { } }
        override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) {
            c.drawColor(k.pal.bg)
            k.header(c, name, null, true, pressed, hits)
            hits += Hit(RectF(k.st.body), "body")
        }
        override fun tapped(key: String) { log += "$name:$key" }
        override fun onScroll(dx: Float, dy: Float) { log += "$name:swipe" }
    }

    private fun idle() = CarTestKit.idle()

    @Test fun buttonsMakeValidTemplates() {
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        val ui = CarUi(ctx)
        val log = ArrayList<String>()
        // One button; four (the most Android Auto takes); one with a text; and a screen that takes swipes.
        for ((count, text, swipe) in listOf(Triple(1, false, false), Triple(4, false, false), Triple(2, true, false), Triple(1, false, true))) {
            val t = Probe(ctx, ui, "p", log, count, text, swipe).onGetTemplate() as NavigationTemplate
            val actions = t.actionStrip!!.actions
            assertEquals(count, actions.size)
            assertTrue("the buttons stay on show", actions.all { (it as Action).flags and Action.FLAG_IS_PERSISTENT != 0 })
            assertEquals(swipe, t.mapActionStrip != null)
        }
        ui.surface.close()
    }

    @Test fun tapsGoToTheScreenOnShow() {
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        val ui = CarUi(ctx)
        ui.surface.start()
        val car = ctx.getCarService(TestAppManager::class.java).surfaceCallback
        assertNotNull("the surface is asked for once, for the whole session", car)
        car!!
        val log = ArrayList<String>()
        val a = Probe(ctx, ui, "a", log); val b = Probe(ctx, ui, "b", log, swipe = true)
        // The app on show with a as its first screen; screens are pushed and popped as in the car.
        CarTestKit.onShow(ctx)
        val screens = ctx.getCarService(ScreenManager::class.java)
        screens.push(a); idle()
        assertEquals(a, screens.top)
        assertTrue("the first screen is started", a.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        // No real surface here: nothing reaches a screen, but the drawing is recorded and taps are found in it.
        car.onSurfaceAvailable(SurfaceContainer(null, 1280, 720, 160))
        car.onVisibleAreaChanged(Rect(0, 100, 1280, 720)); car.onStableAreaChanged(Rect(0, 100, 1280, 720))
        idle()
        car.onClick(640f, 400f); idle()
        assertEquals(listOf("a:body"), log)

        // Screen b is pushed on top: it gets the taps and swipes from then on.
        screens.push(b); idle()
        car.onClick(640f, 400f); car.onScroll(0f, 60f); idle()
        assertEquals(listOf("a:body", "b:body", "b:swipe"), log)

        // The drawn back button (top left) goes back to a.
        car.onClick(40f, 50f); idle()
        assertEquals(a, screens.top)
        car.onClick(640f, 400f); idle()
        assertEquals("a:body", log.last()); assertEquals(4, log.size)

        // A tap outside every tappable area does nothing.
        car.onClick(1279f, 719f); idle()
        assertEquals(4, log.size)

        // The surface lent to another screen (the video): that one gets the surface and the taps, until it is taken back.
        val lent = ArrayList<String>()
        val video = object : SurfaceCallback {
            override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) { lent += "surface ${surfaceContainer.width}" }
            override fun onClick(x: Float, y: Float) { lent += "tap" }
        }
        ui.surface.lend(video); idle()
        car.onClick(640f, 400f); idle()
        assertEquals(listOf("surface 1280", "tap"), lent); assertEquals(4, log.size)
        ui.surface.takeBack(video); idle()
        car.onClick(640f, 400f); idle()
        assertEquals(5, log.size); assertEquals(2, lent.size)

        // A new surface of another size (split screen to full screen): still the same screen, laid out again.
        car.onSurfaceAvailable(SurfaceContainer(null, 1920, 720, 160))
        car.onVisibleAreaChanged(Rect(0, 100, 1920, 720)); car.onStableAreaChanged(Rect(0, 100, 1920, 720)); idle()
        car.onClick(1800f, 400f); idle()
        assertEquals(6, log.size)

        // The surface taken away (another app on show) and given again: the screen still works.
        car.onSurfaceDestroyed(SurfaceContainer(null, 1920, 720, 160)); idle()
        car.onSurfaceAvailable(SurfaceContainer(null, 1920, 720, 160)); car.onVisibleAreaChanged(Rect(0, 100, 1216, 720)); idle()
        car.onClick(1800f, 400f); idle()   // now under the media card: not part of the screen any more
        assertEquals(6, log.size)
        car.onClick(600f, 400f); idle()
        assertEquals(7, log.size)
        ui.surface.close()
    }
}
