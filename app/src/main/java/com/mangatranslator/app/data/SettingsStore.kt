package com.mangatranslator.app.data

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mangatranslator.app.translation.Lang
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "manga_translator_settings")

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
    private val KEY_MYMEMORY_EMAIL = stringPreferencesKey("mymemory_contact_email")

    const val DEFAULT_BUTTON_SIZE_DP = 56
    const val MIN_BUTTON_SIZE_DP = 36
    const val MAX_BUTTON_SIZE_DP = 96

    const val DEFAULT_OVERLAY_OPACITY = 0.92f

    fun sourceLangFlow(context: Context): Flow<String> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[KEY_SOURCE_LANG] ?: Lang.ENGLISH
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
