package no.lionflow.app.ui.screens

import android.content.Context
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import no.lionflow.app.plan.Planner
import no.lionflow.app.plan.RouteData
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
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
import no.lionflow.app.ui.l10n.Text
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
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val p = vm.planner
    /** The trip planner is for fully electric cars. */
    val electric = ui.status?.energyType == "Electric"
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf<Style?>(null) }
    var is3d by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<Charger?>(null) }
    /** A charger along the planned route that was tapped on the map. */
    var selPlan by remember { mutableStateOf<String?>(null) }
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
            val first = map == null
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            if (first) {
                m.addOnCameraMoveListener { bearing = m.cameraPosition.bearing.toFloat() }
                m.addOnMapClickListener { pt ->
                    val at: PointF = m.projection.toScreenLocation(pt)
                    val planHit = try { m.queryRenderedFeatures(at, "lf-plan-stops", "lf-plan-chargers").firstOrNull()?.getStringProperty("id") } catch (_: Exception) { null }
                    if (planHit != null) { selPlan = planHit; selected = null; true }
                    else {
                        val id = try { m.queryRenderedFeatures(at, "lf-chargers").firstOrNull()?.getStringProperty("id") } catch (_: Exception) { null }
                        selected = vm.ui.value.chargers.firstOrNull { it.id == id }
                        selPlan = null
                        id != null
                    }
                }
                mapView.addOnDidFailLoadingMapListener { _ -> m.setStyle(Style.Builder().fromUri(STYLE_FALLBACK)) { st -> styleLoaded = st } }
                val center = ui.myLat?.let { LatLng(it, ui.myLon ?: 0.0) } ?: ui.status?.lat?.let { LatLng(it, ui.status?.lon ?: 0.0) } ?: LatLng(59.91, 10.75)
                m.cameraPosition = CameraPosition.Builder().target(center).zoom(13.0).build()
            }
            m.setStyle(Style.Builder().fromUri(styleUrl)) { st -> styleLoaded = st }
        }
    }
    LaunchedEffect(Unit) {
        if (ui.myLat == null) platform.requestLocation()
        if (ui.chargers.isEmpty()) vm.loadChargers()
    }

    // Keep data layers in sync
    val planning = p.open && p.route != null
    val routeJson = remember(p.route) { p.route?.let { lineJson(it) } }
    val visible = if (planning) emptyList() else ui.chargers.filter { filter == "all" || it.maxKw >= 50 }
    LaunchedEffect(styleLoaded, visible, ui.myLat, ui.status?.lat, is3d, c.isDark, routeJson, p.plan, p.chargers, planning) {
        val st = styleLoaded ?: return@LaunchedEffect
        try { updateLayers(ctx, st, visible, ui, c.accent, c.isDark, is3d, if (planning) routeJson else null, if (planning) p else null) } catch (_: Exception) { }
    }
    LaunchedEffect(is3d) {
        map?.let { m ->
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition).tilt(if (is3d) 60.0 else 0.0).zoom(if (is3d) maxOf(m.cameraPosition.zoom, 15.0) else m.cameraPosition.zoom).build()
            ), 600)
        }
    }
    LaunchedEffect(planning) { if (!planning) selPlan = null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Big screens: the planner sits beside the map. Phones: a full-screen form, then a sheet over the map.
        val wide = maxWidth >= 720.dp
        val fullH = maxHeight
        val showForm = p.open && !wide && (p.editing || p.plan == null)
        val showSheet = p.open && !wide && !showForm
        fun showOnMap(la: Double, lo: Double) { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), 13.5), 700) }

        BackHandler(enabled = p.open) { if (!wide && p.editing && p.plan != null) p.editing = false else p.close() }

        // Show the whole route when a new one arrives.
        LaunchedEffect(p.route, map, wide) {
            val r = p.route ?: return@LaunchedEffect
            val m = map ?: return@LaunchedEffect
            if (r.size < 2) return@LaunchedEffect
            val b = LatLngBounds.Builder()
            val step = maxOf(1, r.size / 300)
            var i = 0
            while (i < r.size) { b.include(LatLng(r.lat[i], r.lon[i])); i += step }
            b.include(LatLng(r.lat[r.size - 1], r.lon[r.size - 1]))
            fun px(dp: androidx.compose.ui.unit.Dp) = with(density) { dp.toPx() }.toInt()
            try {
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), px(if (wide) 430.dp else 40.dp), px(110.dp), px(76.dp), px(if (wide) 48.dp else fullH * 0.6f)), 900)
            } catch (_: Exception) {
                try { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), px(40.dp)), 900) } catch (_: Exception) { }
            }
        }

        AndroidView({ mapView }, Modifier.fillMaxSize())

        // Top: trip search and filters, or the trip being planned
        if (!p.open) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (electric) Row(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.card).border(1.dp, c.line, CircleShape)
                        .clickable(role = Role.Button) {
                            p.open = true; p.editing = true; selected = null
                            if (ui.trips.isEmpty() && !ui.tripsLoading) vm.loadTrips()
                        }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LfIcon(LfIcons.Search, c.muted, 18.dp)
                    Text("Plan a trip: where to?", style = Type.body, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1)
                    LfIcon(LfIcons.Route, c.accentInk, 18.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", filter == "all") { filter = "all" }
                    FilterChip("Fast 50 kW+", filter == "fast") { filter = "fast" }
                }
                ui.chargerError?.let {
                    Text(it, style = Type.small, color = c.text, maxLines = 2, modifier = Modifier.padding(end = 64.dp).clip(RoundedCornerShape(12.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable { vm.loadChargers() })
                }
            }
        } else if (showSheet) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).heightIn(min = 48.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                    .background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).clickable(role = Role.Button) { p.editing = true }.padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LfIcon(LfIcons.Route, c.accentInk, 18.dp)
                androidx.compose.material3.Text(
                    if (p.planRound) listOfNotNull(p.planFrom?.let { placeName(it) }, p.planVias.lastOrNull()?.let { placeName(it) }).joinToString(" ⇄ ")
                    else listOfNotNull(p.planFrom?.let { placeName(it) }, p.planTo?.let { placeName(it) }).joinToString(" → "),
                    modifier = Modifier.weight(1f), style = Type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LfIcon(LfIcons.Close, c.muted, 20.dp, "Close the trip", Modifier.clip(CircleShape).clickable { p.close() }.padding(8.dp))
            }
        }

        // Right: controls
        if (!showForm) Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // Bottom card (not planning): the tapped charger, or the car
        if (!p.open) Column(
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
                        Text(if (carLat != null) (if (moving) "Driving" else "Parked") + " · tap a charger for details" else "Position not available yet", style = Type.small, color = c.muted)
                    }
                    if (carLat != null && carLon != null) Text("Go", style = Type.label, color = c.accentInk, modifier = Modifier.clickable {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(carLat, carLon), 15.0))
                    }.padding(8.dp))
                }
            }
            Text("Map © OpenStreetMap contributors · OpenFreeMap" + if (ui.chargers.isNotEmpty()) (if (s.chargerSource == "nobil") " · Chargers: NOBIL" else " · Chargers: OpenStreetMap") else "", style = Type.tiny.copy(fontSize = 10.sp), color = c.muted,
                modifier = Modifier.clickable { platform.openUrl("https://www.openstreetmap.org/copyright") })
        }

        // Planner, big screens: a panel beside the map with the form on top and the plan under it
        if (p.open && wide) Column(
            Modifier.align(Alignment.TopStart).width(392.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)
                .shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(24.dp)).blockTouches(),
        ) {
            PlanHeader(back = false) { p.close() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PlanForm(p, ui, s)
                if (p.plan != null) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    PlanResult(p, s, options = false) { la, lo -> showOnMap(la, lo) }
                }
            }
        }

        // Planner, phones: the address form fills the screen (so the keyboard has room)…
        if (showForm) Column(Modifier.fillMaxSize().background(c.bg).blockTouches().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
            PlanHeader(back = true) { if (p.plan != null) p.editing = false else p.close() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PlanForm(p, ui, s)
            }
        }

        // …and the plan is a sheet over the map: drag or tap the handle to make it small or tall
        if (showSheet) {
            var tall by remember { mutableStateOf(true) }
            var drag by remember { mutableStateOf(0f) }
            val sheetH by animateDpAsState(if (tall) fullH * 0.58f else 172.dp, label = "sheet")
            val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetH).shadow(16.dp, shape).clip(shape).background(c.card).blockTouches()) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .draggable(rememberDraggableState { drag += it }, Orientation.Vertical, onDragStopped = { if (drag < -16f) tall = true else if (drag > 16f) tall = false; drag = 0f })
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tall = !tall },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(c.bar)) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlanResult(p, s, options = true) { la, lo -> tall = false; showOnMap(la, lo) }
                }
            }
        }

        // A charger along the route that was tapped on the map
        val sel = selPlan?.let { id -> p.chargers.firstOrNull { it.id == id } }
        if (p.open && sel != null && !showForm) {
            val isStop = p.plan?.stops?.any { it.charger.id == sel.id } == true
            PlanChargerCard(
                sel, isStop, s,
                if (wide) Modifier.align(Alignment.BottomCenter).padding(start = 404.dp, end = 12.dp, bottom = 16.dp).widthIn(max = 420.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp))
                else Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 72.dp, top = 68.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp)),
                onAdd = { p.addStop(sel.id); selPlan = null }, onRemove = { p.removeStop(sel.id); selPlan = null }, onClose = { selPlan = null },
            )
        }
    }
}

