package com.mangatranslator.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangatranslator.app.translation.LanguagePackInfo
import com.mangatranslator.app.translation.LanguagePackManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePackScreen(onBack: () -> Unit) {
    val manager = remember { LanguagePackManager() }
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<LanguagePackInfo>>(emptyList()) }
    var busyCode by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    suspend fun refresh() { packs = manager.listPacks() }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("بسته‌های زبان آفلاین") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "برای ترجمه کاملاً آفلاین، ابتدا زبان مبدأ (مثلاً ژاپنی) و زبان مقصد (فارسی) را دانلود کنید. دانلود فقط یک‌بار لازم است.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            LazyColumn {
                items(packs) { pack ->
                    ListItem(
                        headlineContent = { Text(pack.displayName) },
                        supportingContent = { Text(pack.code) },
                        trailingContent = {
                            when {
                                busyCode == pack.code -> CircularProgressIndicator(Modifier.size(24.dp))
                                pack.isDownloaded -> Row {
                                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    IconButton(onClick = {
                                        scope.launch {
                                            busyCode = pack.code
                                            manager.deletePack(pack.code)
                                            refresh()
                                            busyCode = null
                                        }
                                    }) { Icon(Icons.Filled.Delete, null) }
                                }
                                else -> IconButton(onClick = {
                                    scope.launch {
                                        busyCode = pack.code
                                        try {
                                            manager.downloadPack(pack.code, requireWifi = false)
                                        } catch (_: Exception) { }
                                        refresh()
                                        busyCode = null
                                    }
                                }) { Icon(Icons.Filled.Download, null) }
                            }
                        }
                    )
                    Divider()
                }
            }
        }
    }
}
