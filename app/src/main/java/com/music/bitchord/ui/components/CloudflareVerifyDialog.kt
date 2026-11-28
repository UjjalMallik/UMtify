package com.music.bitchord.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.data.CloudflareSessionManager
import com.music.bitchord.data.LosslessAudioProvider
import kotlinx.coroutines.delay

/**
 * Auto-dismissing in-app Cloudflare Turnstile verification dialog.
 *
 * Loads the challenge URL inside a hardware-accelerated Android [WebView], polls
 * [CookieManager] every 500ms + on page finish for `cf_clearance=` or verification redirect,
 * persists cookies alongside the exact WebView `User-Agent`, and dismisses automatically.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareVerifyDialog(
    request: CloudflareSessionManager.VerificationRequest,
    onDismiss: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var capturedUa by remember { mutableStateOf(LosslessAudioProvider.BROWSER_USER_AGENT) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var completed by remember { mutableStateOf(false) }

    fun checkAndFinishIfCleared(currentUrl: String? = null) {
        if (completed) return
        val cm = CookieManager.getInstance()
        val hostCookie = cm.getCookie("https://${request.host}").orEmpty()
        val urlCookie = currentUrl?.let { cm.getCookie(it) }.orEmpty()
        val reqCookie = cm.getCookie(request.url).orEmpty()
        val combined = listOf(hostCookie, urlCookie, reqCookie)
            .firstOrNull { it.contains("cf_clearance=", ignoreCase = true) }

        val urlLower = currentUrl?.lowercase().orEmpty()
        val redirectCleared = urlLower.startsWith("umtify://") ||
            urlLower.startsWith("mornye://") ||
            urlLower.contains("granted=") ||
            urlLower.contains("/verified")

        if (combined != null || (redirectCleared && hostCookie.isNotBlank())) {
            completed = true
            runCatching { cm.flush() }
            val bestCookies = combined ?: hostCookie.ifBlank { urlCookie.ifBlank { reqCookie } }
            val ua = webViewRef?.settings?.userAgentString?.takeIf { it.isNotBlank() } ?: capturedUa
            CloudflareSessionManager.completeVerification(request.host, bestCookies, ua)
        }
    }

    LaunchedEffect(request.url) {
        while (!completed) {
            checkAndFinishIfCleared(webViewRef?.url)
            delay(500L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(520.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF141418),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = Color(0xFF66BB6A),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Verifying Download Provider...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = request.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA0A0AC),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFC0C0CC),
                        )
                    }
                }

                if (pageLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFF66BB6A),
                        trackColor = Color(0xFF26262E),
                    )
                } else {
                    HorizontalDivider(color = Color(0xFF26262E))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(Color(0xFF1A1A20)),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    loadsImagesAutomatically = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }
                                capturedUa = settings.userAgentString ?: LosslessAudioProvider.BROWSER_USER_AGENT
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        pageLoading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        pageLoading = false
                                        checkAndFinishIfCleared(url)
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        req: WebResourceRequest?,
                                    ): Boolean {
                                        val nextUrl = req?.url?.toString().orEmpty()
                                        checkAndFinishIfCleared(nextUrl)
                                        if (nextUrl.startsWith("umtify://") || nextUrl.startsWith("mornye://")) {
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webViewRef = this
                                loadUrl(request.url)
                            }
                        },
                    )
                }
            }
        }
    }
}
