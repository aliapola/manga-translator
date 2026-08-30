package com.mangatranslator.app.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the MyMemory Translation API (mymemory.translated.net).
 * Free, and unlike Azure/Google/DeepL it needs NO signup, NO API key, and
 * NO credit card at all. Anonymous requests get 5,000 words/day per IP;
 * adding a contact email (optional, just a courtesy identifier - not an
 * account) raises that to 50,000 words/day.
 */
class MyMemoryTranslatorClient {

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        contactEmail: String = ""
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            var urlStr = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$sourceLang|$targetLang"
            if (contactEmail.isNotBlank()) {
                urlStr += "&de=${URLEncoder.encode(contactEmail, "UTF-8")}"
            }
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (connection.responseCode !in 200..299) return@withContext null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(responseText)
            if (root.optInt("responseStatus", 200) != 200) return@withContext null

            val translated = root.getJSONObject("responseData").getString("translatedText")
            // MyMemory returns a "MYMEMORY WARNING..." string instead of an
            // error code when the daily quota is exceeded - treat it as a
            // failure so the caller falls back to the offline model.
            if (translated.contains("MYMEMORY WARNING", ignoreCase = true)) return@withContext null
            translated
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
