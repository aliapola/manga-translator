package com.mangatranslator.app.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangatranslator.app.data.DictionaryEntity
import com.mangatranslator.app.viewmodel.DictionaryViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit, viewModel: DictionaryViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsState()
    val query by viewModel.query.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    val context = LocalContext.current

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DictionaryEntity?>(null) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportJson(it) } }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.exportCsv(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            var name = "dictionary.json"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
            viewModel.importFile(it, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("دیکشنری سفارشی") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/csv", "text/*")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "وارد کردن فایل")
                    }
                    IconButton(onClick = { exportJsonLauncher.launch("dictionary_export.json") }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "خروجی JSON")
                    }
                    IconButton(onClick = { exportCsvLauncher.launch("dictionary_export.csv") }) {
                        Icon(Icons.Filled.TableChart, contentDescription = "خروجی CSV")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showEditor = true
            }) { Icon(Icons.Filled.Add, null) }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("جستجو در دیکشنری") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (categories.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { viewModel.setCategoryFilter(null) },
                        label = { Text("همه") }
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = categoryFilter == category,
                            onClick = { viewModel.setCategoryFilter(category) },
                            label = { Text(category) }
                        )
                    }
                }
            }

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("هنوز جفت ترجمه‌ای اضافه نکرده‌اید.")
                }
            }

            LazyColumn {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.sourceText) },
                        supportingContent = {
                            val categoryTag = if (entry.category.isNotBlank()) "  •  ${entry.category}" else ""
                            Text("${entry.translatedText}  •  ${entry.sourceLang}→${entry.targetLang}$categoryTag")
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    editing = entry
                                    showEditor = true
                                }) { Icon(Icons.Filled.Edit, null) }
                                IconButton(onClick = { viewModel.delete(entry) }) {
                                    Icon(Icons.Filled.Delete, null)
                                }
                            }
                        }
                    )
                    Divider()
                }
            }
        }
    }

    if (showEditor) {
        EntryEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = {
                viewModel.addOrUpdate(it)
                showEditor = false
            }
        )
    }
}

@Composable
private fun EntryEditorDialog(
    initial: DictionaryEntity?,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntity) -> Unit
) {
    var source by remember { mutableStateOf(initial?.sourceText ?: "") }
    var translation by remember { mutableStateOf(initial?.translatedText ?: "") }
    var sourceLang by remember { mutableStateOf(initial?.sourceLang ?: "ja") }
    var targetLang by remember { mutableStateOf(initial?.targetLang ?: "fa") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (initial == null) "افزودن ترجمه جدید" else "ویرایش ترجمه",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(source, { source = it }, label = { Text("متن اصلی") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(translation, { translation = it }, label = { Text("ترجمه فارسی") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        sourceLang, { sourceLang = it },
                        label = { Text("کد زبان مبدأ") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        targetLang, { targetLang = it },
                        label = { Text("کد زبان مقصد") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    category, { category = it },
                    label = { Text("دسته‌بندی (مثلاً نام مانگا - اختیاری)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("یادداشت (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("انصراف") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                DictionaryEntity(
                                    id = initial?.id ?: 0,
                                    sourceText = source,
                                    translatedText = translation,
                                    sourceLang = sourceLang,
                                    targetLang = targetLang,
                                    note = note,
                                    category = category.trim()
                                )
                            )
                        },
                        enabled = source.isNotBlank() && translation.isNotBlank()
                    ) { Text("ذخیره") }
                }
            }
        }
    }
}
