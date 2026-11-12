package com.felix.faze

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Where the model shows what it makes. The page is the model's own HTML, so it is held in a cage: scripts may run but nothing can be
 * fetched (network blocked, a content policy on the page, every link refused), no files or content providers are readable, and the page
 * has no way to call into the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StageCard(title: String, html: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val web = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) { onDispose { web[0]?.destroy(); web[0] = null } }
    Column(modifier.clip(shape).background(Palette.Bg).border(1.dp, Palette.Line, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { "On the stage" }, color = Palette.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("✕", color = Palette.Dim, fontSize = 18.sp, modifier = Modifier.clickable { onClose() }.padding(10.dp))
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AColor.parseColor("#0b0f14"))
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.blockNetworkImage = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.databaseEnabled = false
                        settings.setGeolocationEnabled(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true // no link goes anywhere
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (request.url.scheme == "data" || request.url.scheme == "about") null
                                else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                        }
                        web[0] = this
                    }
                },
                update = { v ->
                    if (v.tag != html) {
                        v.tag = html
                        v.loadDataWithBaseURL(null, StageHtml.wrap(html), "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}
