package com.pixiv.pixiv.advancesearch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small dependency-free image loader for the PID result cards. Pixiv's image
 * CDN rejects requests without a Pixiv referer, so set the same headers the
 * app uses for thumbnails.
 */
internal object PidImageLoader {
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    suspend fun load(url: String?, targetPx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(url) ?: runCatching {
                download(url, targetPx)?.also { cache.put(url, it) }
            }.getOrNull()
        }
    }

    private fun download(url: String, targetPx: Int): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            useCaches = true
            setRequestProperty("Referer", "https://app-api.pixiv.net/")
            setRequestProperty(
                "User-Agent",
                "PixivAndroidApp/6.205.0 (Android ${Build.VERSION.RELEASE})"
            )
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }
}
