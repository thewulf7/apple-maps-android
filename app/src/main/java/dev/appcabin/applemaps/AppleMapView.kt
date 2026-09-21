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
    // === Internal gesture state — written directly by pointer handler ===
    // These are the SOURCE OF TRUTH during gestures. The parent's center/zoom
    // are treated as "external commands" (search nav, GPS button).
    var internalCenter by remember { mutableStateOf(center) }
    var internalZoom by remember { mutableStateOf(zoom) }

    // Sync external → internal when parent programmatically navigates
    // (search result tap, GPS button, zoom +/- buttons)
    LaunchedEffect(center, zoom) {
        internalCenter = center
        internalZoom = zoom
    }

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
        if (visibleTiles.containsKey(key)) return
        synchronized(loadingKeys) {
            if (loadingKeys.contains(key)) return
            loadingKeys.add(key)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val cached = tileCache.get(key)
                if (cached != null) {
                    visibleTiles[key] = cached
                    return@launch
                }
                val url = auth.tileUrl(style, x, y, z)
                val bytes = client.get(url) {
                    header("Origin", "https://duckduckgo.com")
                    header("Referer", "https://duckduckgo.com/")
                }.readRawBytes()
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
                    // Read current internal state (always fresh — mutableStateOf)
                    val curCenter = internalCenter
                    val curZoom = internalZoom

                    val z = curZoom.toInt()
                    val (cx, cy) = latLngToTile(curCenter.lat, curCenter.lng, z)
                    val newCx = cx - pan.x / tileSize
                    val newCy = cy - pan.y / tileSize
                    val newCenter = tileToLatLng(newCx, newCy, z)

                    val newZoom = (curZoom + ln(gestureZoom.toDouble()).toFloat() / ln(2.0f))
                        .coerceIn(2f, 19f)

                    // Update internal state immediately (next gesture frame sees it)
                    internalCenter = newCenter
                    internalZoom = newZoom

                    // Notify parent
                    onMapMoved(newCenter, newZoom)
                }
            }
    ) {
        val viewW = size.width
        val viewH = size.height
        val curCenter = internalCenter
        val curZoom = internalZoom
        val z = curZoom.toInt()
        val n = 1 shl z

        val (cx, cy) = latLngToTile(curCenter.lat, curCenter.lng, z)

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
            val pos = latLngToPixel(userLocation, curCenter, z, tileSize, viewW, viewH)
            drawCircle(color = Color(0x30007AFF), radius = 40f, center = pos)
            drawCircle(color = Color.White, radius = 12f, center = pos)
            drawCircle(color = Color(0xFF007AFF), radius = 9f, center = pos)
        }

        // Draw search pin
        if (searchPin != null) {
            val pinPos = latLngToPixel(searchPin, curCenter, z, tileSize, viewW, viewH)
            drawCircle(color = Color(0x40000000), radius = 16f, center = Offset(pinPos.x + 2f, pinPos.y + 2f))
            drawCircle(color = Color(0xFFFF3B30), radius = 14f, center = Offset(pinPos.x, pinPos.y - 14f))
            drawCircle(color = Color.White, radius = 5f, center = Offset(pinPos.x, pinPos.y - 14f))
            drawCircle(color = Color(0xFFFF3B30), radius = 4f, center = pinPos)
        }
    }
}