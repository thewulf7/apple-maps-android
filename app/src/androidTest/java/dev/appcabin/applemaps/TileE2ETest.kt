package dev.appcabin.applemaps

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E tests for tile fetching — verifies tile URLs resolve and return image data.
 * Runs on device against Apple's live tile CDN.
 */
@RunWith(AndroidJUnit4::class)
class TileE2ETest {

    private lateinit var client: HttpClient
    private lateinit var auth: AppleMapAuth

    @Before
    fun setup() {
        client = HttpClient(Android) {
            engine {
                connectTimeout = 15_000
                socketTimeout = 30_000
            }
        }
        auth = AppleMapAuth(client)
    }

    @After
    fun teardown() {
        client.close()
    }

    @Test
    fun standard_tile_url_returns_image_bytes() = runTest {
        // Prague center at z14
        val url = auth.tileUrl("standard", 8832, 5550, 14)
        val bytes = client.get(url) {
            header("Origin", "https://duckduckgo.com")
            header("Referer", "https://duckduckgo.com/")
        }.readRawBytes()

        assertTrue("Tile should have content (>1KB)", bytes.size > 1000)
        // PNG starts with 0x89 0x50 0x4E 0x47
        assertTrue("Tile should be PNG",
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte())
    }

    @Test
    fun satellite_tile_url_returns_image_bytes() = runTest {
        val url = auth.tileUrl("satellite", 8832, 5550, 14)
        val bytes = client.get(url) {
            header("Origin", "https://duckduckgo.com")
            header("Referer", "https://duckduckgo.com/")
        }.readRawBytes()

        assertTrue("Satellite tile should have content (>1KB)", bytes.size > 1000)
        // JPEG starts with 0xFF 0xD8
        assertTrue("Satellite tile should be JPEG",
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    }

    @Test
    fun tile_url_different_locations_return_different_data() = runTest {
        // Two distant tiles should return different data
        val url1 = auth.tileUrl("standard", 8832, 5550, 14) // Prague
        val url2 = auth.tileUrl("standard", 4825, 6160, 14) // New York area

        val bytes1 = client.get(url1) {
            header("Origin", "https://duckduckgo.com")
            header("Referer", "https://duckduckgo.com/")
        }.readRawBytes()

        val bytes2 = client.get(url2) {
            header("Origin", "https://duckduckgo.com")
            header("Referer", "https://duckduckgo.com/")
        }.readRawBytes()

        assertFalse("Different location tiles should differ",
            bytes1.contentEquals(bytes2))
    }

    @Test
    fun multiple_zoom_levels_return_valid_tiles() = runTest {
        // Test tiles at z5, z10, z14
        for (z in listOf(5, 10, 14)) {
            val (tx, ty) = latLngToTile(50.0755, 14.4378, z)
            val url = auth.tileUrl("standard", tx.toInt(), ty.toInt(), z)
            val bytes = client.get(url) {
                header("Origin", "https://duckduckgo.com")
                header("Referer", "https://duckduckgo.com/")
            }.readRawBytes()

            assertTrue("Tile at z$z should have content", bytes.size > 500)
        }
    }
}