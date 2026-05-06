package com.jhoy.shield

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.jhoy.shield.media.BackgroundPlayWebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jhoy.shield.adblock.ShieldWebViewClient
import com.jhoy.shield.media.WebViewMediaBridge
import com.jhoy.shield.service.PlaybackService

/**
 * Main activity for Shield.
 * Optimized for Samsung Galaxy S25 Ultra (One UI 7, Android 15)
 *
 * - Full-screen WebView with display cutout support
 * - Background playback via foreground service
 * - MediaSession integration for lock screen / notification controls
 * - Ad blocking
 * - Offline download & playback
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: BackgroundPlayWebView
    private lateinit var mediaBridge: WebViewMediaBridge
    private var playbackService: PlaybackService? = null

    companion object {
        private const val TARGET_URL = "https://music.youtube.com"

        // Samsung Galaxy S25 Ultra user agent (Chrome on Android 15)
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; SM-S938B) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.6778.200 Mobile Safari/537.36"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, service still works */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optimizeForS25Ultra()
        requestNotificationPermission()

        // Setup media bridge
        mediaBridge = WebViewMediaBridge { title, artist, coverUrl, isPlaying, positionMs, durationMs ->
            runOnUiThread {
                updateServiceMetadata(title, artist, coverUrl, isPlaying, positionMs, durationMs)
            }
        }

        // Register bridge with service
        PlaybackService.mediaBridge = mediaBridge

        // Setup background stream callback: when JS reports a cached audio URL,
        // start native ExoPlayer playback in the foreground service
        mediaBridge.setOnBackgroundStreamCallback { streamUrl, positionMs ->
            // Store for fallback (notification play button when WebView is suspended)
            PlaybackService.lastStreamUrl = streamUrl
            PlaybackService.lastStreamPositionMs = positionMs
            val intent = Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_START_NATIVE
                putExtra("streamUrl", streamUrl)
                putExtra("positionMs", positionMs)
            }
            try { startService(intent) } catch (_: Exception) {}
        }

        // Enforce Native Ad-Blocking even on background Service Workers (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val url = request.url.toString()
                        if (com.jhoy.shield.adblock.AdBlocker.shouldBlock(url)) {
                            return com.jhoy.shield.adblock.AdBlocker.createEmptyResponse(url)
                        }
                        return super.shouldInterceptRequest(request)
                    }
                }
            )
        }

        webView = BackgroundPlayWebView(this).apply {
            clearCache(true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(false)
                textZoom = 100
                builtInZoomControls = false
                displayZoomControls = false
            }

            // CRITICAL: Keep WebView renderer alive when app goes to background.
            // Without this, Android freezes the renderer process and audio stops.
            setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false // waivedWhenNotVisible = false → stays IMPORTANT even in background
            )

            val shieldClient = ShieldWebViewClient()
            webViewClient = shieldClient

            // Inject ad-blocking JS BEFORE any HTML code evaluates
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    com.jhoy.shield.adblock.AdBlockScripts.MAIN_SCRIPT,
                    setOf("*")
                )
            }

            webChromeClient = WebChromeClient()

            // Attach media bridge JS interface
            mediaBridge.attachWebView(this)

            loadUrl(TARGET_URL)
        }

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        setupBackNavigation()
    }



    // ===== Existing methods =====

    private fun optimizeForS25Ultra() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        stopService(intent)
    }

    private fun updateServiceMetadata(title: String, artist: String, coverUrl: String, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("coverUrl", coverUrl)
            putExtra("isPlaying", isPlaying)
            putExtra("positionMs", positionMs)
            putExtra("durationMs", durationMs)
            action = "UPDATE_METADATA"
        }
        try {
            startService(intent)
        } catch (_: Exception) {
        }
    }



    override fun onPause() {
        super.onPause()
        PlaybackService.isAppInBackground = true
        startPlaybackService()

        // Safety-net keepalive: if the video somehow gets paused while in
        // background (e.g. by YouTube's internal logic), force-resume it.
        webView.evaluateJavascript("""
            (function() {
                if (window.__shieldKeepAlive) clearInterval(window.__shieldKeepAlive);
                window.__shieldKeepAlive = setInterval(function() {
                    var v = document.querySelector('video');
                    if (v && v.paused && window.__shieldWasPlaying) {
                        v.play().catch(function(){});
                    }
                }, 3000);
            })()
        """.trimIndent(), null)

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        PlaybackService.isAppInBackground = false

        // Clear keepalive timer — no longer needed in foreground
        webView.evaluateJavascript("""
            if (window.__shieldKeepAlive) {
                clearInterval(window.__shieldKeepAlive);
                window.__shieldKeepAlive = null;
            }
        """.trimIndent(), null)

        // Re-inject observer for metadata updates
        webView.evaluateJavascript(mediaBridge.observerScript, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        PlaybackService.mediaBridge = null
        mediaBridge.detachWebView()
        stopPlaybackService()
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
