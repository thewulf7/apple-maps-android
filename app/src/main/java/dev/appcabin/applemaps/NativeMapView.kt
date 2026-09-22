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
import org.maplibre.android.style.expressions.Expression
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
class NativeMapView(private val context: Context) {

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

                // Polygon fills — Apple Maps color palette by feature type
                // ponytail: separate sources per feature class beats data-driven for clarity
                // ft=0: land/ground, ft=64-69: parks/recreation, ft=90-99: water
                // Base land fill (covers everything)
                style.addLayer(FillLayer("l-land", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#F2EFE9"),
                    PropertyFactory.fillOpacity(1.0f),
                ))
                // Parks — green
                // Use Expression.raw() — Expression.literal(int) type-mismatches JSON double ft values
                style.addLayer(FillLayer("l-park", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#D4E8C2"),
                    PropertyFactory.fillOpacity(1.0f),
                ).withFilter(
                    Expression.raw("[\"in\",[\"get\",\"ft\"],[\"literal\",[40,64,65]]]")
                ))
                // Water — blue
                style.addLayer(FillLayer("l-water", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#A8D4F0"),
                    PropertyFactory.fillOpacity(1.0f),
                ).withFilter(
                    Expression.raw("[\"in\",[\"get\",\"ft\"],[\"literal\",[88,89,90,91,92,93,94,95,96,97,98,99,100]]]")
                ))
                // Buildings — warm beige
                style.addLayer(FillLayer("l-building", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#E0D8CC"),
                    PropertyFactory.fillOpacity(1.0f),
                    PropertyFactory.fillOutlineColor("#C8C0B4"),
                ).withFilter(
                    Expression.raw("[\"all\",[\">\", [\"get\",\"ft\"], 99],[\"<\",[\"get\",\"ft\"],131]]")
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
                    .target(LatLng(50.0853, 14.4031)).zoom(14.0).build()

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
        // size=2 tiles are 512px but MapLibre uses 256px grid, so viewport shows 2x fewer tiles.
        // Pad by ±2 tiles to ensure coverage. ponytail: cheap fix beats proper tile-size config.
        val pad = 2
        val xMin = (((bounds.longitudeWest + 180) / 360 * n).toInt() - pad).coerceAtLeast(0)
        val xMax = (((bounds.longitudeEast + 180) / 360 * n).toInt() + pad).coerceAtMost(n.toInt() - 1)
        val yMin = (latToTileY(bounds.latitudeNorth, zoom) - pad).coerceAtLeast(0)
        val yMax = (latToTileY(bounds.latitudeSouth, zoom) + pad).coerceAtMost(n.toInt() - 1)
        val tileCount = (xMax - xMin + 1) * (yMax - yMin + 1)
        Log.d(TAG, "Loading $tileCount tiles z=$zoom x=$xMin..$xMax y=$yMin..$yMax")

        scope.launch {
            // Fetch both style 1 (polygons/buildings) and style 20 (road network) per tile
            data class TileResult(val x: Int, val y: Int, val parsed: ParsedTile)
            val results = mutableListOf<TileResult>()
            val jobs = mutableListOf<Deferred<TileResult?>>()

            // Style 1 = base polygons (land use, buildings) + sparse roads  
            // Style 20 = dense road network overlay
            // ponytail: only these two are confirmed working on gspe19-ssl
            val styles = listOf(1, 20)

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
                // Polygons — emit feature type as "ft" for color filtering
                // ponytail: build shape→ft map from class ranges once, reuse per shape
                parsed.polygonVertices?.let { pool ->
                    val shapeCount = pool.shapeStarts.size
                    val shapeFt = IntArray(shapeCount)
                    // Classes assign ft to shape ranges via vertexStart..vertexStart+outerCount
                    // Some class fields are corrupt (hash bytes read as varints) — clamp defensively
                    for (cls in parsed.polygons) {
                        val ft = cls.featureType
                        Log.d(TAG, "rawcls: vs=${cls.vertexStart} oc=${cls.outerCount} hc=${cls.holeCount} ft=$ft si=${cls.styleIndex}")
                        // Skip garbage (hash bytes misread as varint → huge) and land default (0)
                        if (ft <= 0 || ft > 127) continue
                        val s = cls.vertexStart.coerceIn(0, shapeCount - 1)
                        val n = cls.outerCount.coerceIn(0, shapeCount)
                        val e = (s + n).coerceAtMost(shapeCount)
                        if (e > s) for (k in s until e) shapeFt[k] = ft
                    }
                    Log.d(TAG, "shapeFt sample: ${shapeFt.take(20).toList()} nonzero=${shapeFt.count { it != 0 }}")
                    // Count ft distribution
                    val ftDist = shapeFt.groupBy { it }.mapValues { it.value.size }
                    Log.d(TAG, "shapeFt distribution: $ftDist")
                    // Log first polygon coords for debugging
                    if (pool.shapeStarts.isNotEmpty()) {
                        val s0 = pool.shapeStarts[0]; val l0 = pool.shapeLengths[0]
                        if (l0 > 0) {
                            val v0 = pool.vertices[s0]
                            val (lon0, lat0) = tileToWgs84(tx, ty, zoom, v0.x, v0.y)
                            Log.d(TAG, "Tile $tx/$ty/$zoom poly[0] v0: raw=(${v0.x},${v0.y}) wgs84=($lat0,$lon0)")
                        }
                    }
                    for (i in pool.shapeStarts.indices) {
                        val start = pool.shapeStarts[i]
                        val len = pool.shapeLengths[i]
                        if (len < 3 || start + len > pool.vertices.size) continue
                        val ft = shapeFt[i]
                        if (!pf) polySb.append(','); pf = false
                        polySb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
                        for (j in 0 until len) {
                            if (j > 0) polySb.append(',')
                            appendCoord(polySb, pool.vertices[start + j], tx, ty, zoom)
                        }
                        polySb.append(','); appendCoord(polySb, pool.vertices[start], tx, ty, zoom)
                        polySb.append("""]]},"properties":{"ft":$ft}}""")
                    }
                }
                // Lines — driven by LineFeature metadata (vs/vc = flat vertex indices)
                // ponytail: MultiLineString when feature spans multiple shapes
                parsed.lineVertices?.let { pool ->
                    val features = parsed.lines
                    if (features.isEmpty()) {
                        // fallback: raw shapes (no feature metadata)
                        for (i in pool.shapeStarts.indices) {
                            val start = pool.shapeStarts[i]; val len = pool.shapeLengths[i]
                            if (len < 2 || start + len > pool.vertices.size) continue
                            if (!lf) lineSb.append(','); lf = false
                            lineSb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
                            for (j in 0 until len) { if (j > 0) lineSb.append(','); appendCoord(lineSb, pool.vertices[start + j], tx, ty, zoom) }
                            lineSb.append("""]},"properties":{}}""")
                        }
                    } else {
                        for (feat in features) {
                            val shapeRange = pool.shapesForVertexRange(feat.vertexStart, feat.vertexCount)
                            if (shapeRange.isEmpty()) continue
                            val subLines = mutableListOf<String>()
                            for (si in shapeRange) {
                                val start = pool.shapeStarts[si]; val len = pool.shapeLengths[si]
                                if (len < 2 || start + len > pool.vertices.size) continue
                                val sb2 = StringBuilder("[")
                                for (j in 0 until len) { if (j > 0) sb2.append(','); appendCoord(sb2, pool.vertices[start + j], tx, ty, zoom) }
                                sb2.append(']')
                                subLines.add(sb2.toString())
                            }
                            if (subLines.isEmpty()) continue
                            if (!lf) lineSb.append(','); lf = false
                            if (subLines.size == 1) {
                                lineSb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":${subLines[0]}},"properties":{}}""")
                            } else {
                                lineSb.append("""{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[${subLines.joinToString(",")}]},"properties":{}}""")
                            }
                        }
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
                    Log.d(TAG, "Updated map: poly=${polySb.length}, line=${lineSb.length}, point=${pointSb.length} chars")
                }
            }
        }
    }

