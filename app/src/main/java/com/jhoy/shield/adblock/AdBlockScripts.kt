package com.jhoy.shield.adblock

/**
 * JavaScript injected into pages to block/remove ad elements.
 * Handles YouTube Music specific ad removal and general ad cleanup.
 */
object AdBlockScripts {

    /**
     * Main ad blocking script injected on every page load.
     * Handles:
     * - YouTube ad skipping (video + overlay)
     * - Ad element removal via CSS selectors
     * - MutationObserver for dynamically loaded ads
     * - Premium upsell dialogs
     */
    val MAIN_SCRIPT = """
        (function() {
            'use strict';
            
            // Abort if running inside a WebWorker instead of the main page
            if (typeof window === 'undefined' || !window.document) return;

            // Prevent duplicate injection
            if (window.__shieldAdBlockActive) return;
            window.__shieldAdBlockActive = true;

            // ============================================
            // BACKGROUND VIDEO PLAYBACK SPOOF (Full Page Lifecycle)
            // Spoofs ALL signals YouTube uses to detect background:
            // - Page Visibility API (visibilityState, hidden)
            // - Page Lifecycle API (freeze, resume, pagehide, pageshow)
            // - Window focus events (blur, focus)
            // - document.hasFocus()
            // ============================================
            Object.defineProperty(document, 'visibilityState', {
                get: function() { return 'visible'; },
                configurable: true
            });
            Object.defineProperty(document, 'hidden', {
                get: function() { return false; },
                configurable: true
            });
            Object.defineProperty(document, 'webkitHidden', {
                get: function() { return false; },
                configurable: true
            });
            // Fake hasFocus to always return true
            document.hasFocus = function() { return true; };

            // Block ALL lifecycle events that YouTube uses to detect background
            [
                'visibilitychange', 'webkitvisibilitychange',
                'blur', 'focus',
                'pagehide', 'pageshow',
                'freeze', 'resume'
            ].forEach(function(evt) {
                window.addEventListener(evt, function(e) {
                    e.stopImmediatePropagation();
                    e.stopPropagation();
                }, true);
                document.addEventListener(evt, function(e) {
                    e.stopImmediatePropagation();
                    e.stopPropagation();
                }, true);
            });

            // ============================================
            // NUKE SERVICE WORKERS (Kill Offline Cached Ads)
            // ============================================
            if ('serviceWorker' in navigator) {
                try {
                    navigator.serviceWorker.getRegistrations().then(function(registrations) {
                        for(let registration of registrations) {
                            registration.unregister();
                            console.log('[Shield] Service Worker uninstalled for clean ad-free fetch.');
                        }
                    }).catch(e => {});
                } catch(e) {}
            }

            // ============================================
            // AJAX INTERCEPTION AND JSON PARASITE (v1.2.12)
            // ============================================
            const originalJsonParse = JSON.parse;

            function deepScrub(node) {
                let altered = false;
                if (!node || typeof node !== 'object') return false;
                if (Array.isArray(node)) {
                    for (let i = 0; i < node.length; i++) if (deepScrub(node[i])) altered = true;
                } else {
                    const adKeys = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams', 'adSlotRenderer', 'promotedSparklesWebRenderer'];
                    for (let k of adKeys) {
                        if (node[k] !== undefined) { delete node[k]; altered = true; }
                    }
                    for (let key in node) {
                        if (deepScrub(node[key])) altered = true;
                    }
                }
                return altered;
            }

            JSON.parse = function(text, reviver) {
                let obj = originalJsonParse.call(this, text, reviver);
                if (typeof text === 'string' && obj && typeof obj === 'object') {
                    if (text.includes('adPlacements') || text.includes('playerAds') || text.includes('adBreakParams') || text.includes('adSlots')) {
                        deepScrub(obj);
                    }
                }
                return obj;
            };

            function patchResponse(text) {
                try {
                    if (typeof text === 'string' && (text.includes('adPlacements') || text.includes('playerAds') || text.includes('adBreakParams') || text.includes('adSlots'))) {
                        let json = originalJsonParse.call(JSON, text);
                        if (deepScrub(json)) return JSON.stringify(json);
                    }
                } catch(e) {}
                return text;
            }

            // Cache best audio stream URL for native ExoPlayer background playback
            function cacheStreamUrl(responseText) {
                try {
                    var data = originalJsonParse.call(JSON, responseText);
                    if (!data || !data.streamingData) return;
                    var formats = data.streamingData.adaptiveFormats || data.streamingData.formats || [];
                    var best = null, bestBr = 0;
                    for (var i = 0; i < formats.length; i++) {
                        var f = formats[i];
                        if (f.mimeType && f.mimeType.indexOf('audio/') === 0 && f.url && (f.bitrate || 0) > bestBr) {
                            bestBr = f.bitrate; best = f;
                        }
                    }
                    if (best) {
                        window.__shieldStreamUrl = best.url;
                        window.__shieldStreamTs = Date.now();
                    }
                } catch(e) {}
            }

            const originalFetch = window.fetch;
            window.fetch = async (...args) => {
                let url = typeof args[0] === 'string' ? args[0] : args[0].url;
                if (url && (url.includes('/youtubei/v1/player') || url.includes('/youtubei/v1/next'))) {
                    try {
                        const response = await originalFetch(...args);
                        const text = await response.clone().text();
                        const patched = patchResponse(text);
                        if (url.includes('/youtubei/v1/player')) cacheStreamUrl(patched);
                        return new Response(patched, {
                            status: response.status,
                            statusText: response.statusText,
                            headers: response.headers
                        });
                    } catch(e) { return originalFetch(...args); }
                }
                return originalFetch(...args);
            };

            // Patch XHR Support
            const OriginalXHR = window.XMLHttpRequest;
            window.XMLHttpRequest = function() {
                const xhr = new OriginalXHR();
                const originalOpen = xhr.open;
                xhr.open = function(method, url) {
                    this._url = url;
                    return originalOpen.apply(this, arguments);
                };
                
                const originalSend = xhr.send;
                xhr.send = function() {
                    this.addEventListener('readystatechange', function() {
                        if (this.readyState === 4 && this._url && (this._url.includes('/youtubei/v1/player') || this._url.includes('/youtubei/v1/next'))) {
                            Object.defineProperty(this, 'responseText', {
                                get: function() {
                                    return patchResponse(this.__originalResponseText || this.response);
                                },
                                configurable: true
                            });
                        }
                        if (this.readyState === 4 && this._url && this._url.includes('/youtubei/v1/player')) {
                            try { cacheStreamUrl(this.response); } catch(e) {}
                        }
                    });
                    return originalSend.apply(this, arguments);
                };
                return xhr;
            };

            // Hook Global ytInitialPlayerResponse
            let _initialResponse = window.ytInitialPlayerResponse;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                get: function() { return _initialResponse; },
                set: function(val) {
                    try {
                        let text = JSON.stringify(val);
                        var patched = patchResponse(text);
                        cacheStreamUrl(patched);
                        _initialResponse = JSON.parse(patched);
                    } catch(e) { _initialResponse = val; }
                },
                configurable: true
            });

            // ============================================
            // CSS: Hide ad-related elements immediately
            // ============================================
            const style = document.createElement('style');
            style.textContent = `
                /* YouTube ad overlays */
                .ad-showing video,
                .ad-container,
                .ad-div,
                .masthead-ad,
                .video-ads,
                .ytp-ad-module,
                .ytp-ad-overlay-container,
                .ytp-ad-overlay-slot,
                .ytp-ad-text-overlay,
                .ytp-ad-image-overlay,
                .ytp-ad-skip-button-container,
                .ytp-ad-player-overlay,
                .ytp-ad-player-overlay-instream-info,
                .ytp-ad-persistent-progress-bar-container,
                #player-ads,
                #masthead-ad,
                ytd-ad-slot-renderer,
                ytd-rich-item-renderer[is-ad],
                ytd-display-ad-renderer,
                ytd-promoted-sparkles-web-renderer,
                ytd-promoted-sparkles-text-search-renderer,
                ytd-player-legacy-desktop-watch-ads-renderer,
                ytd-banner-promo-renderer,
                ytmusic-mealbar-promo-renderer,

                /* YouTube Music specific ads */
                ytmusic-statement-banner-renderer,
                ytmusic-mealbar-promo-renderer,
                .ytmusic-mealbar-promo-renderer,
                .ytmusic-statement-banner-renderer,
                tp-yt-paper-dialog.ytmusic-popup-container,
                ytmusic-popup-container,

                /* Premium upsell */
                ytd-popup-container,
                yt-mealbar-promo-renderer,
                iron-overlay-backdrop,
                tp-yt-iron-overlay-backdrop,
                .yt-spec-toast-renderer,

                /* Google ads general */
                .GoogleActiveViewElement,
                .adsbygoogle,
                iframe[src*="doubleclick"],
                iframe[src*="googlesyndication"],
                iframe[id*="google_ads"],
                div[id*="google_ads"],
                ins.adsbygoogle {
                    display: none !important;
                    visibility: hidden !important;
                    height: 0 !important;
                    width: 0 !important;
                    max-height: 0 !important;
                    overflow: hidden !important;
                    pointer-events: none !important;
                    position: absolute !important;
                    z-index: -9999 !important;
                }

                /* Fix layout after hiding ads */
                .ad-showing .html5-video-container {
                    display: block !important;
                }
            `;
            document.head.appendChild(style);

            // ============================================
            // Remove lingering ad placeholder elements
            // ============================================
            function removeAdElements() {
                const adSelectors = [
                    'ytd-ad-slot-renderer',
                    'ytd-rich-item-renderer[is-ad]',
                    'ytd-display-ad-renderer',
                    'ytd-promoted-sparkles-web-renderer',
                    '#player-ads',
                    '#masthead-ad',
                    'ytmusic-mealbar-promo-renderer',
                    'ytmusic-statement-banner-renderer',
                    'tp-yt-paper-dialog.ytmusic-popup-container',
                ];

                adSelectors.forEach(selector => {
                    document.querySelectorAll(selector).forEach(el => {
                        el.remove();
                    });
                });
            }

            // ============================================
            // MutationObserver: Watch for popups & general cleanup
            // ============================================
            const observer = new MutationObserver(mutations => {
                let shouldClean = false;
                for (const mutation of mutations) {
                    if (mutation.addedNodes.length > 0) {
                        shouldClean = true;
                        break;
                    }
                }
                if (shouldClean) {
                    // Dismiss premium upsell popups quickly
                    const dismissButtons = document.querySelectorAll(
                        'tp-yt-paper-dialog .dismiss-button, ' +
                        'ytmusic-popup-container .dismiss-button, ' +
                        'yt-button-renderer.style-suggestive a, ' +
                        '.yt-spec-toast-renderer #dismiss-button, ' +
                        'ytmusic-mealbar-promo-renderer #dismiss-button, ' +
                        '.ytp-ad-skip-button-modern, ' +
                        '.ytp-ad-skip-button, ' +
                        '.ytp-ad-skip-button-container, ' +
                        '.ytp-skip-ad-button'
                    );
                    dismissButtons.forEach(btn => {
                        if (btn.offsetParent !== null) btn.click();
                    });

                    removeAdElements();
                    
                    // Click skip buttons if they appear
                    const skipBtn = document.querySelector('.ytp-ad-skip-button-modern, .ytp-ad-skip-button');
                    if (skipBtn && skipBtn.offsetParent !== null) {
                        skipBtn.click();
                    }
                }
            });

            observer.observe(document.body || document.documentElement, {
                childList: true,
                subtree: true
            });

            // Initial cleanup
            removeAdElements();

            console.log('[Shield] Advanced DOM Assassin active');
            
            // ============================================
            // IMMORTAL UI BRUTE-FORCE SKIPPER (V1.2.10)
            // ============================================
            let wasAdVisible = false;
            setInterval(() => {
                try {
                    let video = document.querySelector('video');
                    if (!video) return;

                    let isAdVisible = false;
                    // Detect any class natively injected by YouTube Ad engine
                    let adElements = document.querySelectorAll(
                        '.ad-showing, .ad-interrupting, *[class*="ytp-ad-"], .ytm-promoted-sparkles-web-renderer, ytmusic-player-bar[player-ui-state="AD_PLAYING"]'
                    );

                    for (let el of adElements) {
                        try {
                            if (el && el.offsetParent !== null && window.getComputedStyle(el).display !== 'none') {
                                isAdVisible = true;
                                break;
                            }
                        } catch(e){}
                    }

                    if (isAdVisible) {
                        wasAdVisible = true;
                        video.playbackRate = 16.0;
                        video.muted = true;
                        if (!isNaN(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
                            video.currentTime = video.duration - 0.1;
                        }
                        let skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                        if (skipBtn) skipBtn.click();
                    } else if (wasAdVisible) {
                        // Restablecer valores inmediatamente al pasar al video original
                        wasAdVisible = false;
                        video.playbackRate = 1.0;
                        video.muted = false;
                    }
                } catch(e) {}
            }, 100);

        })();
    """.trimIndent()
}
