package dev.appcabin.applemaps

import android.content.Context
import android.graphics.Bitmap
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
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
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
    /** Per-tile style 99 ft maps: key="z/x/y", value=IntArray[512*512] of ft values */
    private val style99Cache = object : LinkedHashMap<String, IntArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 256
    }
    /** Track which style99 image sources have been added to the MapLibre style (avoid duplicates). */
    private val style99Added = mutableSetOf<String>()
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
                // Road layers hidden (road data causes visual artifacts at z=14).
                style.addLayer(FillLayer("l-road-case", SRC_LINE).withProperties(
                    PropertyFactory.fillColor("#F2EFE9"),  // same as land - invisible
                    PropertyFactory.fillOpacity(0.0f),
                ))
                style.addLayer(FillLayer("l-road-fill", SRC_LINE).withProperties(
                    PropertyFactory.fillColor("#F2EFE9"),
                    PropertyFactory.fillOpacity(0.0f),
                ))
                // POI points
                style.addLayer(CircleLayer("l-point", SRC_POINT).withProperties(
                    PropertyFactory.circleRadius(2.5f),
                    PropertyFactory.circleColor("#FF5722"),
                    PropertyFactory.circleStrokeWidth(0.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ))

                mlMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(50.0868, 14.4115)).zoom(14.0).build()

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

    private fun Double.round(decimals: Int) = String.format("%.${decimals}f", this)

    private fun loadVisibleTiles() {
        val m = map ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val zoom = m.cameraPosition.zoom.toInt().coerceIn(1, 17)
        val n = 2.0.pow(zoom)
        // Add ±1 tile padding for denser road coverage and seamless edges
        val xMin = (((bounds.longitudeWest + 180) / 360 * n).toInt() - 1).coerceAtLeast(0)
        val xMax = (((bounds.longitudeEast + 180) / 360 * n).toInt() + 1).coerceAtMost(n.toInt() - 1)
        val yMin = (latToTileY(bounds.latitudeNorth, zoom) - 1).coerceAtLeast(0)
        val yMax = (latToTileY(bounds.latitudeSouth, zoom) + 1).coerceAtMost(n.toInt() - 1)
        val tileCount = (xMax - xMin + 1) * (yMax - yMin + 1)
        Log.d(TAG, "Loading $tileCount tiles z=$zoom x=$xMin..$xMax y=$yMin..$yMax | bounds lon=[${bounds.longitudeWest.round(4)},${bounds.longitudeEast.round(4)}] lat=[${bounds.latitudeSouth.round(4)},${bounds.latitudeNorth.round(4)}]")

        scope.launch {
            // Fetch style 1 (polygons), style 20 (roads), and style 99 (classification raster)
            data class TileResult(val x: Int, val y: Int, val parsed: ParsedTile)
            val results = mutableListOf<TileResult>()
            val jobs = mutableListOf<Deferred<TileResult?>>()
            val style99Jobs = mutableListOf<Deferred<Pair<String, IntArray?>>>()

            // Style 1 = base polygons, style 20 = roads
            val styles = listOf(1, 20)

            for (x in xMin..xMax) for (y in yMin..yMax) {
                val tx = x; val ty = y
                for (style in styles) {
                    jobs.add(async { fetchAndParse(zoom, tx, ty, style = style)?.let { TileResult(tx, ty, it) } })
                }
                // Style 99 = per-pixel style class raster (water=1, park=2, land=0)
                // Style 99 is ONLY available at z=14 (confirmed from ActiveTileGroup.pbd).
                // For z>14: clamp to z=14 parent tile.
                // For z<14: not available (no polygon data either at z<14).
                val s99z = 14
                val s99x = if (zoom <= 14) tx shr (14 - zoom) else tx shl (zoom - 14)
                val s99y = if (zoom <= 14) ty shr (14 - zoom) else ty shl (zoom - 14)
                val key99 = "$s99z/$s99x/$s99y"
                if (zoom >= 13 && !synchronized(style99Cache) { style99Cache.containsKey(key99) }) {
                    style99Jobs.add(async {
                        key99 to fetchStyle99(s99z, s99x, s99y)
                    })
                }
            }
            jobs.mapNotNull { it.await() }.let { results.addAll(it) }
            // Store style 99 results
            style99Jobs.forEach { job ->
                val (k, ftMap) = job.await()
                if (ftMap != null) synchronized(style99Cache) { style99Cache[k] = ftMap }
            }

            val polySb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val lineSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val pointSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            var pf = true; var lf = true; var ptf = true

            for (tile in results) {
                val (tx, ty, parsed) = tile
                // Polygons — sample style 99 raster at centroid for ft classification.
                // Style 99: value 0=land, 1=water, 2=park → mapped to ft 0/94/64.
                parsed.polygonVertices?.let { pool ->
                    val s99z14 = 14
                    val s99x14 = if (zoom <= 14) tx shr (14 - zoom) else tx shl (zoom - 14)
                    val s99y14 = if (zoom <= 14) ty shr (14 - zoom) else ty shl (zoom - 14)
                    val ftMap = synchronized(style99Cache) { style99Cache["$s99z14/$s99x14/$s99y14"] }
                    for (i in pool.shapeStarts.indices) {
                        val start = pool.shapeStarts[i]
                        val len = pool.shapeLengths[i]
                        if (len < 3 || start + len > pool.vertices.size) continue
                        // Sample style 99 at shape centroid for feature type classification
                        val ft = if (ftMap != null) {
                            val cx = pool.vertices.subList(start, start + len).sumOf { it.x.toDouble() }.toFloat() / len
                            val cy = pool.vertices.subList(start, start + len).sumOf { it.y.toDouble() }.toFloat() / len
                            Style99Decoder.sampleFt(ftMap, cx, cy)
                        } else 0
                        if (!pf) polySb.append(','); pf = false
                        polySb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
                        for (j in 0 until len) {
                            if (j > 0) polySb.append(',')
                            appendCoord(polySb, pool.vertices[start + j], tx, ty, zoom)
                        }
                        polySb.append(','); appendCoord(polySb, pool.vertices[start], tx, ty, zoom)
                        polySb.append("""]]},""")
                        polySb.append(""""properties":{"ft":$ft}}""")
                    }
                }
                // Roads disabled — road area polygons cause visual artifacts at this zoom.
                // TODO: implement proper road centerline rendering when data source is available.
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

                    // Add style99 raster layers for tiles that haven't been added yet
                    val n14 = 1 shl 14
                    for (x in xMin..xMax) for (y in yMin..yMax) {
                        val s99x = x shr (zoom - 14).coerceAtLeast(0)
                        val s99y = y shr (zoom - 14).coerceAtLeast(0)
                        val key = "14/$s99x/$s99y"
                        val ftMap = synchronized(style99Cache) { style99Cache[key] } ?: continue
                        if (key in style99Added) continue

                        // Build ARGB bitmap from ft values, upscaled for smooth edges
                        val srcSize = 512; val dstSize = 1024  // 2x upscale for smooth rendering
                        val colors = IntArray(srcSize * srcSize) { i ->
                            when (ftMap[i]) {
                                94 -> 0xFFA8D4F0.toInt()  // water #A8D4F0
                                64 -> 0xFFD4E8C2.toInt()  // park  #D4E8C2
                                else -> 0x00000000         // transparent (land drawn by bg)
                            }
                        }
                        val srcBitmap = Bitmap.createBitmap(colors, srcSize, srcSize, Bitmap.Config.ARGB_8888)
                        val bitmap = android.graphics.Bitmap.createScaledBitmap(srcBitmap, dstSize, dstSize, true)
                        srcBitmap.recycle()

                        // Geographic bounds of the z=14 tile
                        val lonMin = s99x.toDouble() / n14 * 360.0 - 180.0
                        val lonMax = (s99x + 1).toDouble() / n14 * 360.0 - 180.0
                        val latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * s99y / n14))))
                        val latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (s99y + 1).toDouble() / n14))))

                        val srcId = "s99-$key"
                        val lyrId = "l-s99-$key"
                        try {
                            s.addSource(ImageSource(srcId, LatLngQuad(
                                LatLng(latMax, lonMin), LatLng(latMax, lonMax),
                                LatLng(latMin, lonMax), LatLng(latMin, lonMin)
                            ), bitmap))
                            // Add BELOW the land layer so polygons render on top
                            s.addLayerBelow(RasterLayer(lyrId, srcId).withProperties(
                                PropertyFactory.rasterOpacity(1.0f),
                                PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR)
                            ), "l-land")
                            style99Added.add(key)
                            Log.d(TAG, "Added style99 raster $key")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add style99 raster $key: ${e.message}")
                        }
                        bitmap.recycle()
                    }
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

    /**
     * Compute convex hull of a vertex list (Andrew's monotone chain).
     * Used to eliminate self-intersections in road polygon meshes.
     */
    private fun convexHull(points: List<Vertex>): List<Vertex> {
        if (points.size < 3) return points
        // Remove duplicates first
        val unique = points.distinctBy { "${it.x.toBits()},${it.y.toBits()}" }
        if (unique.size < 3) return unique
        val sorted = unique.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<Vertex>()
        for (p in sorted) {
            while (lower.size >= 2) {
                val a = lower[lower.size - 2]; val b = lower[lower.size - 1]
                val cross = (b.x - a.x).toDouble() * (p.y - a.y) - (b.y - a.y).toDouble() * (p.x - a.x)
                if (cross <= 0) lower.removeAt(lower.size - 1) else break
            }
            lower.add(p)
        }
        val upper = mutableListOf<Vertex>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2) {
                val a = upper[upper.size - 2]; val b = upper[upper.size - 1]
                val cross = (b.x - a.x).toDouble() * (p.y - a.y) - (b.y - a.y).toDouble() * (p.x - a.x)
                if (cross <= 0) upper.removeAt(upper.size - 1) else break
            }
            upper.add(p)
        }
        lower.addAll(upper.drop(1).dropLast(1))
        return lower
    }

    /**
     * Extract road centerline spine from a road area polygon using PCA.
     * Returns a LineString path through the center of the road polygon.
     * Works for simple road segments and complex junction areas.
     */
    private fun roadSpine(verts: List<Vertex>): List<Vertex> {
        if (verts.size < 2) return verts
        // Compute centroid
        val cx = verts.sumOf { it.x.toDouble() } / verts.size
        val cy = verts.sumOf { it.y.toDouble() } / verts.size
        // PCA: covariance matrix
        var covXX = 0.0; var covXY = 0.0; var covYY = 0.0
        for (v in verts) {
            val dx = v.x - cx; val dy = v.y - cy
            covXX += dx * dx; covXY += dx * dy; covYY += dy * dy
        }
        val n = verts.size.toDouble()
        covXX /= n; covXY /= n; covYY /= n
        // Principal direction (eigenvector of largest eigenvalue)
        val trace = covXX + covYY
        val det = covXX * covYY - covXY * covXY
        val lambda = trace / 2 + Math.sqrt(trace * trace / 4 - det)
        // Eigenvector direction
        val ex = if (Math.abs(covXY) > 1e-10) covXY else (lambda - covYY)
        val ey = if (Math.abs(covXY) > 1e-10) lambda - covXX else covXY
        val len = Math.sqrt(ex * ex + ey * ey)
        if (len < 1e-10) return listOf(Vertex(cx.toFloat(), cy.toFloat()))
        val nx = (ex / len).toFloat(); val ny = (ey / len).toFloat()
        // Project all vertices onto principal axis
        val projections = verts.map { v -> (v.x - cx) * nx + (v.y - cy) * ny }
        val minP = projections.min()
        val maxP = projections.max()
        // Generate spine points along the principal axis
        val spineCount = minOf(8, maxOf(2, (verts.size / 8)))
        return (0 until spineCount).map { k ->
            val t = minP + (maxP - minP) * k / (spineCount - 1)
            Vertex((cx + t * nx).toFloat(), (cy + t * ny).toFloat())
        }
    }

    /** Fetch and decode style 99 tile (per-pixel style class raster). */
    private suspend fun fetchStyle99(z: Int, x: Int, y: Int): IntArray? {
        return try {
            val url = NativeAuth.style99TileUrl(z, x, y)
            val bytes = client.get(url).bodyAsBytes()
            if (bytes.size < 8) {
                Log.w(TAG, "Style99 $z/$x/$y: too small ${bytes.size}b")
                null
            } else {
                Style99Decoder.decode(bytes).also { result ->
                    if (result != null)
                        Log.d(TAG, "Style99 $z/$x/$y: decoded ${result.size} pixels")
                    else
                        Log.w(TAG, "Style99 $z/$x/$y: decode failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Style99 $z/$x/$y fetch failed: ${e.message}")
            null
        }
    }

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
{"version":8,"name":"Apple Maps","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#F2EFE9"}}]}
""".trim()
