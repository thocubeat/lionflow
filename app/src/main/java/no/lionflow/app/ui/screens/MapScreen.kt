package no.lionflow.app.ui.screens

import android.content.Context
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import no.lionflow.app.api.Charger
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val STYLE_FALLBACK = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun MapScreen(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val ctx = LocalContext.current
    val platform = LocalPlatform.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf<Style?>(null) }
    var is3d by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<Charger?>(null) }
    var bearing by remember { mutableStateOf(0f) }
    val styleUrl = if (c.isDark) STYLE_DARK else STYLE_LIGHT

    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs) // replays ON_START/ON_RESUME for the current state
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // Load map + style (re-load when theme changes)
    LaunchedEffect(styleUrl) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.addOnCameraMoveListener { bearing = m.cameraPosition.bearing.toFloat() }
            m.addOnMapClickListener { p ->
                val pt: PointF = m.projection.toScreenLocation(p)
                val hits = m.queryRenderedFeatures(pt, "lf-chargers")
                val id = hits.firstOrNull()?.getStringProperty("id")
                selected = vm.ui.value.chargers.firstOrNull { it.id == id }
                id != null
            }
            mapView.addOnDidFailLoadingMapListener { _ -> m.setStyle(Style.Builder().fromUri(STYLE_FALLBACK)) { st -> styleLoaded = st } }
            m.setStyle(Style.Builder().fromUri(styleUrl)) { st -> styleLoaded = st }
            val center = ui.myLat?.let { LatLng(it, ui.myLon ?: 0.0) } ?: ui.status?.lat?.let { LatLng(it, ui.status?.lon ?: 0.0) } ?: LatLng(59.91, 10.75)
            m.cameraPosition = CameraPosition.Builder().target(center).zoom(13.0).build()
        }
    }
    LaunchedEffect(Unit) {
        if (ui.myLat == null) platform.requestLocation()
        if (ui.chargers.isEmpty()) vm.loadChargers()
    }

    // Keep data layers in sync
    val visible = ui.chargers.filter { filter == "all" || it.maxKw >= 50 }
    LaunchedEffect(styleLoaded, visible, ui.myLat, ui.status?.lat, is3d, c.isDark) {
        val st = styleLoaded ?: return@LaunchedEffect
        try { updateLayers(st, visible, ui, c.accent, c.isDark, is3d) } catch (_: Exception) { }
        map?.let { m ->
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition).tilt(if (is3d) 60.0 else 0.0).zoom(if (is3d) maxOf(m.cameraPosition.zoom, 15.0) else m.cameraPosition.zoom).build()
            ), 600)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // Top: filters
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", filter == "all") { filter = "all" }
                FilterChip("Fast 50 kW+", filter == "fast") { filter = "fast" }
            }
            ui.chargerError?.let {
                Text(it, style = Type.small, color = c.text, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { vm.setTab(no.lionflow.app.ui.Tab.Settings) })
            }
        }

        // Right: controls
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                MapButton("2D", !is3d) { is3d = false }
                Box(Modifier.width(48.dp).height(1.dp).background(c.line))
                MapButton("3D", is3d) { is3d = true }
            }
            Column(Modifier.shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                IconMapButton(LfIcons.Plus, "Zoom in") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                Box(Modifier.width(48.dp).height(1.dp).background(c.line))
                IconMapButton(LfIcons.Minus, "Zoom out") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
            }
            Box(
                Modifier.size(48.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.card)
                    .clickable(role = Role.Button) { map?.animateCamera(CameraUpdateFactory.bearingTo(0.0)) }
                    .semantics { contentDescription = "Reset rotation to north" },
                contentAlignment = Alignment.Center,
            ) { CompassNeedle(bearing) }
            Box(
                Modifier.size(48.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.card)
                    .clickable(role = Role.Button) {
                        vm.loadLocationAndWeather()
                        val t = ui.myLat?.let { LatLng(it, ui.myLon ?: 0.0) } ?: ui.status?.lat?.let { LatLng(it, ui.status?.lon ?: 0.0) }
                        if (t != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(t, 14.0)) else platform.requestLocation()
                    }
                    .semantics { contentDescription = "Show my location" },
                contentAlignment = Alignment.Center,
            ) { LfIcon(LfIcons.Locate, Color(0xFF3D7BE0), 20.dp) }
        }

        // Bottom sheet
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp).fillMaxWidth().padding(12.dp)
                .shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val sel = selected
            if (sel != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(sel.name, style = Type.label.copy(fontSize = 17.sp), color = c.text)
                        Text(listOfNotNull(sel.address.ifBlank { null }, if (sel.maxKw > 0) "${sel.maxKw.toInt()} kW" else null, "${sel.points} points").joinToString(" · "), style = Type.small, color = c.muted)
                    }
                    LfIcon(LfIcons.Close, c.muted, 20.dp, "Close", Modifier.clickable { selected = null })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Navigate", Modifier.weight(1f), icon = LfIcons.Locate) {
                        platform.openUrl("google.navigation:q=${sel.lat},${sel.lon}")
                    }
                    SecondaryButton("Open in Maps") { platform.openUrl("geo:${sel.lat},${sel.lon}?q=${sel.lat},${sel.lon}(${android.net.Uri.encode(sel.name)})") }
                }
            } else {
                val st = ui.status
                val carLat = st?.lat
                val carLon = st?.lon
                val moving = st?.moving == true
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Car, c.accentInk, 20.dp) }
                    Column(Modifier.weight(1f)) {
                        Text("Your car", style = Type.label, color = c.text)
                        Text(if (carLat != null) (if (moving) "Driving" else "Parked") + " · tap a green dot for charger details" else "Position not available yet", style = Type.small, color = c.muted)
                    }
                    if (carLat != null && carLon != null) Text("Go", style = Type.label, color = c.accentInk, modifier = Modifier.clickable {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(carLat, carLon), 15.0))
                    }.padding(8.dp))
                }
            }
            Text("Map © OpenStreetMap contributors · OpenFreeMap" + if (ui.chargers.isNotEmpty()) " · Chargers: NOBIL" else "", style = Type.tiny.copy(fontSize = 10.sp), color = c.muted,
                modifier = Modifier.clickable { platform.openUrl("https://www.openstreetmap.org/copyright") })
        }
    }
}

