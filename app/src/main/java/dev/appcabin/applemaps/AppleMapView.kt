package dev.appcabin.applemaps

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlin.math.*

private const val TAG = "AppleMapView"

data class LatLng(val lat: Double, val lng: Double)

/** Convert lat/lng to tile x/y at given zoom */
fun latLngToTile(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
    val n = 1 shl zoom
    val x = (lng + 180.0) / 360.0 * n
    val latRad = Math.toRadians(lat)
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x to y
}

/** Convert tile x/y back to lat/lng */
fun tileToLatLng(x: Double, y: Double, zoom: Int): LatLng {
    val n = 1 shl zoom
    val lng = x / n * 360.0 - 180.0
    val latRad = atan(sinh(PI * (1 - 2 * y / n)))
    val lat = Math.toDegrees(latRad)
    return LatLng(lat, lng)
}

/** Convert lat/lng to pixel position relative to map view center */
fun latLngToPixel(
    point: LatLng, center: LatLng, zoom: Int,
    tileSize: Float, viewW: Float, viewH: Float
): Offset {
    val (cx, cy) = latLngToTile(center.lat, center.lng, zoom)
    val (px, py) = latLngToTile(point.lat, point.lng, zoom)
    val screenX = viewW / 2f + ((px - cx) * tileSize).toFloat()
    val screenY = viewH / 2f + ((py - cy) * tileSize).toFloat()
    return Offset(screenX, screenY)
}

@Composable
fun AppleMapView(
    modifier: Modifier = Modifier,
    auth: AppleMapAuth,
    client: HttpClient,
    tileCache: TileCache,
    mapStyle: String = "standard",
    center: LatLng = LatLng(50.0755, 14.4378),
    zoom: Float = 14f,
    userLocation: LatLng? = null,
    searchPin: LatLng? = null,
    onMapMoved: (LatLng, Float) -> Unit = { _, _ -> },
) {
    // Compose-observable tile state: maps key -> ImageBitmap (or null = loading)
    val visibleTiles = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val loadingKeys = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()
    val tileSize = 512f

    // Pre-warm auth
    LaunchedEffect(Unit) {
        try {
            auth.getToken()
            Log.d(TAG, "Auth pre-warmed")
        } catch (e: Exception) {
            Log.e(TAG, "Auth pre-warm failed", e)
        }
    }

    fun loadTile(key: String, style: String, x: Int, y: Int, z: Int) {
        // Fast path: already in compose state
        if (visibleTiles.containsKey(key)) return
        synchronized(loadingKeys) {
            if (loadingKeys.contains(key)) return
            loadingKeys.add(key)
        }
        scope.launch(Dispatchers.IO) {
            try {
                // Check disk cache first
                val cached = tileCache.get(key)
                if (cached != null) {
                    visibleTiles[key] = cached
                    return@launch
                }
                // Fetch from network
                val url = auth.tileUrl(style, x, y, z)
                val bytes = client.get(url) {
                    header("Origin", "https://duckduckgo.com")
                    header("Referer", "https://duckduckgo.com/")
                }.readRawBytes()
                // Store in cache (disk + memory)
                val img = tileCache.put(key, bytes)
                if (img != null) {
                    visibleTiles[key] = img
                } else {
                    Log.w(TAG, "Tile $key decode failed")
                    synchronized(loadingKeys) { loadingKeys.remove(key) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tile $key error: ${e.message}")
                synchronized(loadingKeys) { loadingKeys.remove(key) }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val z = zoom.toInt()
                    val (cx, cy) = latLngToTile(center.lat, center.lng, z)
                    val newCx = cx - pan.x / tileSize
                    val newCy = cy - pan.y / tileSize
                    val newCenter = tileToLatLng(newCx, newCy, z)

                    val newZoom = (zoom + ln(gestureZoom.toDouble()).toFloat() / ln(2.0f))
                        .coerceIn(2f, 19f)

                    onMapMoved(newCenter, newZoom)
                }
            }
    ) {
        val viewW = size.width
        val viewH = size.height
        val z = zoom.toInt()
        val n = 1 shl z

        val (cx, cy) = latLngToTile(center.lat, center.lng, z)

        val tilesX = ceil(viewW / tileSize).toInt() + 2
        val tilesY = ceil(viewH / tileSize).toInt() + 2

        val centerTileX = floor(cx).toInt()
        val centerTileY = floor(cy).toInt()
        val offsetX = viewW / 2f - (cx - centerTileX).toFloat() * tileSize
        val offsetY = viewH / 2f - (cy - centerTileY).toFloat() * tileSize

        val halfTX = tilesX / 2
        val halfTY = tilesY / 2
        for (dy in -halfTY..halfTY) {
            for (dx in -halfTX..halfTX) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (tx < 0 || tx >= n || ty < 0 || ty >= n) continue

                val key = "$mapStyle/$z/$tx/$ty"
                val img = visibleTiles[key]

                val screenX = offsetX + dx * tileSize
                val screenY = offsetY + dy * tileSize

                if (img != null) {
                    drawImage(
                        image = img,
                        dstOffset = androidx.compose.ui.unit.IntOffset(screenX.toInt(), screenY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(tileSize.toInt(), tileSize.toInt()),
                    )
                }

                loadTile(key, mapStyle, tx, ty, z)
            }
        }

        // Draw user location dot
        if (userLocation != null) {
            val pos = latLngToPixel(userLocation, center, z, tileSize, viewW, viewH)
            // Accuracy circle
            drawCircle(
                color = Color(0x30007AFF),
                radius = 40f,
                center = pos,
            )
            // White border
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = pos,
            )
            // Blue dot
            drawCircle(
                color = Color(0xFF007AFF),
                radius = 9f,
                center = pos,
            )
        }

        // Draw search pin
        if (searchPin != null) {
            val pinPos = latLngToPixel(searchPin, center, z, tileSize, viewW, viewH)
            // Pin shadow
            drawCircle(
                color = Color(0x40000000),
                radius = 16f,
                center = Offset(pinPos.x + 2f, pinPos.y + 2f),
            )
            // Red pin body (teardrop — just draw a large circle)
            drawCircle(
                color = Color(0xFFFF3B30),
                radius = 14f,
                center = Offset(pinPos.x, pinPos.y - 14f),
            )
            // White inner dot
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(pinPos.x, pinPos.y - 14f),
            )
            // Pin point
            drawCircle(
                color = Color(0xFFFF3B30),
                radius = 4f,
                center = pinPos,
            )
        }
    }
}