@Composable
private fun PlanHeader(back: Boolean, onClose: () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(start = if (back) 6.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) LfIcon(LfIcons.Back, c.text, 22.dp, "Back", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(10.dp))
        Text("Plan a trip", style = Type.stat, color = c.text, modifier = Modifier.weight(1f))
        if (!back) LfIcon(LfIcons.Close, c.muted, 22.dp, "Close", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(10.dp))
    }
}

/** The route as a GeoJSON line (points closer than ~30 m are skipped to keep it light). */
private fun lineJson(r: RouteData): String {
    val sb = StringBuilder("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
    var last = -1.0
    var first = true
    for (i in 0 until r.size) {
        if (i != r.size - 1 && last >= 0 && r.km[i] - last < 0.03) continue
        if (!first) sb.append(',')
        sb.append('[').append(String.format(java.util.Locale.US, "%.5f,%.5f", r.lon[i], r.lat[i])).append(']')
        last = r.km[i]; first = false
    }
    return sb.append("]}}").toString()
}

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

/** Round map symbols drawn here, so the map needs no icon files or fonts. */
private fun addSymbols(ctx: Context, style: Style, accent: Int) {
    if (style.getImage("lf-ch") != null) return
    val d = ctx.resources.displayMetrics.density
    val dark = android.graphics.Color.parseColor("#0D0E10")
    val white = android.graphics.Color.WHITE
    fun disc(dp: Float, ring: Int, ringDp: Float, fill: Int, draw: (android.graphics.Canvas, Float) -> Unit): android.graphics.Bitmap {
        val px = (dp * d).toInt().coerceAtLeast(8)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        paint.color = ring; cv.drawCircle(r, r, r - 0.5f, paint)
        paint.color = fill; cv.drawCircle(r, r, r - ringDp * d, paint)
        draw(cv, px.toFloat())
        return bmp
    }
    fun bolt(cv: android.graphics.Canvas, size: Float, color: Int) {
        val k = size / 24f * 0.6f
        val path = android.graphics.Path().apply { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(10f, 22f); lineTo(19f, 10f); lineTo(12f, 10f); close() }
        path.transform(android.graphics.Matrix().apply { setScale(k, k); postTranslate(size / 2f - 11.5f * k, size / 2f - 12f * k) })
        cv.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = android.graphics.Paint.Style.FILL })
    }
    fun number(cv: android.graphics.Canvas, size: Float, text: String, color: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textAlign = android.graphics.Paint.Align.CENTER; textSize = size * 0.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        cv.drawText(text, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
    }
    style.addImage("lf-ch-fast", disc(26f, dark, 2f, accent) { cv, sz -> bolt(cv, sz, dark) })
    style.addImage("lf-ch", disc(20f, accent, 1.5f, android.graphics.Color.parseColor("#16181B")) { cv, sz -> bolt(cv, sz, accent) })
    style.addImage("lf-start", disc(22f, white, 3f, android.graphics.Color.parseColor("#FFB547")) { _, _ -> })
    style.addImage("lf-via", disc(20f, white, 3f, android.graphics.Color.parseColor("#6B7280")) { _, _ -> })
    style.addImage("lf-end", disc(26f, white, 3f, dark) { cv, sz ->
        cv.drawCircle(sz / 2f, sz / 2f, sz * 0.14f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = white })
    })
    for (n in 0..12) style.addImage("lf-stop-$n", disc(34f, white, 3f, accent) { cv, sz -> if (n == 0) bolt(cv, sz, dark) else number(cv, sz, "$n", dark) })
    addCarSymbols(ctx, style, accent)
}

