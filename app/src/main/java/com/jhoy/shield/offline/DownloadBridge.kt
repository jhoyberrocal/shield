package com.jhoy.shield.offline

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

/**
 * JavascriptInterface bridge that receives download requests from the WebView.
 * Feeds the DownloadTracker singleton for UI observation.
 */
class DownloadBridge(
    private val context: Context,
    private val downloader: AudioDownloader
) {
    private var webViewRef: WebView? = null
    private var onDownloadComplete: (() -> Unit)? = null

    fun attachWebView(webView: WebView) {
        webViewRef = webView
        webView.addJavascriptInterface(this, "ShieldDownloadBridge")
    }

    fun detachWebView() {
        webViewRef?.removeJavascriptInterface("ShieldDownloadBridge")
        webViewRef = null
    }

    fun setOnDownloadComplete(listener: () -> Unit) {
        onDownloadComplete = listener
    }

    @JavascriptInterface
    fun startDownload(
        videoId: String,
        title: String,
        artist: String,
        coverUrl: String,
        durationMs: Long,
        audioUrl: String,
        fileExtension: String
    ) {
        if (downloader.isDownloaded(videoId)) {
            showToast("✓ Ya descargada: $title")
            updateButtonState(videoId, "downloaded")
            return
        }
        if (downloader.isDownloading(videoId)) {
            showToast("⏳ Descargando: $title")
            return
        }

        // Track in singleton
        DownloadTracker.enqueue(videoId, title, artist)
        showToast("⬇ Descargando: $title")
        updateButtonState(videoId, "downloading")

        downloader.download(
            videoId = videoId,
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            durationMs = durationMs,
            audioUrl = audioUrl,
            fileExtension = fileExtension,
            callback = object : AudioDownloader.Callback {
                override fun onProgress(videoId: String, percent: Int) {
                    DownloadTracker.updateProgress(videoId, percent)
                    updateProgress(videoId, percent)
                }

                override fun onComplete(videoId: String, song: OfflineSong) {
                    DownloadTracker.markCompleted(videoId)
                    updateButtonState(videoId, "downloaded")
                    onDownloadComplete?.invoke()
                }

                override fun onError(videoId: String, error: String) {
                    DownloadTracker.markError(videoId, error)
                    showToast("✗ Error: $error")
                    updateButtonState(videoId, "ready")
                }
            }
        )
    }

    @JavascriptInterface
    fun isDownloaded(videoId: String): Boolean = downloader.isDownloaded(videoId)

    @JavascriptInterface
    fun isDownloading(videoId: String): Boolean = downloader.isDownloading(videoId)

    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateButtonState(videoId: String, state: String) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(
                "if(window.__shieldUpdateDownloadBtn) window.__shieldUpdateDownloadBtn('$videoId','$state');",
                null
            )
        }
    }

    private fun updateProgress(videoId: String, percent: Int) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(
                "if(window.__shieldUpdateProgress) window.__shieldUpdateProgress('$videoId',$percent);",
                null
            )
        }
    }
}
