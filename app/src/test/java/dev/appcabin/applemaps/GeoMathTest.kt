package dev.appcabin.applemaps

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for the pure geo-math functions in AppleMapView.kt.
 * These run on JVM (no Android device needed).
 */
class GeoMathTest {

    private val DELTA = 0.0001  // tolerance for double comparisons

    // --- latLngToTile ---

    @Test
    fun `latLngToTile at z0 returns tile 0,0 for null island`() {
        val (x, y) = latLngToTile(0.0, 0.0, 0)
        assertEquals(0.5, x, DELTA)
        assertEquals(0.5, y, DELTA)
    }

    @Test
    fun `latLngToTile at z0 returns correct for top-left corner`() {
        // (-180, 85.051...) should map to (0, 0)
        val (x, y) = latLngToTile(85.0511, -180.0, 0)
        assertEquals(0.0, x, 0.01)
        assertEquals(0.0, y, 0.01)
    }

    @Test
    fun `latLngToTile Prague at z14`() {
        // Prague: 50.0755°N, 14.4378°E
        val (x, y) = latLngToTile(50.0755, 14.4378, 14)
        // Known tile: x≈8849.08, y≈5551.20 at z14
        assertEquals(8849.08, x, 0.1)
        assertEquals(5551.20, y, 0.1)
    }

    @Test
    fun `latLngToTile zoom level scales correctly`() {
        val (x1, y1) = latLngToTile(50.0755, 14.4378, 10)
        val (x2, y2) = latLngToTile(50.0755, 14.4378, 11)
        // z+1 should double the tile coords
        assertEquals(x1 * 2, x2, 0.001)
        assertEquals(y1 * 2, y2, 0.001)
    }

    @Test
    fun `latLngToTile negative coordinates work`() {
        // Rio: -22.9, -43.17
        val (x, y) = latLngToTile(-22.9, -43.17, 10)
        assertTrue("x should be positive", x > 0)
        assertTrue("y should be >512 (southern hemisphere)", y > 512)
    }

    // --- tileToLatLng ---

    @Test
    fun `tileToLatLng inverts latLngToTile`() {
        val lat = 50.0755
        val lng = 14.4378
        val zoom = 14
        val (tx, ty) = latLngToTile(lat, lng, zoom)
        val result = tileToLatLng(tx, ty, zoom)
        assertEquals(lat, result.lat, 0.0001)
        assertEquals(lng, result.lng, 0.0001)
    }

    @Test
    fun `tileToLatLng roundtrip for equator`() {
        val lat = 0.0
        val lng = 0.0
        for (z in 0..18) {
            val (tx, ty) = latLngToTile(lat, lng, z)
            val result = tileToLatLng(tx, ty, z)
            assertEquals("Lat roundtrip at z$z", lat, result.lat, 0.001)
            assertEquals("Lng roundtrip at z$z", lng, result.lng, 0.001)
        }
    }

    @Test
    fun `tileToLatLng roundtrip for southern hemisphere`() {
        val lat = -33.8688
        val lng = 151.2093 // Sydney
        val zoom = 12
        val (tx, ty) = latLngToTile(lat, lng, zoom)
        val result = tileToLatLng(tx, ty, zoom)
        assertEquals(lat, result.lat, 0.001)
        assertEquals(lng, result.lng, 0.001)
    }

    @Test
    fun `tileToLatLng roundtrip for multiple cities`() {
        val cities = listOf(
            LatLng(48.8566, 2.3522),     // Paris
            LatLng(40.7128, -74.0060),   // New York
            LatLng(35.6762, 139.6503),   // Tokyo
            LatLng(-33.8688, 151.2093),  // Sydney
            LatLng(55.7558, 37.6173),    // Moscow
            LatLng(1.3521, 103.8198),    // Singapore
        )
        for (city in cities) {
            for (z in 4..16) {
                val (tx, ty) = latLngToTile(city.lat, city.lng, z)
                val result = tileToLatLng(tx, ty, z)
                assertEquals("${city.lat},${city.lng} lat at z$z", city.lat, result.lat, 0.001)
                assertEquals("${city.lat},${city.lng} lng at z$z", city.lng, result.lng, 0.001)
            }
        }
    }

    // --- latLngToPixel ---

    @Test
    fun `latLngToPixel center point maps to screen center`() {
        val center = LatLng(50.0755, 14.4378)
        val result = latLngToPixel(center, center, 14, 512f, 1080f, 1920f)
        assertEquals(1080f / 2, result.x, 1f)
        assertEquals(1920f / 2, result.y, 1f)
    }

    @Test
    fun `latLngToPixel point east of center is to the right`() {
        val center = LatLng(50.0755, 14.4378)
        val east = LatLng(50.0755, 14.45) // slightly east
        val result = latLngToPixel(east, center, 14, 512f, 1080f, 1920f)
        assertTrue("East point should be right of center", result.x > 1080f / 2)
    }

    @Test
    fun `latLngToPixel point north of center is above`() {
        val center = LatLng(50.0755, 14.4378)
        val north = LatLng(50.09, 14.4378) // slightly north
        val result = latLngToPixel(north, center, 14, 512f, 1080f, 1920f)
        assertTrue("North point should be above center (smaller y)", result.y < 1920f / 2)
    }

