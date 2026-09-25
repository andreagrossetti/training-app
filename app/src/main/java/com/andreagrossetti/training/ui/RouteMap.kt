package com.andreagrossetti.training.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andreagrossetti.training.data.RoutePoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Free OpenStreetMap vector tiles, no API key. Colorful in both themes: dark styles are too faint. */
private const val STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** Interactive map with the route drawn on top, framed to fit. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = MaterialTheme.colorScheme.secondary.toArgb()
    val mapView = remember {
        MapLibre.getInstance(context)
        // Texture mode so the map respects Compose clipping (rounded corners).
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
            onCreate(null)
            // Let the map pan and zoom instead of the list scrolling.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(points) {
        if (points.size < 2) return@LaunchedEffect
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        val ends = FeatureCollection.fromFeatures(
            listOf(
                Feature.fromGeometry(Point.fromLngLat(points.first().lon, points.first().lat)).apply { addBooleanProperty("start", true) },
                Feature.fromGeometry(Point.fromLngLat(points.last().lon, points.last().lat)).apply { addBooleanProperty("start", false) },
            )
        )
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            // The OpenStreetMap attribution ("i") stays, as the data license requires.
            map.uiSettings.isLogoEnabled = false
            map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                style.addSource(GeoJsonSource("route", line))
                style.addLayer(
                    LineLayer("route-casing", "route").withProperties(
                        PropertyFactory.lineColor(0xFFFFFFFF.toInt()),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
                style.addLayer(
                    LineLayer("route-line", "route").withProperties(
                        PropertyFactory.lineColor(routeColor),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
                style.addSource(GeoJsonSource("ends", ends))
                style.addLayer(
                    CircleLayer("ends-dot", "ends").withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(
                            switchCase(
                                get("start"),
                                color(startColor),
                                color(routeColor),
                            )
                        ),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    )
                )
                val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
