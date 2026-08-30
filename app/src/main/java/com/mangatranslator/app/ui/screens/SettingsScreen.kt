package com.mangatranslator.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.mlkit.nl.translate.TranslateLanguage
import com.mangatranslator.app.data.OnlineProvider
import com.mangatranslator.app.data.SettingsStore
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.Permissions
import kotlinx.coroutines.launch

private data class SourceLanguageOption(val code: String, val label: String)

private val sourceLanguageOptions = listOf(
    SourceLanguageOption(TranslateLanguage.JAPANESE, "ژاپنی (مانگا)"),
    SourceLanguageOption(TranslateLanguage.KOREAN, "کره‌ای (مانهوا)"),
    SourceLanguageOption(TranslateLanguage.CHINESE, "چینی (مانهوا/مانهوآ)"),
    SourceLanguageOption(TranslateLanguage.ENGLISH, "انگلیسی")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogDialog by remember { mutableStateOf(false) }

    val currentSourceLang by SettingsStore.sourceLangFlow(context)
        .collectAsState(initial = TranslateLanguage.ENGLISH)
    val autoTranslate by SettingsStore.autoTranslateFlow(context)
        .collectAsState(initial = false)
    val buttonSizeDp by SettingsStore.buttonSizeDpFlow(context)
        .collectAsState(initial = SettingsStore.DEFAULT_BUTTON_SIZE_DP)
    val overlayOpacity by SettingsStore.overlayOpacityFlow(context)
        .collectAsState(initial = SettingsStore.DEFAULT_OVERLAY_OPACITY)
    val onlineProvider by SettingsStore.onlineProviderFlow(context)
        .collectAsState(initial = OnlineProvider.MYMEMORY)
    val azureApiKeyStored by SettingsStore.azureApiKeyFlow(context)
        .collectAsState(initial = "")
    val azureRegionStored by SettingsStore.azureRegionFlow(context)
        .collectAsState(initial = "")
    val myMemoryEmailStored by SettingsStore.myMemoryEmailFlow(context)
        .collectAsState(initial = "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            Text("زبان مبدأ پیش‌فرض", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            sourceLanguageOptions.forEach { option ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = currentSourceLang == option.code,
                        onClick = {
                            scope.launch { SettingsStore.setSourceLang(context, option.code) }
                        }
                    )
                    Text(option.label)
                }
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ترجمه خودکار هنگام تغییر صفحه", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "وقتی روشن باشد، با هر تغییر صفحه (مثلاً ورق زدن مانگا) ترجمه به‌صورت خودکار انجام می‌شود.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = autoTranslate,
                    onCheckedChange = { scope.launch { SettingsStore.setAutoTranslate(context, it) } }
                )
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("ظاهر دکمه شناور و ترجمه", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Text("اندازه دکمه شناور: ${buttonSizeDp}dp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = buttonSizeDp.toFloat(),
                onValueChange = { newValue ->
                    scope.launch { SettingsStore.setButtonSizeDp(context, newValue.toInt()) }
                },
                valueRange = SettingsStore.MIN_BUTTON_SIZE_DP.toFloat()..SettingsStore.MAX_BUTTON_SIZE_DP.toFloat()
            )

            Spacer(Modifier.height(8.dp))
            Text("شفافیت پس‌زمینه متن ترجمه: ${(overlayOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = overlayOpacity,
                onValueChange = { newValue ->
                    scope.launch { SettingsStore.setOverlayOpacity(context, newValue) }
                },
                valueRange = 0.3f..1f
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("ترجمه آنلاین (اختیاری)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "برنامه به‌طور پیش‌فرض کاملاً آفلاین است. برای متن‌هایی که دیکشنری شما پوششش نمی‌دهد می‌توانید یکی از این دو سرویس آنلاین را فعال کنید. اگر اینترنت نبود یا سرویس در دسترس نبود، خودکار به مدل آفلاین برمی‌گردد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = onlineProvider == OnlineProvider.NONE,
                    onClick = { scope.launch { SettingsStore.setOnlineProvider(context, OnlineProvider.NONE) } }
                )
                Text("خاموش (فقط آفلاین)")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = onlineProvider == OnlineProvider.MYMEMORY,
                    onClick = { scope.launch { SettingsStore.setOnlineProvider(context, OnlineProvider.MYMEMORY) } }
                )
                Column {
                    Text("MyMemory")
                    Text(
                        "رایگان، بدون کارت و بدون ثبت‌نام - فوری کار می‌کند",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = onlineProvider == OnlineProvider.GOOGLE_FREE,
                    onClick = { scope.launch { SettingsStore.setOnlineProvider(context, OnlineProvider.GOOGLE_FREE) } }
                )
                Column {
                    Text("Google Translate (بدون کلید)")
                    Text(
                        "رایگان، بدون کارت و بدون ثبت‌نام - سریع‌تر و باکیفیت‌تر از MyMemory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = onlineProvider == OnlineProvider.AZURE,
                    onClick = { scope.launch { SettingsStore.setOnlineProvider(context, OnlineProvider.AZURE) } }
                )
                Column {
                    Text("Microsoft Azure Translator")
                    Text(
                        "کیفیت بالاتر، ولی نیاز به کارت بین‌المللی برای ساخت اکانت دارد",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (onlineProvider == OnlineProvider.MYMEMORY) {
                Spacer(Modifier.height(8.dp))
                var emailField by remember(myMemoryEmailStored) { mutableStateOf(myMemoryEmailStored) }
                OutlinedTextField(
                    value = emailField,
                    onValueChange = { emailField = it },
                    label = { Text("ایمیل (اختیاری - سهمیه را از ۵۰۰۰ به ۵۰,۰۰۰ کلمه/روز می‌رساند)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { scope.launch { SettingsStore.setMyMemoryEmail(context, emailField) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "این ایمیل فقط یک شناسه‌ی سهمیه است، نه ثبت‌نام واقعی؛ می‌توانید همین فیلد را خالی هم بگذارید و با سهمیه‌ی روزانه‌ی ۵۰۰۰ کلمه کار کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onlineProvider == OnlineProvider.AZURE) {
                Spacer(Modifier.height(8.dp))
                var apiKeyField by remember(azureApiKeyStored) { mutableStateOf(azureApiKeyStored) }
                var regionField by remember(azureRegionStored) { mutableStateOf(azureRegionStored) }

                OutlinedTextField(
                    value = apiKeyField,
                    onValueChange = { apiKeyField = it },
                    label = { Text("کلید API آژور (Ocp-Apim-Subscription-Key)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = regionField,
                    onValueChange = { regionField = it },
                    label = { Text("ناحیه (Region) - اگر منبع Global است خالی بگذارید") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            SettingsStore.setAzureApiKey(context, apiKeyField)
                            SettingsStore.setAzureRegion(context, regionField)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره کلید و ناحیه") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "راهنما: در portal.azure.com یک منبع رایگان «Translator» (لایه F0) بسازید؛ کلید و ناحیه از بخش Keys and Endpoint همان منبع قابل کپی است. این گزینه نیاز به کارت بین‌المللی دارد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            val overlayGranted = Permissions.canDrawOverlays(context)
            ListItem(
                headlineContent = { Text("مجوز نمایش روی سایر برنامه‌ها") },
                supportingContent = { Text(if (overlayGranted) "فعال است" else "غیرفعال - برای شروع ترجمه لازم است") },
                trailingContent = {
                    if (!overlayGranted) {
                        TextButton(onClick = {
                            context.startActivity(Permissions.overlaySettingsIntent(context))
                        }) { Text("فعال‌سازی") }
                    }
                }
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("عیب‌یابی", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "اگر ترجمه کار نکرد یا دکمه شناور واکنشی نداشت، اینجا را باز کنید تا دقیقاً ببینید در کدام مرحله مشکل پیش آمده.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showLogDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("نمایش گزارش برنامه (لاگ)")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "تغییرات این صفحه بلافاصله ذخیره و از دفعه‌ی بعدی که دکمه شناور را بزنید اعمال می‌شوند - نیازی به خاموش/روشن کردن مجدد ترجمه نیست.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showLogDialog) {
        LogViewerDialog(onDismiss = { showLogDialog = false })
    }
}

@Composable
private fun LogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(AppLogger.readLog(context)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("گزارش برنامه", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            logText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { logText = AppLogger.readLog(context) }) {
                        Text("تازه‌سازی")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        AppLogger.clearLog(context)
                        logText = AppLogger.readLog(context)
                    }) { Text("پاک کردن") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("app log", logText))
                    }) { Text("کپی") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) { Text("بستن") }
                }
            }
        }
    }
}