/**
 * The car seen from above: a rounded rectangle with a small arrow at the front. Drawn pointing north; the map turns it
 * to the way the car points ("lf-car-plain" without the arrow, when the car doesn't say).
 */
private fun addCarSymbols(ctx: Context, style: Style, accent: Int) {
    if (style.getImage("lf-car") != null) return
    val d = ctx.resources.displayMetrics.density
    fun car(arrow: Boolean): android.graphics.Bitmap {
        val w = (30 * d).toInt(); val h = (44 * d).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val body = android.graphics.RectF(6 * d, 13 * d, w - 6 * d, h - 3 * d)
        paint.color = android.graphics.Color.WHITE
        cv.drawRoundRect(android.graphics.RectF(body.left - 2.5f * d, body.top - 2.5f * d, body.right + 2.5f * d, body.bottom + 2.5f * d), 7 * d, 7 * d, paint)
        paint.color = android.graphics.Color.parseColor("#0D0E10")
        cv.drawRoundRect(body, 5 * d, 5 * d, paint)
        paint.color = accent
        cv.drawRoundRect(android.graphics.RectF(body.left + 2.5f * d, body.top + 2.5f * d, body.right - 2.5f * d, body.bottom - 2.5f * d), 3.5f * d, 3.5f * d, paint)
        // The windscreen, so the front is clear even without the arrow.
        paint.color = android.graphics.Color.parseColor("#0D0E10")
        cv.drawRoundRect(android.graphics.RectF(body.left + 5 * d, body.top + 6 * d, body.right - 5 * d, body.top + 11 * d), 2 * d, 2 * d, paint)
        if (arrow) {
            val tip = android.graphics.Path().apply { moveTo(w / 2f, 1 * d); lineTo(w / 2f + 7 * d, 10 * d); lineTo(w / 2f - 7 * d, 10 * d); close() }
            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.strokeJoin = android.graphics.Paint.Join.ROUND
            cv.drawPath(tip, paint)
            paint.style = android.graphics.Paint.Style.FILL; paint.color = accent
            cv.drawPath(tip, paint)
        }
        return bmp
    }
    style.addImage("lf-car", car(true))
    style.addImage("lf-car-plain", car(false))
}