    @Test
    fun `latLngToPixel symmetry — equal distances opposite directions`() {
        val center = LatLng(50.0755, 14.4378)
        val east = LatLng(50.0755, 14.45)
        val west = LatLng(50.0755, 14.4378 - (14.45 - 14.4378))
        val re = latLngToPixel(east, center, 14, 512f, 1080f, 1920f)
        val rw = latLngToPixel(west, center, 14, 512f, 1080f, 1920f)
        val cx = 1080f / 2
        assertEquals("Symmetric pixel displacement", re.x - cx, cx - rw.x, 1f)
    }

    // --- Drag simulation (the bug fix) ---

    @Test
    fun `simulated drag updates center correctly`() {
        // Simulate the exact gesture math from AppleMapView
        var center = LatLng(50.0755, 14.4378)
        var zoom = 14f
        val tileSize = 512f

        // Drag right 200px (should move center west / lower lng)
        val panX = 200f
        val panY = 0f
        val z = zoom.toInt()
        val (cx, cy) = latLngToTile(center.lat, center.lng, z)
        val newCx = cx - panX / tileSize
        val newCy = cy - panY / tileSize
        val newCenter = tileToLatLng(newCx, newCy, z)

        assertEquals("Lat shouldn't change on horizontal drag", center.lat, newCenter.lat, 0.001)
        assertTrue("Dragging right should decrease lng (move west)", newCenter.lng < center.lng)
    }

    @Test
    fun `simulated drag up moves north`() {
        var center = LatLng(50.0755, 14.4378)
        val tileSize = 512f
        val z = 14

        // Drag up 300px
        val (cx, cy) = latLngToTile(center.lat, center.lng, z)
        val newCy = cy - 300f / tileSize
        val newCenter = tileToLatLng(cx, newCy, z)

        assertTrue("Dragging up should increase lat (move north)", newCenter.lat > center.lat)
        assertEquals("Lng shouldn't change on vertical drag", center.lng, newCenter.lng, 0.001)
    }

    @Test
    fun `multiple sequential drags accumulate correctly`() {
        // This is THE bug scenario — gestures must read fresh state each time
        var center = LatLng(50.0755, 14.4378)
        var zoom = 14f
        val tileSize = 512f

        // 5 sequential rightward drags of 100px each
        repeat(5) {
            val z = zoom.toInt()
            val (cx, cy) = latLngToTile(center.lat, center.lng, z)
            val newCx = cx - 100f / tileSize
            center = tileToLatLng(newCx, cy, z)
        }

        // Should have moved a noticeable amount west
        assertTrue("5 drags should accumulate", center.lng < 14.4378 - 0.01)

        // And one big drag of 500px from origin should give roughly the same result
        val z = zoom.toInt()
        val (cx, cy) = latLngToTile(50.0755, 14.4378, z)
        val bigDrag = tileToLatLng(cx - 500f / tileSize, cy, z)

        assertEquals("5×100px ≈ 1×500px", bigDrag.lng, center.lng, 0.001)
    }

    @Test
    fun `zoom gesture changes zoom level`() {
        var zoom = 14f
        val gestureZoom = 2.0f  // pinch out = 2x
        val newZoom = (zoom + ln(gestureZoom.toDouble()).toFloat() / ln(2.0f))
            .coerceIn(2f, 19f)
        assertEquals("2x pinch should add 1 zoom level", 15f, newZoom, 0.01f)
    }

    @Test
    fun `zoom coerces to bounds`() {
        // Pinch way in at z2
        var zoom = 2f
        val gestureZoom = 0.1f  // aggressive zoom out
        val newZoom = (zoom + ln(gestureZoom.toDouble()).toFloat() / ln(2.0f))
            .coerceIn(2f, 19f)
        assertEquals("Zoom should not go below 2", 2f, newZoom, 0.01f)

        // Pinch way out at z19
        zoom = 19f
        val gestureZoom2 = 4.0f
        val newZoom2 = (zoom + ln(gestureZoom2.toDouble()).toFloat() / ln(2.0f))
            .coerceIn(2f, 19f)
        assertEquals("Zoom should not go above 19", 19f, newZoom2, 0.01f)
    }

    // --- Edge cases ---

    @Test
    fun `tile coordinates wrap correctly at antimeridian`() {
        // Just west of antimeridian
        val (x1, _) = latLngToTile(0.0, 179.99, 10)
        // Just east of antimeridian
        val (x2, _) = latLngToTile(0.0, -179.99, 10)
        val n = 1 shl 10
        assertTrue("x near east edge should be close to n", x1 > n - 1)
        assertTrue("x near west edge should be close to 0", x2 < 1)
    }

    @Test
    fun `latLng data class equality`() {
        val a = LatLng(50.0755, 14.4378)
        val b = LatLng(50.0755, 14.4378)
        assertEquals(a, b)
        assertNotEquals(a, LatLng(50.0755, 14.44))
    }
}