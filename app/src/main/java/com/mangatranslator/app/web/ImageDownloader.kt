package com.mangatranslator.app.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * دانلود تصاویر صفحه وب با همان هویت WebView (User-Agent، Referer و کوکی)،
 * تا سایت‌هایی که hotlink را می‌بندند یا کوکی Cloudflare می‌خواهند هم کار کنند.
 */
object ImageDownloader {

    private const val MAX_BYTES = 30L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun sameSite(a: String, b: String): Boolean = try {
        java.net.URI(a).host == java.net.URI(b).host
    } catch (e: Exception) { false }

    suspend fun download(url: String, referer: String, userAgent: String, cookie: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            repeat(2) {
                try {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", userAgent)
                        .header("Referer", referer)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", if (sameSite(url, referer)) "same-origin" else "cross-site")
                        .header("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5")
                        .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                        .build()
                    client.newCall(req).execute().use { r ->
                        if (r.isSuccessful) {
                            val body = r.body ?: return@use
                            if (body.contentLength() in 1..MAX_BYTES || body.contentLength() < 0) {
                                val bytes = body.bytes()
                                if (bytes.size.toLong() <= MAX_BYTES && bytes.isNotEmpty()) return@withContext bytes
                            }
                        }
                    }
                } catch (e: Exception) {
                    // تلاش دوباره
                }
            }
            null
        }
}
