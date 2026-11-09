package dev.appcabin.applemaps

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Apple resource manifest — runtime tile-version discovery.
 * Kotlin equivalent of GEOResourceManifestManager (GeoServices).
 *
 * Endpoint: gspe35-ssl.ls.apple.com/geo_manifest/dynamic/config — no auth required.
 * Apple rotates the tile version epoch roughly weekly; tile servers answer
 * 410 Gone for stale versions. Hardcoded versions therefore rot within days.
 *
 * Protobuf structure (verified via protoc --decode_raw on live response):
 *   1 {                        // TileSetVersions
 *     1: <epoch>               // e.g. 2611082 (date-coded YYMMDD+patch)
 *     2 { 1: styleId, 2: ver } // repeated — per-style current version
 *   }
 *   30: "<token P2>"           // auth token part 2 for tile URL signing
 *
 * Fetched once at startup; [requestRefresh] re-fetches after repeated 410s.
 */
object ResourceManifest {
    private const val TAG = "ResourceManifest"

    // Params proven against the live endpoint (any plausible device profile works)
    private const val URL = "https://gspe35-ssl.ls.apple.com/geo_manifest/dynamic/config" +
        "?application=geod&application_version=1&country_code=US" +
        "&hardware=MacBookPro11,2&os=osx&os_build=20B29&os_version=11.0.1"

    /** Auth token part 2 — replaced by manifest field 30 once loaded. */
    @Volatile
    var tokenP2: String = "72xIzEBe0vHBmf9"
        private set

    @Volatile
    var epoch: Int = 0
        private set

    /** styleId -> current tile version */
    private val versions = ConcurrentHashMap<Int, Int>()

    @Volatile
    private var loaded = false
    private val mutex = Mutex()
    private val refreshRequested = AtomicBoolean(false)

    /** Current version for a tile style. Falls back to any known version. */
    fun version(style: Int): Int =
        versions[style] ?: versions.values.firstOrNull() ?: 0

    val isLoaded: Boolean get() = loaded

    suspend fun ensureLoaded(client: HttpClient) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            fetch(client)
        }
    }

    /**
     * Request a refresh after a 410 Gone burst. Coalesced: many concurrent
     * tile failures trigger exactly one re-fetch; next ensureLoaded() blocks on it.
     */
    fun requestRefresh() {
        if (refreshRequested.compareAndSet(false, true)) {
            loaded = false
        }
    }

    private suspend fun fetch(client: HttpClient) {
        try {
            val resp = client.get(URL)
            val status = resp.status.value
            if (status != 200) {
                Log.w(TAG, "Manifest HTTP $status")
                return
            }
            val bytes = resp.bodyAsBytes()
            parse(bytes)
            if (versions.isNotEmpty()) {
                loaded = true
                refreshRequested.set(false)
                Log.d(TAG, "Manifest loaded: epoch=$epoch, ${versions.size} styles, tokenP2=$tokenP2")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest fetch failed: ${e.message}")
        }
    }

    // ---- minimal protobuf wire-format walker (only the fields we need) ----

    private class Reader(val buf: ByteArray, var pos: Int = 0) {
        fun eof() = pos >= buf.size
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                r = r or ((b.toLong() and 0x7FL) shl s)
                if (b and 0x80 == 0) return r
                s += 7
            }
            return r
        }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            val out = buf.copyOfRange(pos, minOf(pos + len, buf.size))
            pos += len
            return out
        }
    }

    private fun parse(data: ByteArray) {
        val r = Reader(data)
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when (tag.toInt() and 7) {
                0 -> { // varint
                    val v = r.varint().toInt()
                    if (field == 1) epoch = v
                }
                2 -> { // length-delimited
                    val payload = r.bytes()
                    when (field) {
                        1 -> parseTileSetVersions(payload)
                        30 -> tokenP2 = String(payload, Charsets.UTF_8)
                    }
                }
                5 -> r.pos += 4
                1 -> r.pos += 8
                else -> return // unknown wire type — stop, keep what we have
            }
        }
    }

    private fun parseTileSetVersions(payload: ByteArray) {
        val r = Reader(payload)
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when (tag.toInt() and 7) {
                0 -> { if (field == 1) epoch = r.varint().toInt() else r.varint() }
                2 -> {
                    val sub = r.bytes()
                    if (field == 2) {
                        var style = -1; var ver = -1
                        val s = Reader(sub)
                        while (!s.eof()) {
                            val t2 = s.varint()
                            when (t2.toInt() and 7) {
                                0 -> {
                                    val v = s.varint().toInt()
                                    when ((t2 ushr 3).toInt()) {
                                        1 -> style = v
                                        2 -> ver = v
                                    }
                                }
                                2 -> s.bytes()
                                else -> return
                            }
                        }
                        if (style >= 0 && ver > 0) versions[style] = ver
                    }
                }
                5 -> r.pos += 4
                1 -> r.pos += 8
                else -> return
            }
        }
    }
}
