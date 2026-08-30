package com.mangatranslator.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mangatranslator.app.data.SettingsStore
import com.mangatranslator.app.service.OverlayService
import com.mangatranslator.app.service.ScreenCaptureService
import com.mangatranslator.app.translation.LanguagePackManager
import com.mangatranslator.app.ui.screens.DictionaryScreen
import com.mangatranslator.app.ui.screens.HomeScreen
import com.mangatranslator.app.ui.screens.LanguagePackScreen
import com.mangatranslator.app.ui.screens.SettingsScreen
import com.mangatranslator.app.ui.theme.MangaTranslatorTheme
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.CrashHandler
import com.mangatranslator.app.util.NetworkUtils
import com.mangatranslator.app.util.Permissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val isSessionActive = mutableStateOf(false)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: services still work, just without a visible heads-up */ }

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, captureIntent)
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            isSessionActive.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Permissions.needsNotificationPermission()) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        autoDownloadLanguagePacksIfNeeded()

        setContent {
            MangaTranslatorTheme {
                var crashText by remember { mutableStateOf(CrashHandler.readLastCrash(this)) }

                if (crashText != null) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("گزارش خطای قبلی") },
                        text = {
                            SelectionContainer {
                                Text(
                                    crashText ?: "",
                                    modifier = androidx.compose.ui.Modifier.padding(4.dp)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("crash log", crashText)
                                )
                                CrashHandler.clearLastCrash(this)
                                crashText = null
                            }) { Text("کپی و بستن") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                CrashHandler.clearLastCrash(this)
                                crashText = null
                            }) { Text("فقط بستن") }
                        }
                    )
                }

                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            sessionActive = isSessionActive.value,
                            onStartSession = ::startTranslationSession,
                            onStopSession = ::stopTranslationSession,
                            onOpenDictionary = { navController.navigate("dictionary") },
                            onOpenLanguagePacks = { navController.navigate("packs") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("dictionary") { DictionaryScreen(onBack = { navController.popBackStack() }) }
                    composable("packs") { LanguagePackScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }
    }

    /**
     * Downloads the currently-selected source language pack + Persian in the
     * background on launch, if connected and not already downloaded. This
     * means most users never have to open "Language Packs" manually - the
     * offline model is just ready by the time they need it. Silent: no
     * toast/dialog, since it's a one-time convenience, not something that
     * should interrupt the user; if it fails (no connectivity yet), the app
     * still works exactly as before - the user can always download manually.
     */
    private fun autoDownloadLanguagePacksIfNeeded() {
        lifecycleScope.launch {
            try {
                if (!NetworkUtils.isOnline(this@MainActivity)) return@launch
                val manager = LanguagePackManager()
                val sourceLang = SettingsStore.sourceLangFlow(this@MainActivity).first()
                val targetLang = manager.targetLanguage

                if (!manager.isPackReady(sourceLang)) {
                    AppLogger.log(this@MainActivity, "MainActivity", "Auto-downloading source language pack: $sourceLang")
                    manager.downloadPack(sourceLang, requireWifi = false)
                }
                if (!manager.isPackReady(targetLang)) {
                    AppLogger.log(this@MainActivity, "MainActivity", "Auto-downloading target language pack: $targetLang")
                    manager.downloadPack(targetLang, requireWifi = false)
                }
            } catch (e: Exception) {
                AppLogger.error(this@MainActivity, "MainActivity", "Auto-download of language packs failed", e)
                // Non-fatal: the user can still download manually from "Language Packs".
            }
        }
    }

    /** Kicks off the overlay-permission + MediaProjection permission flow. */
    private fun startTranslationSession() {
        if (!Permissions.canDrawOverlays(this)) {
            startActivity(Permissions.overlaySettingsIntent(this))
            return
        }
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestProjection.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopTranslationSession() {
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        isSessionActive.value = false
    }
}
