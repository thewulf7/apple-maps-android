package dev.appcabin.applemaps

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.*
import io.ktor.client.engine.android.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented E2E tests — run on real device.
 * Tests the full auth → search → tile pipeline against Apple's live servers.
 */
@RunWith(AndroidJUnit4::class)
class AuthE2ETest {

    private lateinit var client: HttpClient
    private lateinit var auth: AppleMapAuth

    @Before
    fun setup() {
        client = HttpClient(Android) {
            engine {
                connectTimeout = 15_000
                socketTimeout = 15_000
            }
        }
        auth = AppleMapAuth(client)
    }

    @After
    fun teardown() {
        client.close()
    }

    @Test
    fun auth_getToken_returns_valid_token() = runTest {
        val token = auth.getToken()

        // accessKey should be non-empty
        assertTrue("accessKey should not be empty", token.accessKey.isNotEmpty())
        assertTrue("accessKey should contain underscore separator", token.accessKey.contains("_"))

        // searchToken should be a JWT (3 dot-separated parts)
        assertTrue("searchToken should not be empty", token.searchToken.isNotEmpty())
        val parts = token.searchToken.split(".")
        assertEquals("searchToken should be JWT (3 parts)", 3, parts.size)

        // tileSources should contain standard at minimum
        assertTrue("Should have tile sources", token.tileSources.isNotEmpty())
        assertNotNull("Should have 'standard' tile source", token.tileSources["standard"])

        // expiresAt should be in the future
        assertTrue("Token should expire in the future", token.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun auth_getToken_returns_all_tile_sources() = runTest {
        val token = auth.getToken()

        val expected = setOf("standard", "satellite", "hybrid-overlay")
        for (name in expected) {
            val source = token.tileSources[name]
            assertNotNull("Missing tile source: $name", source)
            assertTrue("$name should have domains", source!!.domains.isNotEmpty())
            assertTrue("$name pathTemplate should contain placeholders",
                source.pathTemplate.contains("{{"))
        }
    }

    @Test
    fun auth_token_is_cached_on_second_call() = runTest {
        val t1 = auth.getToken()
        val t2 = auth.getToken()
        // Same object reference (cached)
        assertSame("Second getToken() should return cached token", t1, t2)
    }

    @Test
    fun auth_tileUrl_builds_valid_url() = runTest {
        val url = auth.tileUrl("standard", 8832, 5550, 14)

        assertTrue("URL should be https", url.startsWith("https://"))
        assertTrue("URL should contain apple-mapkit", url.contains("apple-mapkit.com"))
        assertFalse("URL should not contain template placeholders", url.contains("{{"))
    }
}