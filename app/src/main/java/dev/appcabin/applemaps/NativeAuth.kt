package dev.appcabin.applemaps

import android.util.Base64
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Native Apple Maps tile authentication.
 * Algorithm: AES-CBC(SHA-256(token), path+sid+timestamp+p3)
 * Derived from GeoServices.framework decompilation.
 */
object NativeAuth {
    // Hardcoded in GeoServices binary
    private const val TOKEN_P1 = "4cjLaD4jGRwlQ9U"
    // From resource manifest field 31
    private const val TOKEN_P2 = "72xIzEBe0vHBmf9"

    private val sessionId = buildString {
        repeat(40) { append(Random.nextInt(10)) }
    }

    private val chars = ('0'..'9') + ('a'..'z') + ('A'..'Z')

    fun signUrl(url: String): String {
        val p3 = buildString { repeat(16) { append(chars.random()) } }
        val token = TOKEN_P1 + TOKEN_P2 + p3
        val ts = (System.currentTimeMillis() / 1000) + 4200

        val qIdx = url.indexOf('?')
        val sep = if (qIdx >= 0) "&" else "?"
        val parsed = java.net.URL(url)
        val path = if (parsed.query != null) "${parsed.path}?${parsed.query}" else parsed.path

        val plaintext = "$path${sep}sid=$sessionId$ts$p3"
        val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        val b64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val encoded = URLEncoder.encode(b64, "UTF-8")

        return "$url${sep}sid=$sessionId&accessKey=${ts}_${p3}_$encoded"
    }

    /** Standard vector map tile URL (VMP4 format)
     *  style 1  → flags=8, v=20028707 (polygons + sparse roads)
     *  style 20 → no flags, v=20027943 (dense road network overlay)
     */
    fun vectorTileUrl(z: Int, x: Int, y: Int, style: Int = 1): String {
        val base = when (style) {
            20 -> "https://gspe19-ssl.ls.apple.com/tile.vf?style=20&size=2&scale=0&v=20027943&z=$z&x=$x&y=$y"
            else -> "https://gspe19-ssl.ls.apple.com/tile.vf?flags=8&style=$style&size=2&scale=0&v=20028707&z=$z&x=$x&y=$y"
        }
        return signUrl(base)
    }

    /** Satellite raster tile URL (JPEG) */
    fun satelliteTileUrl(z: Int, x: Int, y: Int, version: Int = 10441): String {
        val base = "https://gspe11-ssl.ls.apple.com/tile?style=7&size=2&scale=0&v=$version&z=$z&x=$x&y=$y"
        return signUrl(base)
    }

    /** Road overlay tile URL (VMP4) */
    fun roadOverlayTileUrl(z: Int, x: Int, y: Int, version: Int = 20028707): String {
        val base = "https://gspe19-ssl.ls.apple.com/tile.vf?flags=8&style=1&size=2&scale=0&v=$version&z=$z&x=$x&y=$y"
        return signUrl(base)
    }
}
