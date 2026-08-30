package com.mangatranslator.app.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the Microsoft Azure Translator "Text Translation" REST API
 * (v3.0). Free tier (F0) gives 2,000,000 characters/month. The user creates
 * their own resource in the Azure Portal and pastes the key + region into
 * Settings - this app never ships or proxies a shared key.
 *
 * Docs: https://learn.microsoft.com/azure/ai-services/translator/reference/v3-0-translate
 */
class AzureTranslatorClient {

    /** Maps this app's internal ML Kit-style language codes to Azure's codes (mostly identical; Chinese differs). */
    fun toAzureLanguageCode(code: String): String = when (code) {
        "zh" -> "zh-Hans"
        else -> code
    }

    /** Returns the translated text, or null if the request failed for any reason (caller falls back to offline). */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        region: String
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val from = toAzureLanguageCode(sourceLang)
            val to = toAzureLanguageCode(targetLang)
            val url = URL("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=$from&to=$to")

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Ocp-Apim-Subscription-Key", apiKey)
                if (region.isNotBlank()) {
                    setRequestProperty("Ocp-Apim-Subscription-Region", region)
                }
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val requestBody = JSONArray().put(JSONObject().put("Text", text)).toString()
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext null

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(responseText)
            val translations = root.getJSONObject(0).getJSONArray("translations")
            translations.getJSONObject(0).getString("text")
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
