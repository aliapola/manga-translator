package com.mangatranslator.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "manga_translator_settings")

/** Which online translation service (if any) to try before the offline model. */
enum class OnlineProvider { NONE, MYMEMORY, GOOGLE_FREE, GEMINI }

/**
 * Persists user-configurable settings so OverlayService (a Service, with no
 * direct link to the Settings screen) always reads the current choice
 * instead of hardcoded defaults.
 */
object SettingsStore {
    private val KEY_SOURCE_LANG = stringPreferencesKey("source_lang_code")
    private val KEY_BUTTON_SIZE_DP = intPreferencesKey("button_size_dp")
    private val KEY_OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
    private val KEY_AUTO_TRANSLATE = booleanPreferencesKey("auto_translate_on_page_change")
    private val KEY_ONLINE_PROVIDER = stringPreferencesKey("online_translation_provider")
    private val KEY_MYMEMORY_EMAIL = stringPreferencesKey("mymemory_contact_email")
    private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")

    // Keys written by older versions that supported Microsoft Azure Translator.
    // They are no longer used; purgeLegacyKeys() wipes them (the API key in particular).
    private val LEGACY_AZURE_API_KEY = stringPreferencesKey("azure_translator_api_key")
    private val LEGACY_AZURE_REGION = stringPreferencesKey("azure_translator_region")

    const val DEFAULT_BUTTON_SIZE_DP = 56
    const val MIN_BUTTON_SIZE_DP = 36
    const val MAX_BUTTON_SIZE_DP = 96

    const val DEFAULT_OVERLAY_OPACITY = 0.92f

    /** Editable in Settings, because Google retires/renames model names over time. */
    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

    /** Default provider: Google (fast, no key). Users who had "azure" saved fall back to this too. */
    val DEFAULT_ONLINE_PROVIDER = OnlineProvider.GOOGLE_FREE

    /** Everything OverlayService needs per translation, read with ONE DataStore access. */
    data class Snapshot(
        val sourceLang: String,
        val buttonSizeDp: Int,
        val overlayOpacity: Float,
        val onlineProvider: OnlineProvider,
        val myMemoryEmail: String,
        val geminiApiKey: String,
        val geminiModel: String
    )

    suspend fun snapshot(context: Context): Snapshot {
        val prefs = context.applicationContext.dataStore.data.first()
        return Snapshot(
            sourceLang = prefs[KEY_SOURCE_LANG] ?: TranslateLanguage.ENGLISH,
            buttonSizeDp = prefs[KEY_BUTTON_SIZE_DP] ?: DEFAULT_BUTTON_SIZE_DP,
            overlayOpacity = prefs[KEY_OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY,
            onlineProvider = providerFrom(prefs),
            myMemoryEmail = prefs[KEY_MYMEMORY_EMAIL] ?: "",
            geminiApiKey = prefs[KEY_GEMINI_API_KEY] ?: "",
            geminiModel = prefs[KEY_GEMINI_MODEL] ?: DEFAULT_GEMINI_MODEL
        )
    }

    fun sourceLangFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_SOURCE_LANG] ?: TranslateLanguage.ENGLISH
        }

    suspend fun setSourceLang(context: Context, languageCode: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_SOURCE_LANG] = languageCode
        }
    }

    fun buttonSizeDpFlow(context: Context): Flow<Int> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_BUTTON_SIZE_DP] ?: DEFAULT_BUTTON_SIZE_DP
        }

    suspend fun setButtonSizeDp(context: Context, sizeDp: Int) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_BUTTON_SIZE_DP] = sizeDp.coerceIn(MIN_BUTTON_SIZE_DP, MAX_BUTTON_SIZE_DP)
        }
    }

    fun overlayOpacityFlow(context: Context): Flow<Float> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY
        }

    suspend fun setOverlayOpacity(context: Context, opacity: Float) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_OPACITY] = opacity.coerceIn(0.3f, 1f)
        }
    }

    fun autoTranslateFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_AUTO_TRANSLATE] ?: false
        }

    suspend fun setAutoTranslate(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_AUTO_TRANSLATE] = enabled
        }
    }

    private fun providerFrom(prefs: Preferences): OnlineProvider =
        when (prefs[KEY_ONLINE_PROVIDER]) {
            "none" -> OnlineProvider.NONE
            "mymemory" -> OnlineProvider.MYMEMORY
            "google_free" -> OnlineProvider.GOOGLE_FREE
            "gemini" -> OnlineProvider.GEMINI
            else -> DEFAULT_ONLINE_PROVIDER
        }

    fun onlineProviderFlow(context: Context): Flow<OnlineProvider> =
        context.applicationContext.dataStore.data.map { prefs -> providerFrom(prefs) }

    suspend fun setOnlineProvider(context: Context, provider: OnlineProvider) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_ONLINE_PROVIDER] = when (provider) {
                OnlineProvider.MYMEMORY -> "mymemory"
                OnlineProvider.GOOGLE_FREE -> "google_free"
                OnlineProvider.GEMINI -> "gemini"
                OnlineProvider.NONE -> "none"
            }
        }
    }

    /** Optional contact email for MyMemory - raises the free daily quota from 5,000 to 50,000 words. Not an account. */
    fun myMemoryEmailFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_MYMEMORY_EMAIL] ?: ""
        }

    suspend fun setMyMemoryEmail(context: Context, email: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_MYMEMORY_EMAIL] = email.trim()
        }
    }

    fun geminiApiKeyFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs -> prefs[KEY_GEMINI_API_KEY] ?: "" }

    fun geminiModelFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs -> prefs[KEY_GEMINI_MODEL] ?: DEFAULT_GEMINI_MODEL }

    suspend fun setGemini(context: Context, apiKey: String, model: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_GEMINI_API_KEY] = apiKey.trim()
            prefs[KEY_GEMINI_MODEL] = model.trim().ifBlank { DEFAULT_GEMINI_MODEL }
        }
    }

    /** Removes settings left behind by removed features (Azure key/region). Safe to call on every launch. */
    suspend fun purgeLegacyKeys(context: Context) {
        context.applicationContext.dataStore.edit { prefs ->
            if (prefs.contains(LEGACY_AZURE_API_KEY)) prefs.remove(LEGACY_AZURE_API_KEY)
            if (prefs.contains(LEGACY_AZURE_REGION)) prefs.remove(LEGACY_AZURE_REGION)
        }
    }
}
