package com.mangatranslator.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import android.view.ViewGroup
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mangatranslator.app.web.AdBlocker
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
private const val WELCOME_HTML = """
<html dir="rtl"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;margin:0;padding:28px;background:#fff;color:#222;line-height:1.9}
h2{margin-top:8px}li{margin:6px 0}</style></head><body>
<h2>مترجم چپتر</h2>
<ol>
<li>آدرس چپتر را در نوار بالا وارد یا paste کنید و دکمه جستجو را بزنید.</li>
<li>صبر کنید صفحه کامل باز شود.</li>
<li>دکمه «ترجمه چپتر» پایین صفحه را بزنید.</li>
</ol>
<p style="color:#777">اگر فقط یک کلمه بنویسید، در گوگل جستجو می‌شود.</p>
</body></html>
"""
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
    val clipboard = LocalClipboardManager.current

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
    var desktopMode by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var adBlockOn by remember { mutableStateOf(true) }
    val adBlockFlag = remember { AtomicBoolean(true) }       // خوانده‌شده از thread شبکه
    val blockedCount = remember { AtomicInteger(0) }
    val pageHostRef = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(Unit) {
        val on = SettingsStore.adBlockFlow(context).first()
        adBlockOn = on; adBlockFlag.set(on)
    }

    // WebView's default UA contains "; wv" and "Version/4.0", which many sites
    // (Cloudflare, image CDNs) treat as a bot/embedded view and refuse to serve
    // images to. Present a normal Chrome Mobile UA instead.
    val mobileUa = remember {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
    }
    val desktopUa = remember {
        val ver = Regex("Chrome/([\\d.]+)").find(mobileUa)?.groupValues?.get(1) ?: "124.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = mobileUa
            // Stop WebView from sending "X-Requested-With: <our package>", which
            // some sites use to block embedded browsers.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                // Block odd schemes (intent://, market:// from ads); keep http(s) in-app.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return scheme != "http" && scheme != "https"
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        status = "خطا در بارگذاری صفحه: ${error.description}"
                    } else {
                        AppLogger.error(context, TAG, "resource error ${error.errorCode} ${error.description}: ${request.url}")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    val code = errorResponse.statusCode
                    if (request.isForMainFrame) {
                        status = "سایت خطای HTTP $code برگرداند. گزینه «دسکتاپ» را امتحان کنید."
                    } else {
                        AppLogger.error(context, TAG, "HTTP $code for ${request.url}")
                    }
                }

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
                    // مسدودسازی تبلیغات (فقط زیرمنابع؛ ناوبری اصلی هرگز)
                    if (adBlockFlag.get() && !request.isForMainFrame && AdBlocker.shouldBlock(u, pageHostRef[0])) {
                        blockedCount.incrementAndGet()
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (url.startsWith("about:") || url.startsWith("data:")) return   // صفحه خوش‌آمد
                    job?.cancel()
                    running = false
                    total = 0; done = 0; failed = 0; translatedCount = 0
                    showingOriginal = false
                    pageUrl = url
                    urlField = url
                    pageHostRef[0] = try { android.net.Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }
                    if (adBlockFlag.get()) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                    status = "در حال بارگذاری صفحه…"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("about:") || url.startsWith("data:")) return
                    if (adBlockFlag.get()) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
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

    LaunchedEffect(Unit) {
        // یک بار یک صفحه اولیه بارگذاری می‌شود تا WebView از همان ابتدا درست رسم شود
        webView.loadDataWithBaseURL(null, WELCOME_HTML, "text/html", "utf-8", null)
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    fun loadUrl() {
        val input = urlField.trim()
        if (input.isEmpty()) return
        val target = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            // یک کلمه/جمله بدون نقطه یا با فاصله → جستجوی گوگل (نه آدرس)
            input.contains(' ') || !input.contains('.') ->
                "https://www.google.com/search?q=" + android.net.Uri.encode(input)
            else -> "https://$input"
        }
        webView.loadUrl(target)
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
                translator.setConfig(
                    SettingsStore.myMemoryEmailFlow(context).first(),
                    SettingsStore.colloquialFlow(context).first()
                )

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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── هدر فشرده: برگشت | فیلد آدرس | چسباندن | برو | منو ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.surface)
                .height(46.dp)
                .padding(horizontal = 2.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
            }
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp)
            ) {
                BasicTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        textDirection = TextDirection.Ltr
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadUrl() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (urlField.isEmpty()) {
                    Text(
                        "آدرس چپتر را وارد یا paste کنید",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = { clipboard.getText()?.text?.let { urlField = it.trim() } },
                modifier = Modifier.size(40.dp)
            ) { Icon(Icons.Filled.ContentPaste, contentDescription = "چسباندن") }
            IconButton(onClick = { loadUrl() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "برو")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "منو")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("بارگذاری مجدد") },
                        onClick = { menuOpen = false; webView.reload() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (desktopMode) "نسخه موبایل" else "نسخه دسکتاپ") },
                        onClick = {
                            menuOpen = false
                            desktopMode = !desktopMode
                            webView.settings.userAgentString = if (desktopMode) desktopUa else mobileUa
                            webView.reload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (adBlockOn) "حذف تبلیغات: روشن (${blockedCount.get()} مسدود)" else "حذف تبلیغات: خاموش") },
                        onClick = {
                            menuOpen = false
                            adBlockOn = !adBlockOn
                            adBlockFlag.set(adBlockOn)
                            scope.launch { SettingsStore.setAdBlock(context, adBlockOn) }
                            webView.reload()
                        }
                    )
                }
            }
        }

        // ── صفحه وب ──
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(
                factory = {
                    webView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── نوار پایین فشرده ──
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (running && total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !running && translatedCount > 0,
                        onClick = {
                            showingOriginal = !showingOriginal
                            scope.launch { evalJs(webView, "window.MT_show($showingOriginal);") }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text(if (showingOriginal) "ترجمه" else "اصل", fontSize = 12.sp) }
                    Spacer(Modifier.width(6.dp))
                    if (running) {
                        Button(
                            onClick = { job?.cancel() },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text("توقف", fontSize = 12.sp) }
                    } else {
                        Button(
                            onClick = ::startTranslate,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text("ترجمه چپتر", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
