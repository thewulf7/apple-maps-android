package dev.appcabin.applemaps

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.sources.GeoJsonSource
import dev.appcabin.applemaps.vmp4.*
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Native Apple Maps renderer: MapLibre + VMP4 vector tiles.
 * ponytail: fetches style 1 (buildings/polygons) + style 20 (road network)
 * and merges them into a single render.
 */
class NativeMapView(context: Context) {

    companion object {
        private const val TAG = "NativeMapView"
        private const val SRC_POLY = "apple-poly"
        private const val SRC_LINE = "apple-line"
        private const val SRC_POINT = "apple-point"
    }

    private val client = HttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var map: MapLibreMap? = null
    private val tileCache = object : LinkedHashMap<String, ParsedTile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedTile>?) = size > 256
    }
    val mapView: MapView

    init {
        MapLibre.getInstance(context)
        val opts = MapLibreMapOptions.createFromAttributes(context)
        mapView = MapView(context, opts)
    }

    fun onCreate() {
        mapView.getMapAsync { mlMap ->
            map = mlMap
            mlMap.setStyle(Style.Builder().fromJson(BLANK_STYLE)) { style ->
                style.addSource(GeoJsonSource(SRC_POLY))
                style.addSource(GeoJsonSource(SRC_LINE))
                style.addSource(GeoJsonSource(SRC_POINT))

                // Polygon fill — light building/landuse polygons
                style.addLayer(FillLayer("l-poly", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#E8E0D8"),
                    PropertyFactory.fillOpacity(0.7f),
                    PropertyFactory.fillOutlineColor("#D1C7BB"),
                ))
                // Road network — Apple Maps style cased roads
                // ponytail: feature types uncracked, single style for all roads
                // Casing (dark outline)
                style.addLayer(LineLayer("l-road-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#C0C0C0"),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ))
                // Fill (white)
                style.addLayer(LineLayer("l-road-fill", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(1.8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ))
                // POI points
                style.addLayer(CircleLayer("l-point", SRC_POINT).withProperties(
                    PropertyFactory.circleRadius(2.5f),
                    PropertyFactory.circleColor("#FF5722"),
                    PropertyFactory.circleStrokeWidth(0.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ))

                mlMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(50.08, 14.42)).zoom(14.0).build()

                mlMap.addOnCameraIdleListener { loadVisibleTiles() }
                loadVisibleTiles()
            }
        }
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() { scope.cancel(); mapView.onDestroy() }

    /** Take a screenshot via MapLibre's snapshot API (captures GL surface) */
    fun takeSnapshot(callback: (android.graphics.Bitmap) -> Unit) {
        map?.snapshot(callback)
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double = 14.0) {
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
        ), 500)
    }

    // ── Tile loading ───────────────────────────────────────────────

    private fun loadVisibleTiles() {
        val m = map ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val zoom = m.cameraPosition.zoom.toInt().coerceIn(1, 17)
        val n = 2.0.pow(zoom)
        val xMin = ((bounds.longitudeWest + 180) / 360 * n).toInt().coerceAtLeast(0)
        val xMax = ((bounds.longitudeEast + 180) / 360 * n).toInt().coerceAtMost(n.toInt() - 1)
        val yMin = latToTileY(bounds.latitudeNorth, zoom).coerceAtLeast(0)
        val yMax = latToTileY(bounds.latitudeSouth, zoom).coerceAtMost(n.toInt() - 1)
        val tileCount = (xMax - xMin + 1) * (yMax - yMin + 1)
        Log.d(TAG, "Loading $tileCount tiles z=$zoom x=$xMin..$xMax y=$yMin..$yMax")

        scope.launch {
            // Fetch both style 1 (polygons/buildings) and style 20 (road network) per tile
            data class TileResult(val x: Int, val y: Int, val parsed: ParsedTile)
            val results = mutableListOf<TileResult>()
            val jobs = mutableListOf<Deferred<TileResult?>>()

            // Fetch optimal style mix per zoom level:
            // - Style 1 (buildings/polygons): rich at z≤14, empty at z>14
            // - Style 13 (standard vector map): sparse at z=14, good at z=15
            // - Style 20 (road network): rich at z=14-15, empty at z>15
            val styles = when {
                zoom <= 13 -> listOf(1, 20)      // road overlay + satellite roads
                zoom == 14 -> listOf(1, 20)      // best polygon + road data
                zoom == 15 -> listOf(13, 20)     // standard map + satellite roads
                else -> listOf(13)               // standard map only at z16+
            }

            for (x in xMin..xMax) for (y in yMin..yMax) {
                val tx = x; val ty = y
                for (style in styles) {
                    jobs.add(async { fetchAndParse(zoom, tx, ty, style = style)?.let { TileResult(tx, ty, it) } })
                }
            }
            jobs.mapNotNull { it.await() }.let { results.addAll(it) }

            val polySb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val lineSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val pointSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            var pf = true; var lf = true; var ptf = true

            for (tile in results) {
                val (tx, ty, parsed) = tile
                // Polygons
                parsed.polygonVertices?.let { pool ->
                    for (i in pool.shapeStarts.indices) {
                        val start = pool.shapeStarts[i]
                        val len = pool.shapeLengths[i]
                        if (len < 3 || start + len > pool.vertices.size) continue
                        if (!pf) polySb.append(','); pf = false
                        polySb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
                        for (j in 0 until len) {
                            if (j > 0) polySb.append(',')
                            appendCoord(polySb, pool.vertices[start + j], tx, ty, zoom)
                        }
                        polySb.append(','); appendCoord(polySb, pool.vertices[start], tx, ty, zoom)
                        polySb.append("""]]},""")
                        polySb.append(""""properties":{}}""")
                    }
                }
                // Lines
                parsed.lineVertices?.let { pool ->
                    for (i in pool.shapeStarts.indices) {
                        val start = pool.shapeStarts[i]
                        val len = pool.shapeLengths[i]
                        if (len < 2 || start + len > pool.vertices.size) continue
                        if (!lf) lineSb.append(','); lf = false
                        lineSb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
                        for (j in 0 until len) {
                            if (j > 0) lineSb.append(',')
                            appendCoord(lineSb, pool.vertices[start + j], tx, ty, zoom)
                        }
                        lineSb.append("""]},"properties":{}}""")
                    }
                }
                // Points
                parsed.pointVertices?.let { pool ->
                    for (v in pool.vertices) {
                        if (!ptf) pointSb.append(','); ptf = false
                        pointSb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":""")
                        appendCoord(pointSb, v, tx, ty, zoom)
                        pointSb.append("""},"properties":{}}""")
                    }
                }
            }

            polySb.append("]}"); lineSb.append("]}"); pointSb.append("]}")
            val polyJson = polySb.toString()
            val lineJson = lineSb.toString()
            val pointJson = pointSb.toString()

            withContext(Dispatchers.Main) {
                m.style?.let { s ->
                    (s.getSource(SRC_POLY) as? GeoJsonSource)?.setGeoJson(polyJson)
                    (s.getSource(SRC_LINE) as? GeoJsonSource)?.setGeoJson(lineJson)
                    (s.getSource(SRC_POINT) as? GeoJsonSource)?.setGeoJson(pointJson)
                    Log.d(TAG, "Updated map: poly=${polyJson.length}, line=${lineJson.length}, point=${pointJson.length} chars")
                }
            }
        }
    }

    private suspend fun fetchAndParse(z: Int, x: Int, y: Int, style: Int = 1): ParsedTile? {
        val key = "$z/$x/$y/s$style"
        synchronized(tileCache) { tileCache[key] }?.let { return it }
        return try {
            val url = NativeAuth.vectorTileUrl(z, x, y, style = style)
            val bytes = client.get(url).bodyAsBytes()
            if (bytes.size < 8 || bytes[0] != 'V'.code.toByte()) {
                Log.w(TAG, "Tile $key: not VMP4, ${bytes.size}b")
                null
            } else {
                val parsed = parseTile(bytes)
                Log.d(TAG, "Tile $key: polyShapes=${parsed.polygonVertices?.shapeStarts?.size ?: 0} lineShapes=${parsed.lineVertices?.shapeStarts?.size ?: 0} pointVerts=${parsed.pointVertices?.vertices?.size ?: 0}")
                synchronized(tileCache) { tileCache[key] = parsed }
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key failed: ${e.message}")
            null
        }
    }

    // ── GeoJSON helpers ────────────────────────────────────────────

    private fun appendCoord(sb: StringBuilder, v: Vertex, tx: Int, ty: Int, z: Int) {
        val (lon, lat) = tileToWgs84(tx, ty, z, v.x, v.y)
        sb.append("[%.6f,%.6f]".format(java.util.Locale.US, lon, lat))
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        val r = Math.toRadians(lat)
        return floor((1.0 - ln(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toInt()
    }
}

private val BLANK_STYLE = """
{"version":8,"name":"Apple Maps","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#F5F5F3"}}]}
""".trim()
