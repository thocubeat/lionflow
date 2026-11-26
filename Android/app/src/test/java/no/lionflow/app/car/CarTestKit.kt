package no.lionflow.app.car

import android.os.Looper
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.robolectric.Shadows.shadowOf

/** Shared by the tests of the Android Auto screens. */
internal object CarTestKit {
    /** Runs everything waiting on the main thread. */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Puts the test car's "app" on show. The screen stack follows this: with it on show, a screen that is pushed is
     * started and the one under it stopped, in the same order as in a real car.
     */
    fun onShow(ctx: TestCarContext) {
        val get = TestCarContext::class.java.getDeclaredMethod("getLifecycleOwner").apply { isAccessible = true }
        val registry = (get.invoke(ctx) as LifecycleOwner).lifecycle as LifecycleRegistry
        registry.currentState = Lifecycle.State.RESUMED
        idle()
    }
}
