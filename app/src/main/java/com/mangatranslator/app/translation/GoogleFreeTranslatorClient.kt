package com.mangatranslator.app.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for Google Translate's unofficial, free "translate_a/single" web
 * endpoint - the same one translate.google.com itself uses internally, and
 * the one countless open-source translation tools rely on. No API key, no
 * signup, no credit card. It isn't an officially documented/supported API,
 * so it could in theory change or throttle a heavily-abused IP, but for
 * normal single-device personal use it's fast (Google's own infrastructure)
 * and reliable, and it applies no content filtering of its own.
 */
class GoogleFreeTranslatorClient {

    private fun toGoogleLanguageCode(code: String): String = when (code) {
        "zh" -> "zh-CN"
        else -> code
    }

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val from = toGoogleLanguageCode(sourceLang)
            val to = toGoogleLanguageCode(targetLang)
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$from&tl=$to&dt=t&q=$encodedText"

            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MangaTranslator/1.0")
            }

            if (connection.responseCode !in 200..299) return@withContext null
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Response shape: [[["translated chunk","original chunk",null,null,1], ...], null, "sl", ...]
            val root = JSONArray(responseText)
            val sentences = root.getJSONArray(0)
            val builder = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentenceArr = sentences.optJSONArray(i) ?: continue
                builder.append(sentenceArr.optString(0, ""))
            }
            val result = builder.toString()
            result.ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
