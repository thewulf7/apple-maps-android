package dev.appcabin.applemaps

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Handles Apple MapKit JS authentication flow:
 * 1. Get JWT from DuckDuckGo's MapKit token endpoint
 * 2. Exchange for accessKey + tile URL templates + search access_token via Apple bootstrap
 *
 * Token auto-refreshes every 25 min (keys expire at 30).
 */
class AppleMapAuth(private val client: HttpClient) {

    companion object {
        private const val TAG = "AppleMapAuth"
    }

    data class TileSource(
        val name: String,
        val domains: List<String>,
        val pathTemplate: String,
        val minZoom: Int,
        val maxZoom: Int,
    )

    data class Token(
        val accessKey: String,
        val searchToken: String,          // authInfo.access_token — for search/geocode API
        val tileSources: Map<String, TileSource>,
        val expiresAt: Long,
    )

    private val mutex = Mutex()
    private var currentToken: Token? = null

    suspend fun getToken(): Token = mutex.withLock {
        val t = currentToken
        if (t != null && System.currentTimeMillis() < t.expiresAt) return t
        return refresh().also { currentToken = it }
    }

    private suspend fun refresh(): Token {
        Log.d(TAG, "Refreshing token...")

        // Step 1: JWT from DuckDuckGo
        val ddgBody = client.get("https://duckduckgo.com/local.js?get_mk_token=1").bodyAsText()
        Log.d(TAG, "DDG response length: ${ddgBody.length}")
        val jwt = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
            .find(ddgBody)?.value ?: error("Failed to get MapKit JWT from DDG response")
        Log.d(TAG, "Got JWT: ${jwt.take(30)}...")

        // Step 2: Bootstrap
        val bootstrapBody = client.get("https://cdn.apple-mapkit.com/ma/bootstrap") {
            parameter("apiVersion", "2")
            parameter("mkjsVersion", "5.79.95")
            parameter("poi", "1")
            header("Origin", "https://duckduckgo.com")
            header("Authorization", "Bearer $jwt")
        }.bodyAsText()
        Log.d(TAG, "Bootstrap response length: ${bootstrapBody.length}")

        val json = Json.parseToJsonElement(bootstrapBody).jsonObject
        val accessKey = json["accessKey"]!!.jsonPrimitive.content
        Log.d(TAG, "Got accessKey: ${accessKey.take(30)}...")

        // Extract authInfo.access_token for search/geocode API
        val authInfo = json["authInfo"]?.jsonObject
        val searchToken = authInfo?.get("access_token")?.jsonPrimitive?.content ?: ""
        if (searchToken.isNotEmpty()) {
            Log.d(TAG, "Got search token: ${searchToken.take(30)}...")
        } else {
            Log.w(TAG, "No authInfo.access_token in bootstrap response")
        }

        val sources = mutableMapOf<String, TileSource>()
        for (ts in json["tileSources"]!!.jsonArray) {
            val obj = ts.jsonObject
            val name = obj["tileSource"]?.jsonPrimitive?.content ?: continue
            val domains = obj["domains"]?.jsonArray?.map { it.jsonPrimitive.content } ?: continue
            val path = obj["path"]?.jsonPrimitive?.content ?: continue
            val minZ = obj["minZoomLevel"]?.jsonPrimitive?.intOrNull ?: 0
            val maxZ = obj["maxZoomLevel"]?.jsonPrimitive?.intOrNull ?: 20
            sources[name] = TileSource(name, domains, path, minZ, maxZ)
            Log.d(TAG, "  TileSource: $name -> ${domains.first()} [z$minZ-$maxZ]")
        }

        Log.d(TAG, "Token refresh complete. ${sources.size} tile sources.")
        return Token(
            accessKey = accessKey,
            searchToken = searchToken,
            tileSources = sources,
            expiresAt = System.currentTimeMillis() + 25 * 60 * 1000,
        )
    }

    /** Build a full tile URL for the given style/coords */
    suspend fun tileUrl(
        style: String, // "standard", "satellite", "hybrid-overlay"
        x: Int, y: Int, z: Int,
        lang: String = "en",
    ): String {
        val token = getToken()
        val source = token.tileSources[style] ?: error("Unknown tile source: $style")
        val domain = source.domains.first()
        val path = source.pathTemplate
            .replace("{{tileSizeIndex}}", "2")   // 512px tiles
            .replace("{{x}}", x.toString())
            .replace("{{y}}", y.toString())
            .replace("{{z}}", z.toString())
            .replace("{{resolution}}", "2")      // @2x
            .replace("{{lang}}", lang)
            .replace("{{poi}}", "1")
        return "https://$domain$path"
    }
}