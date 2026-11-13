package no.lionflow.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The stroke icons from the LionFlow design, drawn from their SVG path data. */
object LfIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun filled(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(pathData = addPathNodes(p), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Home = stroke("home", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val Bolt = stroke("bolt", "M13 2L4 14h7l-1 8 9-12h-7z")
    val BoltFilled = filled("boltf", "M13 2L4 14h7l-1 8 9-12h-7z")
    val Map = stroke("map", "M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z", "M9 4v14M15 6v14")
    val Thermo = stroke("thermo", "M14 14.76V4a2 2 0 1 0-4 0v10.76a4 4 0 1 0 4 0z")
    val Wrench = stroke("wrench", "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z")
    val Route = stroke("route", circle(6f, 19f, 2f), circle(18f, 5f, 2f), "M8 19h8a3 3 0 0 0 0-6H8a3 3 0 0 1 0-6h8")
    val Lock = stroke("lock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4")
    val Unlock = stroke("unlock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 7.5-2")
    val Refresh = stroke("refresh", "M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4")
    val Sun = stroke("sun", circle(12f, 12f, 4f), "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val Moon = stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Pin = stroke("pin", "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", circle(12f, 10f, 2.5f))
    val Bulb = stroke("bulb", "M12 3a6 6 0 0 0-3 11.2V17h6v-2.8A6 6 0 0 0 12 3z", "M10 21h4")
    val Horn = stroke("horn", "M4 10v4h4l6 4V6l-6 4z", "M18 9a4 4 0 0 1 0 6")
    val Camera = stroke("camera", "M4 7h3l2-3h6l2 3h3v13H4z", circle(12f, 13f, 3.5f))
    val Back = stroke("back", "M15 6l-6 6 6 6")
    val Chevron = stroke("chev", "M9 6l6 6-6 6")
    val Plus = stroke("plus", "M5 12h14M12 5v14", width = 2.2f)
    val Minus = stroke("minus", "M5 12h14", width = 2.2f)
    val Search = stroke("search", circle(11f, 11f, 7f), "M20 20l-4-4")
    val Locate = filled("locate", "M21 3L3 10.5l7.5 3 3 7.5z")
    val Fingerprint = stroke(
        "fp", "M12 11v3a8 8 0 0 1-1.2 4.2", "M8.5 9.5A3.5 3.5 0 0 1 15.5 10v2.5a12 12 0 0 1-.9 4.5",
        "M5.5 8a7.5 7.5 0 0 1 13 2v2a16 16 0 0 1-.6 4.5", "M6 12.5v1a11 11 0 0 1-1 4.5", "M12 3a9 9 0 0 1 8.4 5.8", width = 1.8f
    )
    val Backspace = stroke("bksp", "M21 5H9l-6 7 6 7h12z", "M15 9l-4 6M11 9l4 6")
    val Shield = stroke("shield", "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z", "M9 12l2 2 4-4")
    val Cloud = stroke("cloud", "M7 18h10a4 4 0 0 0 .6-8 6 6 0 0 0-11.4 1.5A3.3 3.3 0 0 0 7 18z")
    val Snow = stroke("snow", "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9")
    val Rain = stroke("rain", "M7 15h10a4 4 0 0 0 .6-8 6 6 0 0 0-11.4 1.5A3.3 3.3 0 0 0 7 15z", "M9 18l-1 2M13 18l-1 2M17 18l-1 2")
    val Check = stroke("check", "M5 12l5 5 9-10", width = 2.4f)
    val Download = stroke("dl", "M12 3v12M7 10l5 5 5-5", "M4 19h16")
    val Upload = stroke("ul", "M12 15V3M7 8l5-5 5 5", "M4 19h16")
    val Car = stroke("car", "M5 17h14l-1.5-6.5A2 2 0 0 0 15.6 9H8.4a2 2 0 0 0-1.9 1.5z", circle(7.5f, 17.5f, 1.5f), circle(16.5f, 17.5f, 1.5f))
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Fan = stroke("fan", "M12 12c-2-3-1-7 2-8 1 3-1 5-2 8zM12 12c3-2 7-1 8 2-3 1-5-1-8-2zM12 12c2 3 1 7-2 8-1-3 1-5 2-8zM12 12c-3 2-7 1-8-2 3-1 5 1 8 2z")
    val Plug = stroke("plug", "M9 2v6M15 2v6", "M6 8h12v3a6 6 0 0 1-12 0z", "M12 17v5")
    val Image = stroke("image", "M3 5h18v14H3z", circle(9f, 10f, 1.6f), "M21 16l-5-5-8 8")
}

@Composable
fun LfIcon(icon: ImageVector, tint: Color, size: Dp = 22.dp, contentDescription: String? = null, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}
