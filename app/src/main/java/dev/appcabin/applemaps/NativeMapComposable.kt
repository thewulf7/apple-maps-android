package dev.appcabin.applemaps

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Compose wrapper for NativeMapView (MapLibre + VMP4 tiles).
 * ponytail: minimal lifecycle bridge, no fancy state hoisting.
 */
@Composable
fun NativeMapComposable(
    modifier: Modifier = Modifier,
    center: LatLng = LatLng(50.0755, 14.4378),
    zoom: Float = 14f,
    onMapMoved: ((LatLng, Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val nativeMap = remember { NativeMapView(context) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> nativeMap.onCreate()
                Lifecycle.Event.ON_START -> nativeMap.onStart()
                Lifecycle.Event.ON_RESUME -> nativeMap.onResume()
                Lifecycle.Event.ON_PAUSE -> nativeMap.onPause()
                Lifecycle.Event.ON_STOP -> nativeMap.onStop()
                Lifecycle.Event.ON_DESTROY -> nativeMap.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            nativeMap.onDestroy()
        }
    }

    // Move camera when center/zoom changes from outside
    LaunchedEffect(center, zoom) {
        nativeMap.moveTo(center.lat, center.lng, zoom.toDouble())
    }

    AndroidView(
        modifier = modifier,
        factory = { nativeMap.mapView },
    )
}
