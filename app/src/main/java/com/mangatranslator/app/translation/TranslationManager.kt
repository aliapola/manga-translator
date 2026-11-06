package com.mangatranslator.app.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ترجمه فقط با MyMemory.
 *
 * بهینه‌سازی‌ها:
 *  1. درخواست‌های موازی (حداکثر [MAX_PARALLEL] همزمان) - [translateAll]
 *  2. کش دو‌سطحی: RAM (LruCache) + دیسک (SharedPreferences)
 *  3. خروجی تدریجی: هر ترجمه به محض رسیدن از Flow بیرون می‌آید
 *  4. فیلتر متن‌های بی‌ارزش (عدد، علامت، نویز OCR) و حذف متن‌های تکراری
 *  5. یک OkHttpClient مشترک با Keep-Alive و HTTP/2
 */
class TranslationManager(private val context: Context) {

    /** ایمیل اختیاری MyMemory؛ سقف روزانه را از ۵٬۰۰۰ به ۵۰٬۰۰۰ کلمه می‌رساند. */
    @Volatile var contactEmail: String = ""

    private val memCache = LruCache<String, String>(1000)
    private val diskCache: SharedPreferences =
        context.applicationContext.getSharedPreferences("translation_cache_v1", Context.MODE_PRIVATE)
    private val limiter = Semaphore(MAX_PARALLEL)

    // اگر MyMemory یک‌بار شکست خورد (قطعی/سهمیه)، مدتی دوباره تلاش نمی‌کنیم
    @Volatile private var cooldownUntil = 0L

    /** تبدیل خروجی به فارسی محاوره‌ای (کش همیشه متن خام MyMemory را نگه می‌دارد). */
    @Volatile var colloquial: Boolean = true

    fun updateConfig(email: String, colloquial: Boolean = true) {
        contactEmail = email
        this.colloquial = colloquial
    }

    /** فیلتر قبل از ارسال به API (مورد ۴). */
    fun shouldTranslate(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 2) return false
        if (!t.any { it.isLetter() }) return false                 // فقط عدد/علامت
        if (t.length > 3 && t.toSet().size <= 2) return false      // «!!!!» «ッッッ» «…»
        return true
    }

    private fun normalize(raw: String) = raw.replace(Regex("\\s+"), " ").trim()

    private fun cacheKey(text: String, src: String, tgt: String): String {
        val digest = MessageDigest.getInstance("MD5").digest("$src|$tgt|$text".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** ترجمه یک متن. null یعنی لازم نبود یا ناموفق بود. */
    suspend fun translate(raw: String, sourceLang: String, targetLang: String = Lang.PERSIAN): String? {
        val result = translateRaw(raw, sourceLang, targetLang) ?: return null
        return if (colloquial && targetLang == Lang.PERSIAN) PersianColloquial.convert(result) else result
    }

    private suspend fun translateRaw(raw: String, sourceLang: String, targetLang: String): String? {
        if (!shouldTranslate(raw)) return null
        val text = normalize(raw)
        val key = cacheKey(text, sourceLang, targetLang)

        memCache.get(key)?.let { return it }
        diskCache.getString(key, null)?.let { memCache.put(key, it); return it }

        if (System.currentTimeMillis() < cooldownUntil) return null
        if (!NetworkUtils.isOnline(context)) {
            AppLogger.error(context, TAG, "No network connection")
            return null
        }

        val parts = chunk(text)
        val translatedParts = ArrayList<String>(parts.size)
        for (part in parts) {
            val tr = limiter.withPermit { fetch(part, sourceLang, targetLang) }
            if (tr == null) {
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                AppLogger.error(context, TAG, "MyMemory failed for '$part'")
                return null
            }
            translatedParts += tr
        }
        val result = translatedParts.joinToString(" ")
        memCache.put(key, result)
        diskCache.edit().putString(key, result).apply()
        return result
    }

    /**
     * ترجمه موازی همه متن‌ها (مورد ۱) با خروجی تدریجی (مورد ۳).
     * هر عنصر خروجی: (اندیس متن در لیست ورودی، ترجمه).
     * متن‌های تکراری فقط یک‌بار ترجمه می‌شوند.
     */
    fun translateAll(
        texts: List<String>,
        sourceLang: String,
        targetLang: String = Lang.PERSIAN
    ): Flow<Pair<Int, String>> = channelFlow {
        val groups = texts.withIndex()
            .filter { shouldTranslate(it.value) }
            .groupBy { normalize(it.value) }

        for ((text, items) in groups) {
            launch(Dispatchers.IO) {
                val tr = try {
                    translate(text, sourceLang, targetLang)
                } catch (e: Exception) {
                    AppLogger.error(context, TAG, "translate() threw for '$text'", e)
                    null
                }
                if (tr != null) items.forEach { send(it.index to tr) }
            }
        }
    }

    private suspend fun fetch(q: String, src: String, tgt: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.mymemory.translated.net/get".toHttpUrl().newBuilder()
                    .addQueryParameter("q", q)
                    .addQueryParameter("langpair", "${Lang.forMyMemory(src)}|${Lang.forMyMemory(tgt)}")
                    .apply { if (contactEmail.isNotBlank()) addQueryParameter("de", contactEmail) }
                    .build()
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    if (json.optInt("responseStatus", 200) != 200) return@use null
                    val tr = json.getJSONObject("responseData").getString("translatedText")
                    // وقتی سهمیه تمام شود MyMemory به‌جای خطا یک متن هشدار برمی‌گرداند
                    if (tr.contains("MYMEMORY WARNING", ignoreCase = true)) null else tr
                }
            } catch (e: Exception) {
                null
            }
        }

    /** متن‌های بلند را سر جمله/فاصله می‌شکند (سقف MyMemory حدود ۵۰۰ بایت است). */
    private fun chunk(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > MAX_CHARS) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？'), MAX_CHARS)
            if (cut < 50) cut = rest.lastIndexOf(' ', MAX_CHARS)
            if (cut < 50) cut = MAX_CHARS
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun close() {
        memCache.evictAll()
    }

    companion object {
        private const val TAG = "TranslationManager"
        private const val MAX_CHARS = 450
        private const val MAX_PARALLEL = 6
        private const val COOLDOWN_MS = 15_000L

        // مورد ۵: یک کلاینت مشترک برای کل برنامه (Keep-Alive + HTTP/2)
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
