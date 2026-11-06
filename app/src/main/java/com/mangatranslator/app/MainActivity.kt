package com.mangatranslator.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mangatranslator.app.service.OverlayService
import com.mangatranslator.app.service.ScreenCaptureService
import com.mangatranslator.app.ui.screens.HomeScreen
import com.mangatranslator.app.ui.screens.SettingsScreen
import com.mangatranslator.app.ui.screens.WebReaderScreen
import com.mangatranslator.app.ui.theme.MangaTranslatorTheme
import com.mangatranslator.app.util.CrashHandler
import com.mangatranslator.app.util.Permissions

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
                            onOpenWebReader = { navController.navigate("web") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("web") { WebReaderScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { navController.popBackStack() }) }
                }
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
