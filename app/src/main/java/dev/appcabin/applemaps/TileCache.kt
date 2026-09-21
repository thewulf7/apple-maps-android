package dev.appcabin.applemaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Two-level tile cache: LRU memory + disk.
 * Memory: ~100 tiles (512x512 ARGB = ~1MB each, ~100MB budget)
 * Disk: unlimited, stored as PNG/JPEG in app cache dir
 */
class TileCache(context: Context) {

    companion object {
        private const val TAG = "TileCache"
        private const val MEM_CACHE_SIZE = 100
    }

    private val diskCacheDir = File(context.cacheDir, "tiles").also { it.mkdirs() }

    // Memory LRU
    private val memCache = object : LruCache<String, ImageBitmap>(MEM_CACHE_SIZE) {}

    /** Get from memory or disk. Returns null if not cached. */
    suspend fun get(key: String): ImageBitmap? {
        // L1: memory
        memCache.get(key)?.let { return it }

        // L2: disk
        return withContext(Dispatchers.IO) {
            val file = keyToFile(key)
            if (!file.exists()) return@withContext null
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
                val img = bmp.asImageBitmap()
                memCache.put(key, img)
                img
            } catch (e: Exception) {
                Log.w(TAG, "Disk cache read failed for $key: ${e.message}")
                file.delete()
                null
            }
        }
    }

    /** Store raw tile bytes to disk and decoded bitmap to memory. */
    suspend fun put(key: String, bytes: ByteArray): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Decode
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                val img = bmp.asImageBitmap()

                // Memory
                memCache.put(key, img)

                // Disk (write raw bytes — already compressed PNG/JPEG from server)
                val file = keyToFile(key)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { it.write(bytes) }

                img
            } catch (e: Exception) {
                Log.e(TAG, "Cache put failed for $key: ${e.message}")
                null
            }
        }
    }

    /** Check if key is in memory (fast path for Canvas draw loop). */
    fun getFromMemory(key: String): ImageBitmap? = memCache.get(key)

    /** Disk cache size in MB. */
    fun diskSizeMb(): Long {
        return diskCacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() } / (1024 * 1024)
    }

    /** Clear all caches. */
    fun clear() {
        memCache.evictAll()
        diskCacheDir.deleteRecursively()
        diskCacheDir.mkdirs()
    }

    private fun keyToFile(key: String): File {
        // key = "standard/14/8847/5554"
        return File(diskCacheDir, key.replace('/', File.separatorChar) + ".tile")
    }
}
