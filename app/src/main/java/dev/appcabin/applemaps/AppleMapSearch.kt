package dev.appcabin.applemaps

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

/**
 * Apple Maps search via MapKit JS REST API (api.apple-mapkit.com/v1/).
 * Uses authInfo.access_token (NOT the accessKey, which is tiles-only).
 */
class AppleMapSearch(
    private val client: HttpClient,
    private val auth: AppleMapAuth,
) {
    companion object {
        private const val TAG = "AppleMapSearch"
    }

    data class SearchResult(
        val name: String,
        val subtitle: String,
        val lat: Double,
        val lng: Double,
        val category: String?,
        val isCompletion: Boolean = false,    // true = autocomplete suggestion, false = search result
    )

    /**
     * Full place search — returns POIs with coordinates.
     */
    suspend fun search(query: String, lat: Double, lng: Double): List<SearchResult> {
        val token = auth.getToken()
        if (token.searchToken.isEmpty()) {
            Log.w(TAG, "No search token available")
            return emptyList()
        }

        Log.d(TAG, "Search: '$query' near ($lat, $lng)")

        val response = client.get("https://api.apple-mapkit.com/v1/search") {
            parameter("q", query)
            parameter("lang", "en")
            parameter("searchLocation", "$lat,$lng")
            parameter("mkjsVersion", "5.79.95")
            header("Authorization", "Bearer ${token.searchToken}")
        }.bodyAsText()

        Log.d(TAG, "Search response (${response.length} chars): ${response.take(200)}")

        return parseSearchResults(response)
    }

    /**
     * Autocomplete — returns display suggestions as user types.
     * Some results have coordinates (POIs), others are completion strings.
     */
    suspend fun autocomplete(query: String, lat: Double, lng: Double): List<SearchResult> {
        val token = auth.getToken()
        if (token.searchToken.isEmpty()) {
            Log.w(TAG, "No search token available")
            return emptyList()
        }

        Log.d(TAG, "Autocomplete: '$query' near ($lat, $lng)")

        val response = client.get("https://api.apple-mapkit.com/v1/searchAutocomplete") {
            parameter("q", query)
            parameter("lang", "en")
            parameter("searchLocation", "$lat,$lng")
            parameter("mkjsVersion", "5.79.95")
            header("Authorization", "Bearer ${token.searchToken}")
        }.bodyAsText()

        Log.d(TAG, "Autocomplete response (${response.length} chars): ${response.take(200)}")

        return parseAutocompleteResults(response)
    }

    private fun parseSearchResults(body: String): List<SearchResult> {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val center = obj["center"]?.jsonObject ?: return@mapNotNull null
                val lat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lng = center["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null

                val addr = buildList {
                    obj["formattedAddressLines"]?.jsonArray?.forEach { add(it.jsonPrimitive.content) }
                    if (isEmpty()) {
                        obj["locality"]?.jsonPrimitive?.content?.let { add(it) }
                        obj["country"]?.jsonPrimitive?.content?.let { add(it) }
                    }
                }.joinToString(", ")

                val category = obj["poiCategory"]?.jsonPrimitive?.content

                SearchResult(name, addr, lat, lng, category)
            }.also { Log.d(TAG, "Parsed ${it.size} search results") }
        } catch (e: Exception) {
            Log.e(TAG, "Search parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseAutocompleteResults(body: String): List<SearchResult> {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { elem ->
                val obj = elem.jsonObject
                val displayLines = obj["displayLines"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: return@mapNotNull null
                val name = displayLines.firstOrNull() ?: return@mapNotNull null
                val subtitle = if (displayLines.size > 1) displayLines[1] else ""

                // Some autocomplete results have location, some don't
                val loc = obj["location"]?.jsonObject
                val lat = loc?.get("lat")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val lng = loc?.get("lng")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val hasLocation = lat != 0.0 && lng != 0.0

                val completionUrl = obj["completionUrl"]?.jsonPrimitive?.content

                SearchResult(
                    name = name,
                    subtitle = subtitle,
                    lat = lat,
                    lng = lng,
                    category = null,
                    isCompletion = !hasLocation && completionUrl != null,
                )
            }.also { Log.d(TAG, "Parsed ${it.size} autocomplete results") }
        } catch (e: Exception) {
            Log.e(TAG, "Autocomplete parse error: ${e.message}", e)
            emptyList()
        }
    }
}