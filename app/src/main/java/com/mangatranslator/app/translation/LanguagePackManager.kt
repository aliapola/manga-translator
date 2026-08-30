package com.mangatranslator.app.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

data class LanguagePackInfo(
    val code: String,
    val displayName: String,
    val isDownloaded: Boolean
)

/**
 * Wraps ML Kit's RemoteModelManager to download/delete on-device translation
 * models. Once a pack is downloaded, translation for that language runs
 * fully offline - no network calls happen at translate time.
 *
 * Persian ("fa") is always used as the app's target language pack.
 */
class LanguagePackManager {

    private val modelManager = RemoteModelManager.getInstance()

    /** Languages relevant to manga/manhwa/manhua + Persian as the pivot target. */
    val supportedSourceLanguages = listOf(
        TranslateLanguage.JAPANESE to "ژاپنی",
        TranslateLanguage.KOREAN to "کره‌ای",
        TranslateLanguage.CHINESE to "چینی",
        TranslateLanguage.ENGLISH to "انگلیسی"
    )

    val targetLanguage = TranslateLanguage.PERSIAN

    suspend fun listPacks(): List<LanguagePackInfo> {
        val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        val downloadedCodes = downloaded.map { it.language }.toSet()

        val all = mutableListOf<LanguagePackInfo>()
        // Target language pack (Persian) itself must be downloaded too.
        all += LanguagePackInfo(targetLanguage, "فارسی (مقصد)", targetLanguage in downloadedCodes)
        supportedSourceLanguages.forEach { (code, name) ->
            all += LanguagePackInfo(code, name, code in downloadedCodes)
        }
        return all
    }

    /**
     * Downloads a language model. Pass [requireWifi] = true to respect
     * metered-data users; the app defaults to allowing downloads only
     * on Wi-Fi unless the user explicitly opts into cellular in settings.
     */
    suspend fun downloadPack(languageCode: String, requireWifi: Boolean = true) {
        val model = TranslateRemoteModel.Builder(languageCode).build()
        val conditions = DownloadConditions.Builder()
            .apply { if (requireWifi) requireWifi() }
            .build()
        modelManager.download(model, conditions).await()
    }

    suspend fun deletePack(languageCode: String) {
        val model = TranslateRemoteModel.Builder(languageCode).build()
        modelManager.deleteDownloadedModel(model).await()
    }

    suspend fun isPackReady(languageCode: String): Boolean {
        val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        return downloaded.any { it.language == languageCode }
    }
}
