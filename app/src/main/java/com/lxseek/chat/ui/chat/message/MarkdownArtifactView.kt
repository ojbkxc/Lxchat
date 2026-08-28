package com.lxseek.chat.ui.chat.message

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lxseek.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 聊天内可视化工件渲染：把 fenced 的 ```html / ```artifact 与 ```mermaid 代码块渲染成
 * 可交互的 WebView 内容，而不是一整块源码。
 *
 * - HTML 工件：直接把 AI/用户提供的 HTML 送进 WebView，默认关闭 JS（安全；可后续按内容放开）。
 * - Mermaid：引擎 mermaid.min.js 不打包进 APK（约 3.2MB，违背小安装包路线），首次用到时
 *   才按需下载并缓存到 app 私有目录，之后复用。与 Vosk 模型的"首启按需下载"策略一致。
 */
internal object MermaidEngine {
    private const val TAG = "MermaidEngine"
    private const val VERSION = "10.9.1"
    private const val CDN_URL = "https://cdn.jsdelivr.net/npm/mermaid@$VERSION/dist/mermaid.min.js"

    fun dir(context: Context): File = File(context.filesDir, "mermaid")

    fun file(context: Context): File = File(dir(context), "mermaid.min.js")

    fun isDownloaded(context: Context): Boolean = file(context).exists() && file(context).length() > 100_000L

    /** Download (and overwrite if stale) the mermaid engine on demand. */
    suspend fun ensure(context: Context): Boolean = withContext(Dispatchers.IO) {
        val target = file(context)
        if (isDownloaded(context)) return@withContext true
        runCatching {
            dir(context).mkdirs()
            val url = URL(CDN_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 60_000; readTimeout = 120_000
                setRequestProperty("User-Agent", "LxChat/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext false
                val tmp = File(dir(context), "mermaid.min.js.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                val ok = tmp.length() > 100_000L
                if (ok) {
                    // 原子替换，避免半成品留下
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                } else tmp.delete()
                ok
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}

@Composable
internal fun MarkdownArtifactView(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
) {
    when (language.lowercase()) {
        "html", "artifact", "web", "svg" -> HtmlArtifactView(code, modifier)
        "mermaid", "graph" -> MermaidArtifactView(code, modifier)
        else -> Text(text = code)
    }
}

/**
 * 直接把 HTML 源码渲染进 WebView（默认禁 JS）。JS 关闭保证默认安全，AI 自写页面若需要脚本，
 * 后续可加显式开关放宽——当前小包体/安全优先。
 */
@Composable
private fun HtmlArtifactView(code: String, modifier: Modifier) {
    val context = LocalContext.current
    val webView = remember { WebView(context).apply { setBackgroundColor(0xFFFFFFFF.toInt()) } }
    LaunchedEffect(code) {
        webView.loadDataWithBaseURL(
            null, code, "text/html", "utf-8", null,
        )
    }
    Box(modifier = modifier.height(360.dp)) {
        AndroidView(
            factory = { webView },
            update = { it.webViewClient = NoTitledWebViewClient },
        )
    }
}

/** A view-local title-less web view client so the host keeps the message title if any. */
private val NoTitledWebViewClient = object : WebViewClient() {}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidArtifactView(code: String, modifier: Modifier) {
    val context = LocalContext.current
    val state = remember { mutableStateOf<MermaidLoadState>(MermaidLoadState.Loading) }
    LaunchedEffect(context, code, state.value is MermaidLoadState.Ready) {
        if (!MermaidEngine.isDownloaded(context)) {
            state.value = MermaidLoadState.Downloading
            if (MermaidEngine.ensure(context)) {
                state.value = MermaidLoadState.Ready
            } else {
                state.value = MermaidLoadState.Error("图表引擎未就绪（需联网下载一次，下载失败）")
            }
        }
    }
    when (val s = state.value) {
        MermaidLoadState.Loading, MermaidLoadState.Downloading ->
            Text(stringResource(R.string.markdown_rendering_mermaid), modifier = modifier.padding(8.dp))
        is MermaidLoadState.Error -> Text(
            s.message, modifier = modifier.padding(8.dp),
            color = Color(0xFFE5484D),
        )
        MermaidLoadState.Ready -> {
            val html = remember { context.assets.open("www/mermaid_render.html").bufferedReader().use { it.readText() } }
            val webView = remember(context) { WebView(context) }
            LaunchedEffect(code) {
                webView.settings.javaScriptEnabled = true
                webView.settings.allowFileAccess = true
                webView.webViewClient = NoTitledWebViewClient
                val base = "file://" + MermaidEngine.dir(context).absolutePath + "/"
                webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
                webView.evaluateJavascript("__render(${jsString(code)})", null)
            }
            Box(modifier = modifier.height(320.dp)) { AndroidView(factory = { webView }) }
        }
    }
}

private sealed interface MermaidLoadState {
    data object Loading : MermaidLoadState
    data object Downloading : MermaidLoadState
    data object Ready : MermaidLoadState
    data class Error(val message: String) : MermaidLoadState
}

/** Escape a string so it can be passed as a JS literal argument. */
private fun jsString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    return "'$escaped'"
}