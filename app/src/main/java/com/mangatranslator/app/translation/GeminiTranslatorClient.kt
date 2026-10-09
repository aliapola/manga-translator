package com.mangatranslator.app.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the Google Gemini API (generativelanguage.googleapis.com).
 *
 * Unlike the classic translation endpoints it translates a whole page in ONE
 * request (a JSON array of speech-bubble texts in, a JSON array of the same
 * length out), which is both faster than one request per bubble and lets the
 * model keep names and tone consistent across the page. The system prompt
 * asks for natural, colloquial Persian and a faithful, uncensored rendering;
 * the safety filters are set to BLOCK_NONE (the most permissive setting the
 * API allows - Google may still block some content on its side).
 *
 * Get a free key at https://aistudio.google.com/apikey
 */
class GeminiTranslatorClient {

    private fun languageName(code: String): String = when (code) {
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "en" -> "English"
        "fa" -> "Persian (Farsi)"
        else -> code
    }

    private fun systemPrompt(sourceLang: String, targetLang: String): String =
        "You are a professional manga/manhwa/manhua translator. " +
            "The user message is a JSON array of strings: OCR'd speech bubbles and captions from ONE page, in reading order. " +
            "Translate every string from ${languageName(sourceLang)} to ${languageName(targetLang)}.\n" +
            "Rules:\n" +
            "- Use natural, colloquial, spoken Persian (محاوره‌ای), the way real people talk in everyday conversation - NOT formal or bookish language.\n" +
            "- Translate faithfully and completely. Do NOT censor, soften, euphemize, omit, summarize or moralize. Keep profanity, insults, sexual content and violence at the same intensity as the original.\n" +
            "- Do not add explanations, notes or extra text. Keep sound effects short.\n" +
            "- Keep character names and the tone consistent across the page. If a string is a fragment of a sentence split across bubbles, translate it so it still reads naturally.\n" +
            "- OCR may contain small errors; infer the intended text when obvious.\n" +
            "- Output ONLY a JSON array of strings with exactly the same number of items, in the same order."

    /**
     * Returns translations in the same order as [texts], or null if the request
     * failed, was blocked, or came back malformed (caller falls back).
     */
    suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        model: String
    ): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty() || apiKey.isBlank() || model.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }

            val generationConfig = JSONObject()
                .put("temperature", 0.3)
                .put("responseMimeType", "application/json")
                .put(
                    "responseSchema",
                    JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                )
            // Gemini 2.5 models "think" before answering by default, which adds seconds.
            // Translation doesn't need it, so turn it off where that is supported.
            if (model.contains("2.5")) {
                generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }

            val safety = JSONArray()
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { safety.put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE")) }

            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(sourceLang, targetLang))))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", JSONArray(texts).toString())))
                    )
                )
                .put("generationConfig", generationConfig)
                .put("safetySettings", safety)

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) return@withContext null
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val candidate = JSONObject(responseText).optJSONArray("candidates")?.optJSONObject(0)
                ?: return@withContext null // e.g. the prompt itself was blocked
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
            val outputText = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }.trim()
            if (outputText.isEmpty()) return@withContext null

            val array = JSONArray(outputText)
            if (array.length() != texts.size) return@withContext null
            List(array.length()) { array.optString(it, "") }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
