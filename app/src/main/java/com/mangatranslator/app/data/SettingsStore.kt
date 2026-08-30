package com.mangatranslator.app.data

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "manga_translator_settings")

/** Which online translation service (if any) to use for text the dictionary doesn't cover. */
enum class OnlineProvider { NONE, MYMEMORY, GOOGLE_FREE, AZURE }

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
    private val KEY_AZURE_API_KEY = stringPreferencesKey("azure_translator_api_key")
    private val KEY_AZURE_REGION = stringPreferencesKey("azure_translator_region")
    private val KEY_MYMEMORY_EMAIL = stringPreferencesKey("mymemory_contact_email")

    const val DEFAULT_BUTTON_SIZE_DP = 56
    const val MIN_BUTTON_SIZE_DP = 36
    const val MAX_BUTTON_SIZE_DP = 96

    const val DEFAULT_OVERLAY_OPACITY = 0.92f

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

    /**
     * Default online provider is MyMemory (free, no signup/key/card needed
     * at all, so it works out of the box). Users can still switch to
     * Google, Azure, or fully offline (NONE) from Settings.
     */
    fun onlineProviderFlow(context: Context): Flow<OnlineProvider> =
        context.applicationContext.dataStore.data.map { prefs ->
            when (prefs[KEY_ONLINE_PROVIDER]) {
                "none" -> OnlineProvider.NONE
                "google_free" -> OnlineProvider.GOOGLE_FREE
                "azure" -> OnlineProvider.AZURE
                else -> OnlineProvider.MYMEMORY
            }
        }

    suspend fun setOnlineProvider(context: Context, provider: OnlineProvider) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_ONLINE_PROVIDER] = when (provider) {
                OnlineProvider.MYMEMORY -> "mymemory"
                OnlineProvider.GOOGLE_FREE -> "google_free"
                OnlineProvider.AZURE -> "azure"
                OnlineProvider.NONE -> "none"
            }
        }
    }

    fun azureApiKeyFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_AZURE_API_KEY] ?: ""
        }

    suspend fun setAzureApiKey(context: Context, key: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_AZURE_API_KEY] = key.trim()
        }
    }

    /** Azure "region" for regional resources; leave blank if using a Global resource. */
    fun azureRegionFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_AZURE_REGION] ?: ""
        }

    suspend fun setAzureRegion(context: Context, region: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_AZURE_REGION] = region.trim()
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
}
