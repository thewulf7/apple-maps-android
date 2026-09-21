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
 * E2E tests for Apple Maps search — autocomplete and full search
 * against live Apple servers. Runs on device.
 */
@RunWith(AndroidJUnit4::class)
class SearchE2ETest {

    private lateinit var client: HttpClient
    private lateinit var auth: AppleMapAuth
    private lateinit var search: AppleMapSearch

    @Before
    fun setup() {
        client = HttpClient(Android) {
            engine {
                connectTimeout = 15_000
                socketTimeout = 15_000
            }
        }
        auth = AppleMapAuth(client)
        search = AppleMapSearch(client, auth)
    }

    @After
    fun teardown() {
        client.close()
    }

    // --- Autocomplete ---

    @Test
    fun autocomplete_returns_results_for_coffee_in_prague() = runTest {
        val results = search.autocomplete("coffee", 50.0755, 14.4378)

        assertTrue("Should return autocomplete results", results.isNotEmpty())
        assertTrue("Should have at least 3 results", results.size >= 3)

        // At least one result should contain "coffee" in name (case-insensitive)
        val hasCoffee = results.any { it.name.contains("coffee", ignoreCase = true) }
        assertTrue("At least one result should mention 'coffee'", hasCoffee)
    }

    @Test
    fun autocomplete_returns_displayLines_as_name_subtitle() = runTest {
        val results = search.autocomplete("restaurant", 50.0755, 14.4378)

        assertTrue("Should return results", results.isNotEmpty())

        for (result in results) {
            assertTrue("Name should not be empty", result.name.isNotEmpty())
            // Subtitle can be empty for "Search Nearby" completions
        }
    }

    @Test
    fun autocomplete_has_location_for_specific_places() = runTest {
        val results = search.autocomplete("Starbucks", 50.0755, 14.4378)

        // At least one specific Starbucks should have coordinates
        val withCoords = results.filter { it.lat != 0.0 && it.lng != 0.0 }
        assertTrue("At least one Starbucks should have coordinates", withCoords.isNotEmpty())

        for (r in withCoords) {
            // Coords should be roughly in Czech Republic / Europe
            assertTrue("Lat should be plausible (20-70)", r.lat in 20.0..70.0)
            assertTrue("Lng should be plausible (-30..50)", r.lng in -30.0..50.0)
        }
    }

    @Test
    fun autocomplete_with_short_query_returns_results() = runTest {
        val results = search.autocomplete("pr", 50.0755, 14.4378)
        // Even 2-char query should return something
        assertTrue("2-char query should return results", results.isNotEmpty())
    }

    // --- Full search ---

    @Test
    fun search_returns_results_for_restaurant_in_prague() = runTest {
        val results = search.search("restaurant prague", 50.0755, 14.4378)

        assertTrue("Should return search results", results.isNotEmpty())

        for (result in results) {
            assertTrue("Name should not be empty", result.name.isNotEmpty())
            assertTrue("Lat should be non-zero", result.lat != 0.0)
            assertTrue("Lng should be non-zero", result.lng != 0.0)
        }
    }

    @Test
    fun search_results_have_subtitles() = runTest {
        val results = search.search("hotel prague", 50.0755, 14.4378)

        assertTrue("Should return results", results.isNotEmpty())
        // At least some results should have address/subtitle
        val withSubtitle = results.count { it.subtitle.isNotEmpty() }
        assertTrue("At least some results should have subtitles", withSubtitle > 0)
    }

    @Test
    fun search_location_bias_works() = runTest {
        // Search for "Eiffel Tower" near Paris
        val results = search.search("Eiffel Tower", 48.8566, 2.3522)

        assertTrue("Should return results", results.isNotEmpty())
        val first = results.first()
        // Should be near Paris
        assertTrue("First result lat should be near Paris", first.lat in 48.0..49.0)
        assertTrue("First result lng should be near Paris", first.lng in 2.0..3.0)
    }
}