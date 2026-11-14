package no.lionflow.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.api.Charger
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
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
import kotlin.math.ln

/**
 * Draws LionFlow's vector map (OpenFreeMap tiles, MapLibre) on the surface Android Auto gives us:
 * the car's centre screen, or the driver display behind the wheel ([cluster] = true).
 * The map is rendered into a private VirtualDisplay so it runs at the car screen's own size and density.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val owner: LifecycleOwner,
    private val cluster: Boolean,
) : SurfaceCallback, DefaultLifecycleObserver {

    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var card: LinearLayout? = null
    private var cardTitle: TextView? = null
    private var cardText: TextView? = null
    private var cardMsg: TextView? = null
    private var visible = Rect()
    private var collectJob: Job? = null

    var following = true
        private set
    var is3d = true
        private set

    /** Called when following/3D changes so the screen can swap its map buttons. */
    var onStateChanged: () -> Unit = {}

    init {
        owner.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(this)
        collectJob = owner.lifecycleScope.launch {
            combine(CarHub.status, CarHub.chargers, CarHub.message, CarHub.focus) { _, _, _, f -> f }.collect { f ->
                updateData()
                f?.let { focusOn(it) }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        collectJob?.cancel()
        tearDown()
    }

    // ---------------- surface ----------------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        main.post { setUp(container) }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        main.post { tearDown() }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        main.post { visible = Rect(visibleArea); applyPadding(); placeCard() }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        main.post { if (visible.isEmpty) { visible = Rect(stableArea); applyPadding(); placeCard() } }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        main.post {
            stopFollowing()
            map?.scrollBy(-distanceX, -distanceY, 0)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        main.post {
            stopFollowing()
            map?.scrollBy(velocityX / 8f, velocityY / 8f, 300)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        main.post {
            val m = map ?: return@post
            // The host sends -1 for a double tap.
            val by = if (scaleFactor < 0f) 1.0 else ln(scaleFactor.toDouble()) / ln(2.0)
            if (following) m.moveCamera(CameraUpdateFactory.zoomBy(by))
            else m.moveCamera(CameraUpdateFactory.zoomBy(by, android.graphics.Point(focusX.toInt(), focusY.toInt())))
        }
    }

    override fun onClick(x: Float, y: Float) {
        main.post {
            val m = map ?: return@post
            val hit = m.queryRenderedFeatures(PointF(x, y), "lf-chargers").firstOrNull() ?: return@post
            val name = hit.getStringProperty("name") ?: "Charger"
            val kw = hit.getNumberProperty("kw")?.toInt() ?: 0
            CarToast.makeText(carContext, if (kw > 0) "$name · $kw kW" else name, CarToast.LENGTH_LONG).show()
        }
    }

    private fun setUp(c: SurfaceContainer) {
        tearDown()
        val surface = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        MapLibre.getInstance(carContext.applicationContext)
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            if (cluster) "LionFlowCluster" else "LionFlowMap",
            c.width, c.height, c.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        val p = Presentation(carContext, vd.display)
        val ctx = p.context
        val mv = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(false)
            .logoEnabled(false).compassEnabled(false).attributionEnabled(!cluster))
        val root = FrameLayout(ctx)
        root.addView(mv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!cluster) root.addView(buildCard(ctx))
        p.setContentView(root)
        p.show()
        presentation = p
        mapView = mv
        if (visible.isEmpty) visible = Rect(0, 0, c.width, c.height)

        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = !cluster
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            val start = CarHub.status.value?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> LatLng(la, lo) } } } ?: LatLng(59.91, 10.75)
            m.cameraPosition = CameraPosition.Builder().target(start).zoom(15.5).tilt(if (is3d) 55.0 else 0.0).build()
            loadStyle(m, styleUrl())
        }
        placeCard()
    }

    // The car map is always dark: easier on the eyes and matches the car's own screens.
    private fun styleUrl() = STYLE_DARK

    private fun loadStyle(m: MapLibreMap, url: String) {
        m.setStyle(Style.Builder().fromUri(url)) { st ->
            style = st
            add3d(st)
            updateData()
            enableLocation(m, st)
            applyPadding()
        }
    }

    private fun tearDown() {
        val mv = mapView
        mapView = null; map = null; style = null; card = null
        try { mv?.onPause(); mv?.onStop(); mv?.onDestroy() } catch (_: Exception) { }
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        display?.release()
        display = null
    }

    // ---------------- camera ----------------

    @SuppressLint("MissingPermission")
    private fun enableLocation(m: MapLibreMap, st: Style) {
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            following = false
            CarHub.say("Allow location for LionFlow on your phone to follow your position")
            return
        }
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(presentation?.context ?: carContext, st)
                    .useDefaultLocationEngine(true)
                    .locationEngineRequest(
                        LocationEngineRequest.Builder(1000)
                            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                            .setFastestInterval(500)
                            .build()
                    )
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            if (following) follow()
        } catch (e: Exception) {
            following = false
        }
    }

    private fun follow() {
        val m = map ?: return
        try {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) return
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.zoomWhileTracking(if (cluster) 16.5 else 16.0)
            lc.tiltWhileTracking(if (is3d) 60.0 else 0.0)
        } catch (_: Exception) { }
    }

    private fun stopFollowing() {
        if (!following) return
        following = false
        try { map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE } catch (_: Exception) { }
        onStateChanged()
    }

    fun recenter() = main.post {
        following = true
        CarHub.focus.value = null
        follow()
        onStateChanged()
    }

    fun toggle3d() = main.post {
        is3d = !is3d
        val m = map ?: return@post
        if (following) follow()
        else m.animateCamera(CameraUpdateFactory.tiltTo(if (is3d) 55.0 else 0.0), 400)
        style?.let { st -> if (is3d) add3d(st) else st.removeLayer("lf-3d") }
        onStateChanged()
    }

    fun zoom(by: Double) = main.post { map?.animateCamera(CameraUpdateFactory.zoomBy(by), 250) }

    private fun focusOn(ch: Charger) {
        val m = map ?: return
        stopFollowing()
        val me = try { m.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation } catch (_: Exception) { null }
        val target = LatLng(ch.lat, ch.lon)
        if (me != null) {
            val b = LatLngBounds.Builder().include(target).include(LatLng(me.latitude, me.longitude)).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120), 700)
        } else {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(15.0).tilt(if (is3d) 50.0 else 0.0).build()), 700)
        }
        updateData()
    }

    /** Keeps the car arrow in the free part of the screen (not under the host's buttons), and a bit low like a sat-nav. */
    private fun applyPadding() {
        val m = map ?: return
        val w = mapView?.width?.takeIf { it > 0 } ?: return
        val h = mapView?.height?.takeIf { it > 0 } ?: return
        val v = if (visible.isEmpty) Rect(0, 0, w, h) else visible
        val extraTop = if (is3d) (v.height() * 0.30).toInt() else 0
        m.moveCamera(CameraUpdateFactory.paddingTo(
            v.left.toDouble(), (v.top + extraTop).toDouble(), (w - v.right).coerceAtLeast(0).toDouble(), (h - v.bottom).coerceAtLeast(0).toDouble(),
        ))
    }

    // ---------------- layers ----------------

    private fun add3d(st: Style) {
        if (!is3d || st.getLayer("lf-3d") != null) return
        val src = listOf("openmaptiles", "composite").firstOrNull { st.getSource(it) != null } ?: return
        val dark = true
        val layer = FillExtrusionLayer("lf-3d", src).apply {
            sourceLayer = "building"
            setProperties(
                PropertyFactory.fillExtrusionColor(Color.parseColor(if (dark) "#2A2E35" else "#D7DBE0")),
                PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
                PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
                PropertyFactory.fillExtrusionOpacity(0.85f),
            )
        }
        if (st.getLayer("lf-chargers") != null) st.addLayerBelow(layer, "lf-chargers") else st.addLayer(layer)
    }

    private fun updateData() {
        val st = style ?: run { updateCard(); return }
        val focusId = CarHub.focus.value?.id
        val chargers = FeatureCollection.fromFeatures(CarHub.chargers.value.map { ch ->
            Feature.fromGeometry(Point.fromLngLat(ch.lon, ch.lat)).apply {
                addStringProperty("id", ch.id)
                addStringProperty("name", ch.name)
                addNumberProperty("kw", ch.maxKw)
                addBooleanProperty("fast", ch.maxKw >= 50)
                addBooleanProperty("focus", ch.id == focusId)
            }
        })
        val csrc = st.getSourceAs<GeoJsonSource>("lf-chargers-src")
        if (csrc == null) {
            st.addSource(GeoJsonSource("lf-chargers-src", chargers))
            st.addLayer(CircleLayer("lf-chargers", "lf-chargers-src").withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(
                    Expression.get("focus"), Expression.literal(14f),
                    Expression.get("fast"), Expression.literal(9f),
                    Expression.literal(7f),
                )),
                PropertyFactory.circleColor(Color.parseColor(ACCENT)),
                PropertyFactory.circleStrokeColor(Color.parseColor("#0D0E10")),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circlePitchAlignment("map"),
            ))
        } else csrc.setGeoJson(chargers)

        // Where the car is parked (when it isn't with you).
        val s = CarHub.status.value
        val car = if (s?.lat != null && s.lon != null) listOf(Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))) else emptyList()
        val psrc = st.getSourceAs<GeoJsonSource>("lf-car-src")
        if (psrc == null) {
            st.addSource(GeoJsonSource("lf-car-src", FeatureCollection.fromFeatures(car)))
            st.addLayer(CircleLayer("lf-car", "lf-car-src").withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Color.parseColor("#FFB547")),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
            ))
        } else psrc.setGeoJson(FeatureCollection.fromFeatures(car))
        updateCard()
    }

    // ---------------- status card (centre screen only) ----------------

    private fun buildCard(ctx: android.content.Context): View {
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
        val l = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.parseColor("#E6141619")) }
            elevation = dp(4f)
        }
        cardTitle = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        cardText = TextView(ctx).apply { setTextColor(Color.parseColor("#B8BEC7")); textSize = 13f }
        cardMsg = TextView(ctx).apply { setTextColor(Color.parseColor(ACCENT)); textSize = 13f; visibility = View.GONE }
        l.addView(cardTitle); l.addView(cardText); l.addView(cardMsg)
        l.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        card = l
        updateCard()
        return l
    }

    private fun placeCard() {
        val c = card ?: return
        val ctx = c.context
        val m = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, ctx.resources.displayMetrics).toInt()
        val lp = c.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = visible.left + m
        lp.topMargin = visible.top + m
        c.layoutParams = lp
    }

    private fun updateCard() {
        cardTitle?.text = CarHub.batteryLine(carContext)
        cardText?.text = CarHub.detailLine(carContext)
        val msg = CarHub.message.value
        cardMsg?.text = msg ?: ""
        cardMsg?.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val ACCENT = "#C8F560"
    }
}
