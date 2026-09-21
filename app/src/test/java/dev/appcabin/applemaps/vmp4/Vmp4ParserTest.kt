package dev.appcabin.applemaps.vmp4

import org.junit.Test
import org.junit.Assert.*

class Vmp4ParserTest {

    private fun loadResource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun `parse VMP4 container - road overlay tile`() {
        val data = loadResource("test_road_overlay.vmp4")
        val tile = Vmp4Tile.parse(data)
        assertTrue("Should have sections", tile.sections.isNotEmpty())
        println("Road overlay: ${tile.sections.size} sections")
        tile.sections.forEach { println("  type=0x${it.typeId.toString(16)} size=${it.data.size}") }
    }

    @Test
    fun `parse VMP4 container - vector map tile`() {
        val data = loadResource("test_vector_map.vmp4")
        val tile = Vmp4Tile.parse(data)
        assertTrue("Should have sections", tile.sections.isNotEmpty())
        println("Vector map: ${tile.sections.size} sections")
    }

    @Test
    fun `decode vertices from road overlay`() {
        val data = loadResource("test_road_overlay.vmp4")
        val parsed = parseTile(data)
        assertNotNull("Should have vertices", parsed.vertices)
        val pool = parsed.vertices!!
        assertTrue("Should have vertices", pool.vertices.isNotEmpty())
        println("Vertices: ${pool.vertices.size}, shapes: ${pool.shapeStarts.size}, precision: ${pool.precisionBits} bits")
        // All coords should be in [0,1] range (tile-local)
        pool.vertices.forEach {
            assertTrue("x in range: ${it.x}", it.x in -0.1f..1.1f)
            assertTrue("y in range: ${it.y}", it.y in -0.1f..1.1f)
        }
    }

    @Test
    fun `decode features from road overlay`() {
        val data = loadResource("test_road_overlay.vmp4")
        val parsed = parseTile(data)
        println("Points: ${parsed.points.size}, Lines: ${parsed.lines.size}, Polygons: ${parsed.polygons.size}")
        assertTrue("Should have some geometry", parsed.points.isNotEmpty() || parsed.lines.isNotEmpty() || parsed.polygons.isNotEmpty())
    }

    @Test
    fun `decode labels from road overlay`() {
        val data = loadResource("test_road_overlay.vmp4")
        val parsed = parseTile(data)
        println("Labels: ${parsed.labels}")
        // Road overlay for Prague should have Czech place names
        assertTrue("Should have labels", parsed.labels.isNotEmpty())
    }

    @Test
    fun `generate GeoJSON from road overlay`() {
        val data = loadResource("test_road_overlay.vmp4")
        val parsed = parseTile(data)
        // Tile coords for Prague z14
        val geojson = toGeoJson(parsed, 8849, 5551, 14)
        assertTrue("Should be valid GeoJSON", geojson.startsWith("{\"type\":\"FeatureCollection\""))
        assertTrue("Should have features", geojson.contains("\"Feature\""))
        println("GeoJSON length: ${geojson.length} chars")
        // Print first 500 chars to inspect coordinates
        println("GeoJSON preview: ${geojson.take(500)}")
        // Print feature counts
        val polyCount = "\"Polygon\"".toRegex().findAll(geojson).count()
        val lineCount = "\"LineString\"".toRegex().findAll(geojson).count()
        val pointCount = "\"Point\"".toRegex().findAll(geojson).count()
        println("Features: $polyCount polygons, $lineCount lines, $pointCount points")
        // Coordinates should be near Prague (lon ~14.4, lat ~50.0)
        assertTrue("Should contain Prague-area coordinates", geojson.contains("14.") || geojson.contains("50."))
    }

    @Test
    fun `NativeAuth sign URL format check`() {
        // NativeAuth uses android.util.Base64, can't test in JVM unit tests
        // Tested via instrumented tests instead
    }

    @Test
    fun `tile to WGS84 conversion - Prague center`() {
        // Tile 8849/5551/14 should be near Prague (50.08°N, 14.42°E)
        val (lon, lat) = tileToWgs84(8849, 5551, 14, 0.5f, 0.5f)
        println("Tile center: lat=$lat, lon=$lon")
        assertTrue("Longitude near Prague", lon in 14.0..15.0)
        assertTrue("Latitude near Prague", lat in 49.5..50.5)
    }
}
