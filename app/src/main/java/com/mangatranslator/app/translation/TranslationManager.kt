package com.mangatranslator.app.translation

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mangatranslator.app.data.OnlineProvider
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

enum class TranslationSource { OFFLINE_MODEL, ONLINE, CACHE, NONE }

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val source: TranslationSource
)

/**
 * Core translation pipeline:
 * 1. Session cache: the exact same text is never translated twice.
 * 2. Optional online provider (MyMemory / Google) - only if enabled, not in
 *    a failure cooldown, and the device is actually online.
 * 3. On-device ML Kit model as the fallback / offline path.
 *
 * Safe to call from many coroutines at once (OverlayService translates all
 * text blocks of a page in parallel).
 */
class TranslationManager(
    private val context: Context,
    private val languagePackManager: LanguagePackManager
) {
    private val translators = ConcurrentHashMap<String, Translator>()
    @Volatile private var translatorsLastUsed = System.currentTimeMillis()
    private val myMemoryClient = MyMemoryTranslatorClient()
    private val googleFreeClient = GoogleFreeTranslatorClient()

    // Pushed in by OverlayService before each translation pass.
    @Volatile private var onlineProvider = OnlineProvider.NONE
    @Volatile private var myMemoryEmail = ""

    // If the online provider fails once (unreachable, blocked, timed out),
    // skip it for a short cooldown instead of paying a full timeout again
    // for every remaining text block on the page.
    @Volatile private var onlineCooldownUntil = 0L

    // "src>tgt" pairs whose language packs were already verified as downloaded.
    // Asking ML Kit for the downloaded-model list is comparatively slow, and it
    // used to be done twice for EVERY text block.
    private val readyPairs = ConcurrentHashMap.newKeySet<String>()
    private val readyMutex = Mutex()

    // Simple in-memory session cache: (sourceLang, targetLang, text) -> result (LRU).
    private val cache = object : LinkedHashMap<String, TranslationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }

    fun updateOnlineConfig(provider: OnlineProvider, myMemoryEmail: String) {
        this.onlineProvider = provider
        this.myMemoryEmail = myMemoryEmail
    }

    private fun pairKey(sourceLang: String, targetLang: String) = "$sourceLang>$targetLang"

    private fun translatorFor(sourceLang: String, targetLang: String): Translator {
        translatorsLastUsed = System.currentTimeMillis()
        return translators.computeIfAbsent(pairKey(sourceLang, targetLang)) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    private suspend fun packsReady(sourceLang: String, targetLang: String): Boolean {
        val key = pairKey(sourceLang, targetLang)
        if (key in readyPairs) return true
        return readyMutex.withLock {
            if (key in readyPairs) return@withLock true
            val ready = languagePackManager.areReady(listOf(sourceLang, targetLang))
            if (ready) readyPairs.add(key)
            ready
        }
    }

    /**
     * Loads the offline model into memory ahead of the first tap. Skipped when
     * an online provider is enabled (the offline model is then only a fallback
     * and keeping it resident would just cost RAM).
     */
    suspend fun warmUp(sourceLang: String, targetLang: String = TranslateLanguage.PERSIAN) {
        if (onlineProvider != OnlineProvider.NONE) return
        try {
            if (!packsReady(sourceLang, targetLang)) return
            translatorFor(sourceLang, targetLang).translate("OK").await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(context, TAG, "warmUp skipped: ${e.message}")
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String = TranslateLanguage.PERSIAN
    ): TranslationResult {
        if (text.isBlank()) return TranslationResult(text, "", TranslationSource.NONE)

        val cacheKey = "$sourceLang|$targetLang|$text"
        synchronized(cache) { cache[cacheKey] }?.let { return it.copy(source = TranslationSource.CACHE) }

        val result = translateUncached(text, sourceLang, targetLang)
        if (result.translatedText.isNotBlank()) {
            synchronized(cache) { cache[cacheKey] = result }
        }
        return result
    }

    private suspend fun translateUncached(
        text: String,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        tryOnline(text, sourceLang, targetLang)?.let {
            return TranslationResult(text, it, TranslationSource.ONLINE)
        }

        if (!packsReady(sourceLang, targetLang)) {
            return TranslationResult(text, "", TranslationSource.NONE)
        }

        return try {
            val translated = translatorFor(sourceLang, targetLang).translate(text).await()
            TranslationResult(text, translated, TranslationSource.OFFLINE_MODEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pack may have been deleted meanwhile: re-verify next time.
            readyPairs.remove(pairKey(sourceLang, targetLang))
            AppLogger.error(context, TAG, "Offline translation failed for '$text'", e)
            TranslationResult(text, "", TranslationSource.NONE)
        }
    }

    /** Returns the online translation, or null if online is off/unavailable/failed (caller falls back to offline). */
    private suspend fun tryOnline(text: String, sourceLang: String, targetLang: String): String? {
        val provider = onlineProvider
        if (provider == OnlineProvider.NONE) return null
        if (System.currentTimeMillis() < onlineCooldownUntil) return null
        if (!NetworkUtils.isOnline(context)) return null

        val translated = when (provider) {
            OnlineProvider.MYMEMORY -> myMemoryClient.translate(text, sourceLang, targetLang, myMemoryEmail)
            OnlineProvider.GOOGLE_FREE -> googleFreeClient.translate(text, sourceLang, targetLang)
            OnlineProvider.NONE -> null
        }
        if (!translated.isNullOrBlank()) return translated

        // Failed (blocked/unreachable endpoint, rate limit, timeout...). Start a
        // cooldown so the REST of this page's blocks skip straight to the
        // offline model instead of each waiting out a full timeout.
        onlineCooldownUntil = System.currentTimeMillis() + ONLINE_COOLDOWN_MS
        AppLogger.error(context, TAG, "Online ($provider) failed - cooling down ${ONLINE_COOLDOWN_MS}ms, falling back to offline")
        return null
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
        synchronized(cache) { cache.clear() }
    }

    companion object {
        private const val TAG = "TranslationManager"
        private const val CACHE_MAX_ENTRIES = 500
        private const val ONLINE_COOLDOWN_MS = 20_000L
    }
}