private fun updateLayers(ctx: Context, style: Style, chargers: List<Charger>, ui: UiState, accent: Color, dark: Boolean, is3d: Boolean, routeJson: String?, planner: Planner?) {
    addSymbols(ctx, style, accent.toArgb())
    // All layers once, bottom to top: route, nearby chargers, chargers along the route, stops, car and phone.
    if (style.getSource("lf-route-src") == null) {
        style.addSource(GeoJsonSource("lf-route-src", EMPTY))
        style.addLayer(LineLayer("lf-route-case", "lf-route-src").withProperties(
            PropertyFactory.lineColor(if (dark) "#0B1730" else "#FFFFFF"), PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("lf-route", "lf-route-src").withProperties(
            PropertyFactory.lineColor("#2F7CF6"), PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addSource(GeoJsonSource("lf-chargers-src", EMPTY))
        style.addLayer(SymbolLayer("lf-chargers", "lf-chargers-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addSource(GeoJsonSource("lf-plan-ch-src", EMPTY))
        style.addLayer(SymbolLayer("lf-plan-chargers", "lf-plan-ch-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconIgnorePlacement(false)))
        style.addSource(GeoJsonSource("lf-plan-stops-src", EMPTY))
        style.addLayer(SymbolLayer("lf-plan-stops", "lf-plan-stops-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addSource(GeoJsonSource("lf-points-src", EMPTY))
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
    }

    // The car, turned the way it points (on top of everything else)
    addCarSymbols(ctx, style, accent.toArgb())
    if (style.getSource("lf-car-src") == null) {
        style.addSource(GeoJsonSource("lf-car-src", EMPTY))
        style.addLayer(SymbolLayer("lf-car", "lf-car-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.get("heading")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }
    val st = ui.status
    style.getSourceAs<GeoJsonSource>("lf-car-src")?.setGeoJson(FeatureCollection.fromFeatures(
        if (st?.lat != null && st.lon != null) listOf(Feature.fromGeometry(Point.fromLngLat(st.lon, st.lat)).apply {
            addStringProperty("icon", if (st.heading != null) "lf-car" else "lf-car-plain")
            addNumberProperty("heading", st.heading ?: 0.0)
        }) else emptyList()))

    // Nearby chargers
    style.getSourceAs<GeoJsonSource>("lf-chargers-src")?.setGeoJson(FeatureCollection.fromFeatures(chargers.map { ch ->
        Feature.fromGeometry(Point.fromLngLat(ch.lon, ch.lat)).apply {
            addStringProperty("id", ch.id)
            addStringProperty("icon", if (ch.maxKw >= 50) "lf-ch-fast" else "lf-ch")
        }
    }))

    // The planned trip: the road, the chargers along it, the stops, and both ends
    val route = planner?.route
    style.getSourceAs<GeoJsonSource>("lf-route-src")?.setGeoJson(if (route != null && routeJson != null) routeJson else EMPTY)
    val stops = planner?.plan?.stops.orEmpty()
    val stopIds = stops.map { it.charger.id }.toSet()
    style.getSourceAs<GeoJsonSource>("lf-plan-ch-src")?.setGeoJson(FeatureCollection.fromFeatures(
        if (route == null) emptyList() else planner?.chargers.orEmpty().filter { it.id !in stopIds }.map { ch ->
            Feature.fromGeometry(Point.fromLngLat(ch.lon, ch.lat)).apply {
                addStringProperty("id", ch.id)
                addStringProperty("icon", if (ch.kw >= 100) "lf-ch-fast" else "lf-ch")
            }
        }))
    val marks = ArrayList<Feature>()
    if (route != null && route.size >= 2) {
        marks += Feature.fromGeometry(Point.fromLngLat(route.lon[0], route.lat[0])).apply { addStringProperty("icon", "lf-start") }
        marks += Feature.fromGeometry(Point.fromLngLat(route.lon[route.size - 1], route.lat[route.size - 1])).apply { addStringProperty("icon", "lf-end") }
        planner?.planVias.orEmpty().forEach { v -> marks += Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply { addStringProperty("icon", "lf-via") } }
        stops.forEachIndexed { i, st ->
            marks += Feature.fromGeometry(Point.fromLngLat(st.charger.lon, st.charger.lat)).apply {
                addStringProperty("id", st.charger.id)
                addStringProperty("icon", "lf-stop-${if (i + 1 <= 12) i + 1 else 0}")
            }
        }
    }
    style.getSourceAs<GeoJsonSource>("lf-plan-stops-src")?.setGeoJson(FeatureCollection.fromFeatures(marks))

    // Car + me
    val points = mutableListOf<Feature>()
    ui.myLat?.let { la -> ui.myLon?.let { lo -> points += Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addStringProperty("kind", "me") } } }
    style.getSourceAs<GeoJsonSource>("lf-points-src")?.setGeoJson(FeatureCollection.fromFeatures(points))

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
            if (style.getLayer("lf-route-case") != null) style.addLayerBelow(layer, "lf-route-case") else style.addLayer(layer)
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
