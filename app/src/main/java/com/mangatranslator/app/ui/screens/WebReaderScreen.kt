package com.mangatranslator.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangatranslator.app.data.SettingsStore
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.web.ChapterImageResult
import com.mangatranslator.app.web.ChapterTranslator
import com.mangatranslator.app.web.ReaderScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

private const val TAG = "WebReader"
private val SAFE_NAME = Regex("[0-9a-f]{32}\\.jpg")

/** اجرای جاوااسکریپت و برگرداندن نتیجه رشته‌ای (باید روی Main صدا زده شود). */
private suspend fun evalJs(wv: WebView, js: String): String? =
    suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { raw ->
            val value = try {
                if (raw == null || raw == "null") null
                else (JSONTokener(raw).nextValue() as? String) ?: raw
            } catch (e: Exception) { raw }
            if (cont.isActive) cont.resume(value)
        }
    }

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translator = remember { ChapterTranslator(context.applicationContext) }

    var urlField by rememberSaveable { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var translatedCount by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var showingOriginal by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("آدرس چپتر را وارد کنید، صفحه باز شود، سپس «ترجمه کل چپتر» را بزنید.") }
    var job by remember { mutableStateOf<Job?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                // تصاویر ترجمه‌شده از کش دیسک به صفحه داده می‌شوند (آدرس مجازی mt.local)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.host == ReaderScripts.HOST && u.path?.startsWith("/t/") == true) {
                        val name = u.lastPathSegment ?: return null
                        if (!SAFE_NAME.matches(name)) return null
                        val f = File(translator.dir, name)
                        if (f.exists()) {
                            return WebResourceResponse(
                                "image/jpeg", null, 200, "OK",
                                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
                                FileInputStream(f)
                            )
                        }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    job?.cancel()
                    running = false
                    total = 0; done = 0; failed = 0; translatedCount = 0
                    showingOriginal = false
                    pageUrl = url
                    urlField = url
                    status = "در حال بارگذاری صفحه…"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    status = "صفحه آماده است. برای ترجمه همه تصاویر چپتر، دکمه پایین را بزنید."
                }
            }
        }
    }

    DisposableEffect(Unit) {
        translator.cleanup(3L * 24 * 60 * 60 * 1000)
        onDispose {
            job?.cancel()
            translator.close()
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    fun loadUrl() {
        var u = urlField.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        webView.loadUrl(u)
    }

    fun startTranslate() {
        if (running) return
        job = scope.launch {
            running = true
            showingOriginal = false
            failed = 0; done = 0; total = 0
            try {
                status = "در حال یافتن تصاویر چپتر…"
                evalJs(webView, ReaderScripts.INJECT)
                val raw = evalJs(webView, "(function(){try{return window.MT_scan();}catch(e){return '{\"visible\":0,\"items\":[]}';}})()")
                if (raw == null) { status = "خواندن صفحه ممکن نشد."; return@launch }

                val parsed = JSONObject(raw)
                val arr = parsed.getJSONArray("items")
                val visible = parsed.optInt("visible", 0).coerceIn(0, maxOf(0, arr.length() - 1))
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it); o.getInt("id") to o.getString("url")
                }
                if (items.isEmpty()) {
                    status = "تصویری برای ترجمه پیدا نشد. صفحه را کمی اسکرول کنید تا تصاویر بارگذاری شوند و دوباره بزنید."
                    return@launch
                }
                total = items.size

                // اول از تصویری که الان دیده می‌شود شروع می‌کنیم، بعد بقیه
                val order = items.drop(visible) + items.take(visible)
                val ua = webView.settings.userAgentString
                val srcLang = SettingsStore.sourceLangFlow(context).first()
                val opacity = SettingsStore.overlayOpacityFlow(context).first()
                translator.setEmail(SettingsStore.myMemoryEmailFlow(context).first())

                val slots = Semaphore(2)   // حداکثر ۲ تصویر همزمان (حافظه)
                coroutineScope {
                    order.map { (id, url) ->
                        async {
                            slots.withPermit {
                                val cookie = CookieManager.getInstance().getCookie(url)
                                    ?: CookieManager.getInstance().getCookie(pageUrl)
                                when (val res = translator.process(url, pageUrl, ua, cookie, srcLang, opacity)) {
                                    is ChapterImageResult.Done -> {
                                        evalJs(webView, "window.MT_replace($id,'https://${ReaderScripts.HOST}/t/${res.file.name}');")
                                        translatedCount++
                                    }
                                    ChapterImageResult.NoText -> {}
                                    ChapterImageResult.Failed -> failed++
                                }
                                done++
                                status = "ترجمه چپتر: $done از $total"
                            }
                        }
                    }.awaitAll()
                }
                status = buildString {
                    append("تمام شد: $translatedCount تصویر ترجمه شد")
                    if (failed > 0) append("، $failed تصویر ناموفق (اینترنت/سهمیه MyMemory/بلاک سایت)")
                }
            } catch (e: CancellationException) {
                status = "متوقف شد ($done از $total)"
                throw e
            } catch (e: Exception) {
                AppLogger.error(context, TAG, "chapter translate failed", e)
                status = "خطا: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("خواندن چپتر از وب") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    if (running && total > 0) {
                        LinearProgressIndicator(
                            progress = { done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (running) {
                            Button(onClick = { job?.cancel() }, modifier = Modifier.weight(1f)) { Text("توقف") }
                        } else {
                            Button(onClick = ::startTranslate, modifier = Modifier.weight(1f)) { Text("ترجمه کل چپتر") }
                        }
                        OutlinedButton(
                            enabled = !running && translatedCount > 0,
                            onClick = {
                                showingOriginal = !showingOriginal
                                scope.launch { evalJs(webView, "window.MT_show($showingOriginal);") }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (showingOriginal) "نمایش ترجمه" else "نمایش اصل") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    placeholder = { Text("https://…/chapter-118/") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadUrl() }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { loadUrl() }) { Text("برو") }
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}