private fun updateLayers(style: Style, chargers: List<Charger>, ui: UiState, accent: Color, dark: Boolean, is3d: Boolean) {
    // Chargers
    val features = chargers.map { ch ->
        Feature.fromGeometry(Point.fromLngLat(ch.lon, ch.lat)).apply {
            addStringProperty("id", ch.id)
            addBooleanProperty("fast", ch.maxKw >= 50)
        }
    }
    val chargerFc = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>("lf-chargers-src")
    if (src == null) {
        style.addSource(GeoJsonSource("lf-chargers-src", chargerFc))
        style.addLayer(CircleLayer("lf-chargers", "lf-chargers-src").withProperties(
            PropertyFactory.circleRadius(Expression.switchCase(Expression.get("fast"), Expression.literal(9f), Expression.literal(7f))),
            PropertyFactory.circleColor(accent.toArgb()),
            PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0D0E10")),
            PropertyFactory.circleStrokeWidth(2f),
        ))
    } else src.setGeoJson(chargerFc)

    // Car + me
    val points = mutableListOf<Feature>()
    ui.status?.lat?.let { la -> ui.status?.lon?.let { lo -> points += Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addStringProperty("kind", "car") } } }
    ui.myLat?.let { la -> ui.myLon?.let { lo -> points += Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addStringProperty("kind", "me") } } }
    val pfc = FeatureCollection.fromFeatures(points)
    val psrc = style.getSourceAs<GeoJsonSource>("lf-points-src")
    if (psrc == null) {
        style.addSource(GeoJsonSource("lf-points-src", pfc))
        style.addLayer(CircleLayer("lf-points-halo", "lf-points-src").withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(android.graphics.Color.parseColor("#3D7BE0")),
            PropertyFactory.circleOpacity(0.18f),
        ))
        style.addLayer(CircleLayer("lf-points", "lf-points-src").withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.color(android.graphics.Color.parseColor("#FFB547")),
                Expression.stop("car", Expression.color(android.graphics.Color.parseColor("#FFB547"))),
                Expression.stop("me", Expression.color(android.graphics.Color.parseColor("#3D7BE0"))))),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ))
    } else psrc.setGeoJson(pfc)

    // 3D buildings (vector only)
    val has3d = style.getLayer("lf-3d") != null
    if (is3d && !has3d) {
        val sourceId = listOf("openmaptiles", "composite").firstOrNull { style.getSource(it) != null }
        if (sourceId != null) {
            val layer = FillExtrusionLayer("lf-3d", sourceId).apply {
                sourceLayer = "building"
                setProperties(
                    PropertyFactory.fillExtrusionColor(android.graphics.Color.parseColor(if (dark) "#2A2E35" else "#D7DBE0")),
                    PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
                    PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
                    PropertyFactory.fillExtrusionOpacity(0.85f),
                )
            }
            val below = style.getLayer("lf-chargers")
            if (below != null) style.addLayerBelow(layer, "lf-chargers") else style.addLayer(layer)
        }
    } else if (!is3d && has3d) {
        style.removeLayer("lf-3d")
    }
}

@Composable
private fun FilterChip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(
        Modifier.heightIn(min = 38.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(if (active) c.text else c.card)
            .border(1.dp, if (active) c.text else c.line, CircleShape).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (active) c.card else c.text) }
}

@Composable
private fun MapButton(text: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(Modifier.width(48.dp).height(44.dp).background(if (active) c.text else Color.Transparent).clickable(role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Type.small.copy(fontWeight = FontWeight.Bold), color = if (active) c.card else c.muted)
    }
}

@Composable
private fun IconMapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(Modifier.width(48.dp).height(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        LfIcon(icon, c.text, 18.dp)
    }
}

@Composable
private fun CompassNeedle(bearing: Float) {
    val c = LocalLf.current
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        rotate(-bearing) {
            val north = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close() }
            val south = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h); lineTo(w * 0.32f, h / 2); lineTo(w * 0.68f, h / 2); close() }
            drawPath(north, Color(0xFFE5484D))
            drawPath(south, c.muted)
        }
    }
}

@Suppress("unused")
private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
