package dev.appcabin.applemaps

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Decoder for Apple Maps style 99 tiles — per-pixel style class raster.
 *
 * Style 99 tiles are 512×512 16-bit grayscale PNGs fetched from:
 *   https://gspe11-ssl.ls.apple.com/tile?style=99&size=2&scale=0&v=654&z=Z&x=X&y=Y
 *
 * Each pixel value = style class index:
 *   0  = land (default, beige)
 *   1  = water (blue, confirmed = Vltava river)
 *   2  = park / vegetation (green)
 *   3-4 = water variants (river/shore)
 *   5+ = various urban features (all rendered as land)
 *
 * Version 654 extracted from ActiveTileGroup.pbd (macOS GeoServices cache).
 *
 * Confirmed Sep 24 2026 via direct pixel sampling at known Prague landmarks.
 */
object Style99Decoder {

    private const val TAG = "Style99Decoder"
    private const val EXPECTED_WIDTH = 512
    private const val EXPECTED_HEIGHT = 512

    /**
     * Style class to feature-type mapping for NativeMapView FillLayer filters.
     * These ft values match the existing MapLibre layer expressions in NativeMapView.
     *
     * Apple internal class IDs:
     *   0 = land/default         → ft 0  → #F2EFE9 beige
     *   1 = water (river/lake)   → ft 94 → #A8D4F0 blue
     *   2 = park/green space     → ft 64 → #D4E8C2 green
     *   3 = river surface        → ft 94 → #A8D4F0 blue (water variant)
     *   4 = shore/riverbank      → ft 94 → #A8D4F0 blue (water variant)
     */
    private val CLASS_TO_FT = IntArray(256) { 0 }.also { a ->
        a[1] = 94  // water
        a[2] = 64  // park
        a[3] = 94  // water variant
        a[4] = 94  // water variant
    }

    /**
     * Decode a style 99 PNG tile.
     * @param pngBytes Raw bytes of the 16-bit grayscale PNG
     * @return IntArray[512*512] of ft values (0=land, 64=park, 94=water)
     *         or null if decoding failed.
     */
    fun decode(pngBytes: ByteArray): IntArray? {
        if (pngBytes.size < 8) return null

        // Verify PNG magic
        val magic = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (!pngBytes.sliceArray(0..7).contentEquals(magic)) {
            Log.w(TAG, "Not a PNG file (magic mismatch)")
            return null
        }

        var width = 0; var height = 0; var bitDepth = 0; var colorType = 0
        val idatData = ByteArrayOutputStream()
        var pos = 8

        // Parse PNG chunks
        while (pos + 12 <= pngBytes.size) {
            val chunkLen = ByteBuffer.wrap(pngBytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            if (chunkLen < 0 || pos + 12 + chunkLen > pngBytes.size) break

            val chunkType = String(pngBytes, pos + 4, 4, Charsets.US_ASCII)
            when (chunkType) {
                "IHDR" -> {
                    val ihdr = ByteBuffer.wrap(pngBytes, pos + 8, 13).order(ByteOrder.BIG_ENDIAN)
                    width = ihdr.int; height = ihdr.int
                    bitDepth = ihdr.get().toInt() and 0xFF
                    colorType = ihdr.get().toInt() and 0xFF
                }
                "IDAT" -> idatData.write(pngBytes, pos + 8, chunkLen)
                "IEND" -> break
            }
            pos += 12 + chunkLen
        }

        if (width != EXPECTED_WIDTH || height != EXPECTED_HEIGHT || bitDepth != 16 || colorType != 0) {
            Log.w(TAG, "Unexpected PNG format: ${width}×${height} depth=$bitDepth colorType=$colorType")
            return null
        }

        // Decompress IDAT
        val bpp = 2  // 16-bit grayscale = 2 bytes per pixel
        val rawStride = width * bpp + 1  // 1 filter byte + pixel data per row
        val rawSize = rawStride * height

        val inflater = Inflater()
        inflater.setInput(idatData.toByteArray())
        val raw = ByteArray(rawSize)
        try {
            var n = 0
            while (!inflater.finished() && n < rawSize) {
                n += inflater.inflate(raw, n, rawSize - n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inflate failed: ${e.message}")
            return null
        } finally {
            inflater.end()
        }

        // Reconstruct pixels applying PNG filters
        val pixels = ByteArray(width * height * bpp)

        for (y in 0 until height) {
            val filterType = raw[y * rawStride].toInt() and 0xFF
            val rowBase = y * rawStride + 1  // skip filter byte
            val curBase = y * width * bpp
            val prevBase = (y - 1) * width * bpp

            for (i in 0 until width * bpp) {
                val src = raw[rowBase + i].toInt() and 0xFF
                val a = if (i >= bpp) pixels[curBase + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) pixels[prevBase + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) pixels[prevBase + i - bpp].toInt() and 0xFF else 0

                pixels[curBase + i] = when (filterType) {
                    0 -> src                           // None
                    1 -> (src + a) and 0xFF            // Sub
                    2 -> (src + b) and 0xFF            // Up
                    3 -> (src + (a + b) / 2) and 0xFF  // Average
                    4 -> (src + paethPredictor(a, b, c)) and 0xFF  // Paeth
                    else -> src
                }.toByte()
            }
        }

        // Extract 16-bit big-endian pixel values and map to ft
        val result = IntArray(width * height)
        for (i in 0 until width * height) {
            val hi = pixels[i * 2].toInt() and 0xFF
            val lo = pixels[i * 2 + 1].toInt() and 0xFF
            val styleClass = (hi shl 8) or lo
            result[i] = if (styleClass < CLASS_TO_FT.size) CLASS_TO_FT[styleClass] else 0
        }

        return result
    }

    /**
     * Sample the ft value at a tile-local normalized coordinate (0..1).
     * @param ftMap Result of decode()
     * @param normX Tile-local x coordinate (0=left, 1=right)
     * @param normY Tile-local y coordinate (0=top, 1=bottom)
     */
    fun sampleFt(ftMap: IntArray, normX: Float, normY: Float): Int {
        val px = (normX * EXPECTED_WIDTH).toInt().coerceIn(0, EXPECTED_WIDTH - 1)
        val py = (normY * EXPECTED_HEIGHT).toInt().coerceIn(0, EXPECTED_HEIGHT - 1)
        return ftMap[py * EXPECTED_WIDTH + px]
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
