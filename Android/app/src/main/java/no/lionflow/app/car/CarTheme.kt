package no.lionflow.app.car

import androidx.car.app.CarContext
import no.lionflow.app.car.ui.Palette

/** Which look the drawn car screens use: dark or light as chosen with the sun / moon button, else as the car says. */
object CarTheme {
    private const val KEY = "carTheme"   // "dark", "light", or empty = follow the car

    /** The palette in use. Set from [refresh] by each screen before it draws. */
    @Volatile var p: Palette = Palette.DARK
        private set

    fun refresh(ctx: CarContext): Palette {
        val s = try { CarHub.app(ctx).settings.raw(KEY) } catch (_: Exception) { "" }
        p = when (s) { "light" -> Palette.LIGHT; "dark" -> Palette.DARK; else -> if (ctx.isDarkMode) Palette.DARK else Palette.LIGHT }
        return p
    }

    fun toggle(ctx: CarContext) {
        val next = if (refresh(ctx).dark) "light" else "dark"
        CarHub.app(ctx).settings.setRaw(KEY, next)
        refresh(ctx)
    }
}
