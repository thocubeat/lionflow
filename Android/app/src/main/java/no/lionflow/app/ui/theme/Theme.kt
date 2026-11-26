package no.lionflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LfColors(
    val isDark: Boolean,
    val bg: Color,
    val text: Color,
    val text2: Color,
    val chipText: Color,
    val muted: Color,
    val card: Color,
    val line: Color,
    val line2: Color,
    val track: Color,
    val nav: Color,
    val navLine: Color,
    val raised: Color,
    val raisedBlue: Color,
    val divider: Color,
    val bar: Color,
    val icon: Color,
    val slot: Color,
    val accent: Color,
    val accentInk: Color,
    val meter: Color,
    val orange: Color,
    val orangeInk: Color,
    val blueInk: Color,
    val okBg: Color,
    val okLine: Color,
    val okText: Color,
    val danger: Color,
    val onAccent: Color,
)

val DarkLf = LfColors(
    isDark = true,
    bg = Color(0xFF0D0E10), text = Color(0xFFF2F3F5), text2 = Color(0xFFC9CCD1), chipText = Color(0xFFD5D8DC),
    muted = Color(0xFF9A9EA6), card = Color(0xFF16181B), line = Color(0xFF23262A), line2 = Color(0xFF2A2D32),
    track = Color(0xFF26292E), nav = Color(0xFF121316), navLine = Color(0xFF1F2125), raised = Color(0xFF1E2024),
    raisedBlue = Color(0xFF1E2228), divider = Color(0xFF202327), bar = Color(0xFF3A3E45), icon = Color(0xFF6B7078),
    slot = Color(0xFF121417), accent = Color(0xFFC6F36B), accentInk = Color(0xFFC6F36B), meter = Color(0xFFC6F36B),
    orange = Color(0xFFFFB547), orangeInk = Color(0xFFFFB547), blueInk = Color(0xFF8AB4FF),
    okBg = Color(0xFF1A1E12), okLine = Color(0xFF2F3A1A), okText = Color(0xFFE4F7C2), danger = Color(0xFFE5484D),
    onAccent = Color(0xFF0D0E10),
)

val LightLf = LfColors(
    isDark = false,
    // A warm off-white page under near-white cards (with soft shadows), and dark warm-grey text for easy reading.
    bg = Color(0xFFEFEBE4), text = Color(0xFF14120F), text2 = Color(0xFF322E29), chipText = Color(0xFF262320),
    muted = Color(0xFF58534B), card = Color(0xFFFFFDFA), line = Color(0xFFE6E0D7), line2 = Color(0xFFD3CCC1),
    track = Color(0xFFE5DFD5), nav = Color(0xFFFFFDFA), navLine = Color(0xFFE2DBD1), raised = Color(0xFFF5F1EB),
    raisedBlue = Color(0xFFE9EEF8), divider = Color(0xFFEFEAE3), bar = Color(0xFFC6BEB2), icon = Color(0xFF746E65),
    slot = Color(0xFFF2EEE8), accent = Color(0xFFC6F36B), accentInk = Color(0xFF3F6600), meter = Color(0xFF558F00),
    orange = Color(0xFFFFB547), orangeInk = Color(0xFFA35F00), blueInk = Color(0xFF2560C8),
    okBg = Color(0xFFEEF5D8), okLine = Color(0xFFD0E39C), okText = Color(0xFF2A4300), danger = Color(0xFFC62B30),
    onAccent = Color(0xFF0D0E10),
)

val LocalLf = staticCompositionLocalOf { DarkLf }

object Type {
    val display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val title = display.copy(fontSize = 30.sp)
    val big = display.copy(fontSize = 52.sp, letterSpacing = (-1.5).sp)
    val stat = display.copy(fontSize = 22.sp)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp)
    val small = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp)
    val tiny = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun LionFlowTheme(themePref: String, content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val lf = if (dark) DarkLf else LightLf
    val scheme = if (dark) darkColorScheme(
        primary = lf.accent, onPrimary = lf.onAccent, background = lf.bg, surface = lf.card,
        onBackground = lf.text, onSurface = lf.text, surfaceVariant = lf.raised, outline = lf.line2,
    ) else lightColorScheme(
        primary = lf.meter, onPrimary = Color.White, background = lf.bg, surface = lf.card,
        onBackground = lf.text, onSurface = lf.text, surfaceVariant = lf.raised, outline = lf.line2,
    )
    CompositionLocalProvider(LocalLf provides lf) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
