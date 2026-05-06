package com.jhoy.shield.adblock

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom WebViewClient that integrates ad blocking.
 *
 * - Blocks ad/tracking network requests by returning empty responses
 * - Injects ad-blocking JavaScript after each page load
 * - Logs blocked request count for diagnostics
 */
class ShieldWebViewClient : WebViewClient() {

    private var blockedCount = 0

    @Volatile
    private var scriptInjectedThisPage = false

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

        // Fallback natively to AdBlocker DNS
        if (AdBlocker.shouldBlock(url)) {
            blockedCount++
            return AdBlocker.createEmptyResponse(url)
        }



        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        scriptInjectedThisPage = false
        // Inject early CSS-based blocking as fallback
        view?.evaluateJavascript(AdBlockScripts.MAIN_SCRIPT, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Re-inject after full page load to catch late elements
        view?.evaluateJavascript(AdBlockScripts.MAIN_SCRIPT, null)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false

        // Block navigation to ad URLs
        if (AdBlocker.shouldBlock(url)) {
            return true
        }

        // Prevent ERR_UNKNOWN_URL_SCHEME by blocking custom deep link schemes
        // like vnd.youtube.music:// or intent:// that the WebView cannot natively load.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return true // Tell WebView we handled it (by doing nothing)
        }

        return false
    }

    fun getBlockedCount(): Int = blockedCount
}
