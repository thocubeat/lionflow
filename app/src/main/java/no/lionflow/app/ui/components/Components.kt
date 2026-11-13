package no.lionflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

@Composable
fun LfCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(radius)
    var m = modifier.clip(shape).background(c.card).border(1.dp, c.line, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Chip(text: String, icon: ImageVector? = null, iconTint: Color? = null, onClick: (() -> Unit)? = null) {
    val c = LocalLf.current
    var m = Modifier.clip(CircleShape).background(c.card).border(1.dp, c.line, CircleShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) LfIcon(icon, iconTint ?: c.chipText, 14.dp)
        Text(text, style = Type.small, color = c.chipText)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalLf.current
    val bg = color ?: c.accent
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else c.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LfIcon(icon, if (enabled) c.onAccent else c.muted, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.label.copy(fontSize = 16.sp), color = if (enabled) c.onAccent else c.muted)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, textColor: Color? = null, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .border(1.dp, c.line2, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = textColor ?: c.text) }
}

@Composable
fun CircleIconButton(icon: ImageVector, description: String, size: Dp = 44.dp, filled: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) c.accent else c.card)
            .border(if (filled) 0.dp else 1.dp, c.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LfIcon(icon, tint ?: if (filled) c.onAccent else c.text2, 18.dp) }
}

@Composable
fun Toggle(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = LocalLf.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label; stateDescription = if (on) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.width(52.dp).height(30.dp).clip(CircleShape).background(if (on) c.accent else c.line2).padding(3.dp),
            horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        ) { Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) c.onAccent else c.muted)) }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, on: Boolean, showDivider: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalLf.current
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = Type.label, color = c.text)
                if (subtitle != null) Text(subtitle, style = Type.tiny, color = c.muted)
            }
            Spacer(Modifier.width(12.dp))
            Toggle(on, title, onChange)
        }
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = LocalLf.current
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(c.raised).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((id, label) in options) {
            val active = id == selected
            Box(
                Modifier
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) c.card else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(id) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.label.copy(fontSize = 14.sp), color = if (active) c.text else c.muted) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    val c = LocalLf.current
    Text(text.uppercase(), style = Type.tiny.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
}

@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape), content = content)
}

@Composable
fun ProgressBar(fraction: Float, color: Color, height: Dp = 10.dp, marker: Float? = null) {
    val c = LocalLf.current
    Box(Modifier.fillMaxWidth().height(height + 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.track))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
        if (marker != null) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.fillMaxWidth(marker.coerceIn(0f, 0.99f)))
                Box(Modifier.width(2.dp).height(height + 8.dp).background(c.text))
            }
        }
    }
}

@Composable
fun Wordmark(fontSize: Int = 15) {
    val c = LocalLf.current
    Text(
        buildAnnotatedString {
            append("Lion")
            withStyle(SpanStyle(color = c.accentInk)) { append("Flow") }
        },
        style = Type.display.copy(fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
        color = c.text,
    )
}

@Composable
fun ScreenHeader(kicker: String?, title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalLf.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kicker != null) Text(kicker, style = Type.small.copy(fontSize = 14.sp), color = c.muted)
            Text(title, style = Type.title, color = c.text)
        }
        trailing()
    }
}

private val MANE = "M50.0 8.9 L57.8 18.6 L70.4 10.5 L71.8 25.2 L86.7 23.3 L81.5 37.1 L95.8 41.8 L85.0 52.0 L95.8 62.2 " +
    "L81.5 66.9 L86.7 80.7 L71.8 78.8 L70.4 93.5 L57.8 85.4 L50.0 98.1 L42.2 85.4 L29.6 93.5 L28.2 78.8 L13.3 80.7 " +
    "L18.5 66.9 L4.2 62.2 L15.0 52.0 L4.2 41.8 L18.5 37.1 L13.3 23.3 L28.2 25.2 L29.6 10.5 L42.2 18.6 Z"

private fun pathOf(points: List<Pair<Float, Float>>): Path = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    close()
}

private val manePts: List<Pair<Float, Float>> = MANE.replace("M", "").replace("Z", "").split("L").map { seg ->
    val (x, y) = seg.trim().split(" ").map { it.toFloat() }
    x to y
}

/** The LionFlow lion, drawn natively. */
@Composable
fun LionLogo(size: Dp, accent: Color? = null, face: Color? = null) {
    val c = LocalLf.current
    val a = accent ?: c.accentInk
    val f = face ?: c.bg
    Canvas(Modifier.size(size).semantics { contentDescription = "LionFlow" }) {
        val s = this.size.minDimension / 106f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(pathOf(manePts), a)
            drawPath(pathOf(listOf(50f to 22f, 69f to 31f, 74f to 54f, 63f to 75f, 50f to 82f, 37f to 75f, 26f to 54f, 31f to 31f)), f)
            drawPath(pathOf(listOf(31f to 43f, 46f to 49f, 44f to 52.5f, 34f to 50.5f)), a)
            drawPath(pathOf(listOf(69f to 43f, 54f to 49f, 56f to 52.5f, 66f to 50.5f)), a)
            drawLine(a, Offset(47f, 53f), Offset(45f, 61f), 2.2f, StrokeCap.Round)
            drawLine(a, Offset(53f, 53f), Offset(55f, 61f), 2.2f, StrokeCap.Round)
            drawPath(pathOf(listOf(43f to 61f, 57f to 61f, 50f to 68f)), a)
            drawLine(a, Offset(50f, 68f), Offset(50f, 72f), 2.2f, StrokeCap.Round)
            drawLine(a, Offset(50f, 72f), Offset(42f, 75.5f), 2.2f, StrokeCap.Round)
            drawLine(a, Offset(50f, 72f), Offset(58f, 75.5f), 2.2f, StrokeCap.Round)
            drawPath(pathOf(listOf(43.5f to 75f, 46.5f to 74f, 45.3f to 79.5f)), a)
            drawPath(pathOf(listOf(56.5f to 75f, 53.5f to 74f, 54.7f to 79.5f)), a)
        }
    }
}

@Composable
fun Ring(fraction: Float, color: Color, size: Dp, stroke: Dp = 16.dp) {
    val c = LocalLf.current
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        drawArc(c.track, 0f, 360f, false, style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round), topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw))
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

@Composable
fun Divider() {
    val c = LocalLf.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

fun Modifier.outlined(color: Color, radius: Dp) = this.border(BorderStroke(1.dp, color), RoundedCornerShape(radius))
