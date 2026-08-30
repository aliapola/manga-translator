package com.mangatranslator.app.translation

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mangatranslator.app.data.DictionaryDao
import com.mangatranslator.app.data.DictionaryEntity
import com.mangatranslator.app.data.OnlineProvider
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.NetworkUtils
import kotlinx.coroutines.tasks.await

enum class TranslationSource { CUSTOM_DICTIONARY, OFFLINE_MODEL, ONLINE, MIXED, CACHE, NONE }

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val source: TranslationSource
)

/**
 * Core translation pipeline. Rule (as specified by the app's design):
 * 1. Cache: if this exact text was translated before in this session,
 *    reuse the result instantly.
 * 2. Exact match of the whole recognized text in the user's custom
 *    dictionary.
 * 3. Substring matches: any dictionary entry appearing anywhere inside
 *    the recognized text (longest entries first).
 * 4. Fuzzy match: for short fragments that are almost a dictionary entry.
 * 5. Whatever text is left over goes through [translateFragment]: the
 *    optional online Microsoft Azure Translator tier (only if the user
 *    enabled it, configured an API key, AND the device is currently
 *    online), falling back to the on-device ML Kit model otherwise.
 * 6. If neither is available, NONE.
 */
class TranslationManager(
    private val context: Context,
    private val dictionaryDao: DictionaryDao,
    private val languagePackManager: LanguagePackManager
) {
    private val translators = mutableMapOf<String, Translator>()
    private var translatorsLastUsed = System.currentTimeMillis()
    private val azureClient = AzureTranslatorClient()
    private val myMemoryClient = MyMemoryTranslatorClient()
    private val googleFreeClient = GoogleFreeTranslatorClient()

    // Online mode config - NONE by default; OverlayService pushes the current
    // Settings values in here via updateOnlineConfig() before each translate.
    private var onlineProvider = OnlineProvider.NONE
    private var azureApiKey = ""
    private var azureRegion = ""
    private var myMemoryEmail = ""

    // If the online provider fails once (unreachable, blocked, timed out),
    // skip it for a short cooldown instead of paying a full timeout again
    // for every remaining text block on the page.
    private var onlineCooldownUntil = 0L
    private val ONLINE_COOLDOWN_MS = 20_000L

    fun updateOnlineConfig(provider: OnlineProvider, azureApiKey: String, azureRegion: String, myMemoryEmail: String) {
        this.onlineProvider = provider
        this.azureApiKey = azureApiKey
        this.azureRegion = azureRegion
        this.myMemoryEmail = myMemoryEmail
    }

    // Simple in-memory session cache: (sourceLang, targetLang, text) -> result.
    private val cache = LinkedHashMap<String, TranslationResult>(0, 0.75f, true)
    private val CACHE_MAX_ENTRIES = 500

    private fun translatorFor(sourceLang: String, targetLang: String): Translator {
        translatorsLastUsed = System.currentTimeMillis()
        return translators.getOrPut("$sourceLang-$targetLang") {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String = TranslateLanguage.PERSIAN
    ): TranslationResult {
        if (text.isBlank()) return TranslationResult(text, "", TranslationSource.NONE)

        val cacheKey = "$sourceLang|$targetLang|$text"
        cache[cacheKey]?.let { return it.copy(source = TranslationSource.CACHE) }

        val result = translateUncached(text, sourceLang, targetLang)
        if (result.translatedText.isNotBlank()) {
            putInCache(cacheKey, result)
        }
        return result
    }

    private fun putInCache(key: String, result: TranslationResult) {
        cache[key] = result
        if (cache.size > CACHE_MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
    }

    private suspend fun translateUncached(
        text: String,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        val exactMatch = findExactMatch(text, sourceLang, targetLang)
        if (exactMatch != null) {
            return TranslationResult(text, exactMatch.translatedText, TranslationSource.CUSTOM_DICTIONARY)
        }

        val entries = dictionaryDao.getAllOnce()
            .filter { it.sourceLang == sourceLang && it.targetLang == targetLang && it.sourceText.isNotBlank() }

        if (text.length <= 12) {
            val fuzzy = entries
                .filter { kotlin.math.abs(it.sourceText.length - text.length) <= 1 }
                .minByOrNull { levenshtein(normalize(it.sourceText, it.caseSensitive), normalize(text, it.caseSensitive)) }
            if (fuzzy != null) {
                val distance = levenshtein(normalize(fuzzy.sourceText, fuzzy.caseSensitive), normalize(text, fuzzy.caseSensitive))
                if (distance in 1..1) {
                    return TranslationResult(text, fuzzy.translatedText, TranslationSource.CUSTOM_DICTIONARY)
                }
            }
        }

        val matches = entries.filter { entry -> textContains(text, entry) }
            .sortedByDescending { it.sourceText.length }

        if (matches.isEmpty()) {
            return machineTranslate(text, sourceLang, targetLang)
        }

        val segments = splitIntoSegments(text, matches)
        val builder = StringBuilder()
        var usedDictionary = false
        var usedMachine = false
        var usedOnline = false

        for (segment in segments) {
            when (segment) {
                is Segment.Dict -> {
                    builder.append(segment.translated)
                    usedDictionary = true
                }
                is Segment.Raw -> {
                    val trimmed = segment.text.trim()
                    if (trimmed.isEmpty()) {
                        builder.append(segment.text)
                        continue
                    }
                    val fragment = translateFragment(segment.text, sourceLang, targetLang)
                    if (fragment != null) {
                        builder.append(fragment.first)
                        if (fragment.second == TranslationSource.ONLINE) usedOnline = true else usedMachine = true
                    }
                }
            }
        }

        val source = when {
            usedDictionary && (usedMachine || usedOnline) -> TranslationSource.MIXED
            usedOnline -> TranslationSource.ONLINE
            usedDictionary -> TranslationSource.CUSTOM_DICTIONARY
            usedMachine -> TranslationSource.OFFLINE_MODEL
            else -> TranslationSource.NONE
        }
        return TranslationResult(text, builder.toString(), source)
    }

    private sealed class Segment {
        data class Dict(val translated: String) : Segment()
        data class Raw(val text: String) : Segment()
    }

    private fun splitIntoSegments(text: String, matches: List<DictionaryEntity>): List<Segment> {
        val segments = mutableListOf<Segment>()
        val raw = StringBuilder()
        var i = 0
        while (i < text.length) {
            val hit = matches.firstOrNull { entry ->
                val len = entry.sourceText.length
                if (len == 0 || i + len > text.length) {
                    false
                } else {
                    val slice = text.substring(i, i + len)
                    if (entry.caseSensitive) slice == entry.sourceText else slice.equals(entry.sourceText, ignoreCase = true)
                }
            }
            if (hit != null) {
                if (raw.isNotEmpty()) {
                    segments += Segment.Raw(raw.toString())
                    raw.clear()
                }
                segments += Segment.Dict(hit.translatedText)
                i += hit.sourceText.length
            } else {
                raw.append(text[i])
                i++
            }
        }
        if (raw.isNotEmpty()) segments += Segment.Raw(raw.toString())
        return segments
    }

    private fun textContains(text: String, entry: DictionaryEntity): Boolean =
        if (entry.caseSensitive) text.contains(entry.sourceText) else text.contains(entry.sourceText, ignoreCase = true)

    private fun normalize(text: String, caseSensitive: Boolean): String =
        if (caseSensitive) text else text.lowercase()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private suspend fun machineTranslate(text: String, sourceLang: String, targetLang: String): TranslationResult {
        val fragment = translateFragment(text, sourceLang, targetLang)
        return if (fragment != null) {
            TranslationResult(text, fragment.first, fragment.second)
        } else {
            TranslationResult(text, "", TranslationSource.NONE)
        }
    }

    /**
     * Translates a single fragment: tries the configured online provider
     * first (only if the device is actually online right now), then falls
     * back to the offline ML Kit model. Returns null if neither path
     * produced a result.
     */
    private suspend fun translateFragment(
        text: String,
        sourceLang: String,
        targetLang: String
    ): Pair<String, TranslationSource>? {
        val onlineIsWorthTrying = onlineProvider != OnlineProvider.NONE &&
            System.currentTimeMillis() >= onlineCooldownUntil

        if (onlineIsWorthTrying && NetworkUtils.isOnline(context)) {
            val online = when (onlineProvider) {
                OnlineProvider.MYMEMORY -> myMemoryClient.translate(text, sourceLang, targetLang, myMemoryEmail)
                OnlineProvider.GOOGLE_FREE -> googleFreeClient.translate(text, sourceLang, targetLang)
                OnlineProvider.AZURE -> if (azureApiKey.isNotBlank()) {
                    azureClient.translate(text, sourceLang, targetLang, azureApiKey, azureRegion)
                } else null
                OnlineProvider.NONE -> null
            }
            if (!online.isNullOrBlank()) {
                AppLogger.log(context, TAG, "Online ($onlineProvider) succeeded for '$text'")
                return online to TranslationSource.ONLINE
            }
            // Failed (bad key, blocked/unreachable endpoint, rate limit,
            // timeout...). Start a cooldown so the REST of this page's
            // blocks (and the next tap for a while) skip straight to the
            // offline model instead of each waiting out a full timeout.
            onlineCooldownUntil = System.currentTimeMillis() + ONLINE_COOLDOWN_MS
            AppLogger.error(context, TAG, "Online ($onlineProvider) failed for '$text' - cooling down ${ONLINE_COOLDOWN_MS}ms, falling back to offline")
        }

        val ready = languagePackManager.isPackReady(sourceLang) && languagePackManager.isPackReady(targetLang)
        if (!ready) return null

        return try {
            val translated = translatorFor(sourceLang, targetLang).translate(text).await()
            translated to TranslationSource.OFFLINE_MODEL
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun findExactMatch(
        text: String,
        sourceLang: String,
        targetLang: String
    ): DictionaryEntity? {
        dictionaryDao.findExact(sourceLang, targetLang, text)?.let { return it }
        val all = dictionaryDao.getAllOnce()
        return all.firstOrNull {
            it.sourceLang == sourceLang && it.targetLang == targetLang &&
                !it.caseSensitive && it.sourceText.equals(text, ignoreCase = true)
        }
    }

    fun releaseIfIdle(idleMillis: Long) {
        if (translators.isNotEmpty() && System.currentTimeMillis() - translatorsLastUsed > idleMillis) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        cache.clear()
    }

    companion object {
        private const val TAG = "TranslationManager"
    }
}