    private suspend fun fetchAndParse(z: Int, x: Int, y: Int, style: Int = 1): ParsedTile? {
        val key = "$z/$x/$y/s$style"
        synchronized(tileCache) { tileCache[key] }?.let { return it }
        return try {
            // Try asset fallback first (for offline testing)
            val assetName = "tile_${z}_${x}_${y}_s${style}.bin"
            val bytes: ByteArray = try {
                context.assets.open(assetName).readBytes().also {
                    Log.d(TAG, "Tile $key: loaded from asset $assetName (${it.size}b)")
                }
            } catch (_: Exception) {
                val url = NativeAuth.vectorTileUrl(z, x, y, style = style)
                client.get(url).bodyAsBytes()
            }
            if (bytes.size < 8 || bytes[0] != 'V'.code.toByte()) {
                Log.w(TAG, "Tile $key: not VMP4, ${bytes.size}b")
                null
            } else {
                val parsed = parseTile(bytes)
                val polyTypes = parsed.polygons.take(20).map { it.featureType }
                val lineTypes = parsed.lines.take(10).map { it.featureType }
                Log.d(TAG, "Tile $key: polyShapes=${parsed.polygonVertices?.shapeStarts?.size ?: 0} polyTypes=$polyTypes lineShapes=${parsed.lineVertices?.shapeStarts?.size ?: 0} lineTypes=$lineTypes")
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
