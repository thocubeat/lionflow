package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import no.lionflow.app.ui.theme.LocalLf

/** A recorded route drawn as a line, with start and end dots. */
@Composable
fun RouteMap(points: List<Triple<Double, Double, Long>>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dark = LocalLf.current.isDark
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(null); onStart(); onResume() }
    }
    DisposableEffect(Unit) { onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } }

    val line = remember(points) {
        val coords = points.joinToString(",") { "[${it.second},${it.first}]" }
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
    }
    val ends = remember(points) {
        if (points.isEmpty()) """{"type":"FeatureCollection","features":[]}""" else {
            val a = points.first(); val b = points.last()
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[${a.second},${a.first}]},"properties":{"k":"start"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[${b.second},${b.first}]},"properties":{"k":"end"}}]}"""
        }
    }

    Box(modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp))) {
        AndroidView(factory = {
            mapView.getMapAsync { m ->
                m.uiSettings.isAttributionEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isCompassEnabled = false
                val url = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
                m.setStyle(Style.Builder().fromUri(url)) { st ->
                    st.addSource(GeoJsonSource("route-src", line))
                    st.addLayer(LineLayer("route", "route-src").withProperties(
                        PropertyFactory.lineColor("#2F7CF6"), PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                    st.addSource(GeoJsonSource("route-ends", ends))
                    st.addLayer(CircleLayer("route-ends-l", "route-ends").withProperties(
                        PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#FFFFFF"),
                        PropertyFactory.circleStrokeColor("#2F7CF6"), PropertyFactory.circleStrokeWidth(3f)))
                    if (points.size >= 2) {
                        val b = LatLngBounds.Builder()
                        points.forEach { b.include(LatLng(it.first, it.second)) }
                        try { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60)) } catch (_: Exception) {}
                    } else points.firstOrNull()?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.first, it.second), 14.0)) }
                }
            }
            mapView
        })
    }
}
