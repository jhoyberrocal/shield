package com.jhoy.shield.media

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Bridge between the WebView (YouTube Music) and native MediaSession.
 *
 * - Injects JS that monitors the YT Music player state
 * - Receives track info and playback state via @JavascriptInterface
 * - Executes JS commands to control playback from notification buttons
 * - Handles ExoPlayer background handoff (stream URL caching + mute/unmute)
 */
class WebViewMediaBridge(
    private val onMetadataChanged: (title: String, artist: String, coverUrl: String, isPlaying: Boolean, positionMs: Long, durationMs: Long) -> Unit
) {
    private var webViewRef: WebView? = null
    private var onBackgroundStreamCallback: ((String, Long) -> Unit)? = null

    fun attachWebView(webView: WebView) {
        webViewRef = webView
        webView.addJavascriptInterface(this, "ShieldBridge")
    }

    fun detachWebView() {
        webViewRef?.removeJavascriptInterface("ShieldBridge")
        webViewRef = null
    }

    /**
     * Set callback invoked when JS reports a cached stream URL for native background playback.
     * Called from the JavascriptInterface binder thread.
     */
    fun setOnBackgroundStreamCallback(callback: (streamUrl: String, positionMs: Long) -> Unit) {
        onBackgroundStreamCallback = callback
    }

    // ===== Commands FROM notification TO WebView =====

    fun executePlay() {
        evaluateJs("""
            var v = document.querySelector('video');
            if (v && v.paused) {
                document.querySelector('#play-pause-button, .play-pause-button, tp-yt-paper-icon-button.play-pause-button')?.click();
            }
        """.trimIndent().replace("\n", " "))
    }

    fun executePause() {
        evaluateJs("""
            var v = document.querySelector('video');
            if (v && !v.paused) {
                document.querySelector('#play-pause-button, .play-pause-button, tp-yt-paper-icon-button.play-pause-button')?.click();
            }
        """.trimIndent().replace("\n", " "))
    }

    fun executeNext() {
        evaluateJs("document.querySelector('.next-button, tp-yt-paper-icon-button.next-button')?.click();")
    }

    fun executePrevious() {
        evaluateJs("document.querySelector('.previous-button, tp-yt-paper-icon-button.previous-button')?.click();")
    }

    fun executeSeek(posMs: Long) {
        val seconds = posMs / 1000.0
        evaluateJs("var v = document.querySelector('video'); if (v) v.currentTime = $seconds;")
    }

    /** Unmute the WebView video element */
    fun executeUnmute() {
        evaluateJs("var v = document.querySelector('video'); if (v) v.muted = false;")
    }

    /** Unmute WebView, then skip to next track */
    fun executeUnmuteAndNext() {
        executeUnmute()
        executeNext()
    }

    /** Unmute WebView, then skip to previous track */
    fun executeUnmuteAndPrevious() {
        executeUnmute()
        executePrevious()
    }

    /** Seek WebView video to position, unmute, and play */
    fun executeResumeAt(positionMs: Long) {
        val seconds = positionMs / 1000.0
        evaluateJs("""
            (function() {
                var v = document.querySelector('video');
                if (!v) return;
                v.currentTime = $seconds;
                v.muted = false;
                if (v.paused) v.play().catch(function(){});
            })()
        """.trimIndent().replace("\n", " "))
    }

    // ===== Callbacks FROM WebView TO native =====

    @JavascriptInterface
    fun onPlaybackStateChanged(title: String, artist: String, coverUrl: String, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        onMetadataChanged(title, artist, coverUrl, isPlaying, positionMs, durationMs)
    }

    /**
     * Called from JS when the app goes to background and a cached stream URL is available.
     * Triggers the native ExoPlayer handoff.
     */
    @JavascriptInterface
    fun onBackgroundStreamReady(streamUrl: String, positionMs: Long) {
        onBackgroundStreamCallback?.invoke(streamUrl, positionMs)
    }

    // ===== JS Scripts =====

    /**
     * JS evaluated in onPause() to handle background playback.
     * Two-tier strategy:
     * 1. If a valid cached stream URL exists → mute WebView, start ExoPlayer (reliable)
     * 2. If no stream URL → keep WebView playing (relies on Visibility API spoof)
     */
    val backgroundHandoffScript = """
        (function() {
            var v = document.querySelector('video');
            if (!v) return;
            if (v.currentTime <= 0) return;

            // Use tracked playing state from observer
            var wasPlaying = window.__shieldWasPlaying === true || !v.paused;
            if (!wasPlaying) return;

            var url = window.__shieldStreamUrl || '';
            var ts = window.__shieldStreamTs || 0;
            var urlValid = url && (Date.now() - ts < 3600000);

            if (urlValid) {
                // Tier 1: Hand off to native ExoPlayer for reliable background playback
                v.muted = true;
                ShieldBridge.onBackgroundStreamReady(url, Math.floor(v.currentTime * 1000));
            } else {
                // Tier 2: No valid stream URL — keep WebView audio playing.
                // The Visibility API spoof + keepalive timer will maintain playback.
                // Ensure video is not muted and playing.
                if (v.muted) v.muted = false;
                if (v.paused) v.play().catch(function(){});
                console.log('[Shield] No stream URL, relying on WebView background playback');
            }
        })()
    """.trimIndent()

    /** Observer JS injected into the page to monitor playback state */
    val observerScript = """
        (function() {
            if (window.__shieldMediaObserverActive) return;
            window.__shieldMediaObserverActive = true;

            let lastState = '';

            function reportState() {
                try {
                    const playPauseBtn = document.querySelector(
                        '#play-pause-button, .play-pause-button, tp-yt-paper-icon-button.play-pause-button'
                    );
                    
                    const titleEl = document.querySelector(
                        '.title.ytmusic-player-bar, ' +
                        '.content-info-wrapper .title, ' +
                        'ytmusic-player-bar .title'
                    );
                    
                    const artistEl = document.querySelector(
                        '.byline.ytmusic-player-bar, ' +
                        '.content-info-wrapper .byline, ' +
                        'ytmusic-player-bar .byline, ' +
                        'span.subtitle ytmusic-player-bar'
                    );

                    const title = titleEl?.textContent?.trim() || '';
                    const artist = artistEl?.textContent?.trim() || '';
                    
                    const thumbImg = document.querySelector('img.ytmusic-player-bar, .thumbnail.ytmusic-player-bar img, ytmusic-player-bar .image');
                    let coverUrl = thumbImg ? (thumbImg.src || '') : '';
                    if (coverUrl.includes('w60-h60')) {
                        coverUrl = coverUrl.replace('w60-h60', 'w512-h512');
                    }
                    
                    // Detect playing state from button aria-label or title attribute
                    let isPlaying = false;
                    if (playPauseBtn) {
                        const label = (
                            playPauseBtn.getAttribute('aria-label') || 
                            playPauseBtn.getAttribute('title') || 
                            ''
                        ).toLowerCase();
                        // If button says "pause", music IS playing
                        isPlaying = label.includes('pause') || label.includes('pausa');
                    }
                    
                    // Also check if any video/audio is actively playing
                    let positionMs = 0;
                    let durationMs = 0;
                    const media = document.querySelector('video, audio');
                    if (media) {
                        positionMs = Math.floor(media.currentTime * 1000);
                        durationMs = Math.floor(media.duration * 1000) || 0;
                        if (!isPlaying && !media.paused && media.currentTime > 0) {
                            isPlaying = true;
                        }
                    }

                    // Track playing state globally for background handoff
                    window.__shieldWasPlaying = isPlaying;

                    // We group position in windows of 2 seconds so we dont spam the native bridge every single second unless user seeks or state changes.
                    const currentState = title + '|' + artist + '|' + coverUrl + '|' + isPlaying + '|' + Math.floor(positionMs/2000) + '|' + durationMs;
                    if (currentState !== lastState && title) {
                        lastState = currentState;
                        ShieldBridge.onPlaybackStateChanged(title, artist, coverUrl, isPlaying, positionMs, durationMs);
                    }
                } catch(e) {}
            }

            // Poll every second
            setInterval(reportState, 1000);

            // Also observe DOM changes for faster detection
            const observer = new MutationObserver(() => {
                setTimeout(reportState, 200);
            });

            const playerBar = document.querySelector('ytmusic-player-bar');
            if (playerBar) {
                observer.observe(playerBar, { childList: true, subtree: true, attributes: true });
            } else {
                // Wait for player bar to appear
                const bodyObserver = new MutationObserver(() => {
                    const bar = document.querySelector('ytmusic-player-bar');
                    if (bar) {
                        observer.observe(bar, { childList: true, subtree: true, attributes: true });
                        bodyObserver.disconnect();
                    }
                });
                bodyObserver.observe(document.body || document.documentElement, {
                    childList: true, subtree: true
                });
            }

            console.log('[Shield] Media bridge observer active');
        })();
    """.trimIndent()

    private fun evaluateJs(js: String) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(js, null)
        }
    }
}
