package com.jhoy.shield.offline

/**
 * JavaScript injected into YouTube Music to:
 * 1. Add a download button visible ONLY when the player bar is active
 * 2. Intercept both fetch AND XHR to capture streaming URLs
 * 3. Drive the download via the native ShieldDownloadBridge interface
 *
 * Uses SVG for the icon (not emoji) for Material Design consistency.
 */
object DownloadBridgeScripts {

    val DOWNLOAD_SCRIPT = """
        (function() {
            'use strict';
            if (window.__shieldDLv4) return;
            window.__shieldDLv4 = true;

            // ============================================
            // CACHE for streaming data
            // ============================================
            window.__shieldStreamCache = window.__shieldStreamCache || {};

            function cachePlayerData(data) {
                try {
                    if (data && data.videoDetails && data.videoDetails.videoId && data.streamingData) {
                        var vid = data.videoDetails.videoId;
                        window.__shieldCurrentVideoId = vid; // Guardar globalmente
                        var formats = data.streamingData.adaptiveFormats || data.streamingData.formats || [];
                        if (formats.length > 0) {
                            window.__shieldStreamCache[vid] = {
                                formats: formats,
                                timestamp: Date.now()
                            };
                            setTimeout(updateBtnForCurrent, 300);
                        }
                    }
                } catch(e) {}
            }

            // Intercept fetch
            if (!window.__shieldFetchV4) {
                window.__shieldFetchV4 = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var result = origFetch.apply(this, args);
                    try {
                        var url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                        if (url.indexOf('/youtubei/v1/player') !== -1) {
                            result.then(function(resp) {
                                var cloned = resp.clone();
                                cloned.json().then(function(data) { cachePlayerData(data); }).catch(function(){});
                            }).catch(function(){});
                        }
                    } catch(e) {}
                    return result;
                };
            }

            // Also intercept XMLHttpRequest (YouTube Music sometimes uses XHR)
            if (!window.__shieldXHRV4) {
                window.__shieldXHRV4 = true;
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__shieldUrl = url || '';
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    var self = this;
                    if (self.__shieldUrl && self.__shieldUrl.indexOf('/youtubei/v1/player') !== -1) {
                        self.addEventListener('load', function() {
                            try {
                                var data = JSON.parse(self.responseText);
                                cachePlayerData(data);
                            } catch(e) {}
                        });
                    }
                    return origSend.apply(this, arguments);
                };
            }

            // ============================================
            // SVG-based download icon (Material Design arrow-down-to-line)
            // ============================================
            var SVG_DOWNLOAD = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M12 4v12M12 16l-5-5M12 16l5-5M5 20h14"/></svg>';
            var SVG_LOADING = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" width="24" height="24"><path d="M12 4v4M12 16v4M4 12h4M16 12h4M6.3 6.3l2.8 2.8M14.9 14.9l2.8 2.8M6.3 17.7l2.8-2.8M14.9 9.1l2.8-2.8"/></svg>';
            var SVG_CHECK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M5 12l5 5L19 7"/></svg>';

            // ============================================
            // UI
            // ============================================
            function ensureStyles() {
                if (document.getElementById('shield-dl-css')) return;
                var parent = document.head || document.documentElement;
                if (!parent) return;
                var style = document.createElement('style');
                style.id = 'shield-dl-css';
                style.textContent = [
                    '@keyframes shield-rotate { to { transform: rotate(360deg); } }',
                    '@keyframes shield-pop { from{opacity:0;transform:scale(0.4)}to{opacity:1;transform:scale(1)} }',
                    '#shield-dl-btn[data-state="hidden"] { display:none!important; }',
                    '#shield-dl-btn[data-state="visible"],',
                    '#shield-dl-btn[data-state="downloading"],',
                    '#shield-dl-btn[data-state="downloaded"] {',
                    '  display:flex!important;',
                    '  animation: shield-pop 0.25s ease!important;',
                    '}',
                    '#shield-dl-btn {',
                    '  all:initial!important;',
                    '  position:fixed!important;',
                    '  bottom:85px!important;',
                    '  left:14px!important;',
                    '  z-index:2147483647!important;',
                    '  width:50px!important;',
                    '  height:50px!important;',
                    '  background:rgba(25,25,25,0.95)!important;',
                    '  border:2px solid rgba(255,255,255,0.2)!important;',
                    '  border-radius:50%!important;',
                    '  color:#fff!important;',
                    '  display:none!important;',
                    '  align-items:center!important;',
                    '  justify-content:center!important;',
                    '  cursor:pointer!important;',
                    '  box-shadow:0 4px 20px rgba(0,0,0,0.6)!important;',
                    '  -webkit-tap-highlight-color:transparent!important;',
                    '  touch-action:manipulation!important;',
                    '  user-select:none!important;',
                    '  pointer-events:auto!important;',
                    '  padding:0!important;margin:0!important;',
                    '}',
                    '#shield-dl-btn:active { transform:scale(0.85)!important; }',
                    '#shield-dl-btn[data-state="downloading"] { border-color:rgba(255,170,0,0.5)!important; }',
                    '#shield-dl-btn[data-state="downloading"] svg { animation:shield-rotate 1.2s linear infinite!important; color:#ffaa00!important; }',
                    '#shield-dl-btn[data-state="downloaded"] { border-color:rgba(0,204,102,0.5)!important; color:#00cc66!important; }',
                ].join('\n');
                parent.appendChild(style);
            }

            function ensureButton() {
                if (document.getElementById('shield-dl-btn')) return true;
                var parent = document.body || document.documentElement;
                if (!parent) return false;
                ensureStyles();

                var btn = document.createElement('div');
                btn.id = 'shield-dl-btn';
                btn.setAttribute('data-state', 'hidden');
                btn.setAttribute('role', 'button');
                btn.innerHTML = SVG_DOWNLOAD;

                btn.addEventListener('click', function(e) {
                    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                    onDownloadClick();
                }, true);
                btn.addEventListener('touchend', function(e) {
                    e.preventDefault(); e.stopPropagation();
                    onDownloadClick();
                }, {passive:false});

                parent.appendChild(btn);
                return true;
            }

            function isPlayerActive() {
                // Chequar si estamos en el reproductor EXPANDIDO evaluando el tamaño de la portada del álbum.
                // En el mini-reproductor mide ~40px. Expandido mide mas de 150px.
                var thumb = document.querySelector('img.ytmusic-player-bar, .thumbnail.ytmusic-player-bar img, ytmusic-player-bar .image, .ytmusic-player-page .image, .thumbnail-image-wrapper img');
                if (thumb) {
                    var rect = thumb.getBoundingClientRect();
                    // Si el artwork es grande y la vista está renderizada
                    if (rect.height > 100) return true;
                }
                
                // Si explícitamente estamos navegando en /watch (Desktop mode / PWAs)
                if (window.location.href.indexOf('/watch') !== -1) return true;

                return false;
            }

            function updateVisibility() {
                var btn = document.getElementById('shield-dl-btn');
                if (!btn) return;
                var state = btn.getAttribute('data-state');
                // Always recalculate visibility strictly based on playing state unless it is actively downloading
                if (state === 'downloading') return; 
                btn.setAttribute('data-state', isPlayerActive() ? 'visible' : 'hidden');
            }

            // ============================================
            // DOWNLOAD LOGIC
            // ============================================
            function getCurrentVideoId() {
                try { var u = new URL(location.href); var v = u.searchParams.get('v'); if (v && v.length === 11) return v; } catch(e){}
                var m = location.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
                if (m) return m[1];
                try {
                    var sel = document.querySelector('ytmusic-player-queue-item[selected] a[href*="watch"]');
                    if (sel) { var mm = sel.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/); if (mm) return mm[1]; }
                } catch(e) {}
                
                // Fallback maestro: el interceptor de peticiones guardó el ID activamente
                if (window.__shieldCurrentVideoId) return window.__shieldCurrentVideoId;

                return null;
            }

            function getTrackInfo() {
                var t = document.querySelector('ytmusic-player-bar .title, .content-info-wrapper .title');
                var a = document.querySelector('ytmusic-player-bar .byline, .content-info-wrapper .byline');
                var img = document.querySelector('ytmusic-player-bar .image, ytmusic-player-bar img.image, .thumbnail-image-wrapper img');
                var media = document.querySelector('video, audio');
                var cover = (img && img.src) ? img.src : '';
                if (cover.match(/w\d+-h\d+/)) cover = cover.replace(/w\d+-h\d+/, 'w512-h512');
                return {
                    title: t ? t.textContent.trim() : 'Sin título',
                    artist: a ? a.textContent.trim() : 'Artista desconocido',
                    coverUrl: cover,
                    durationMs: media ? Math.floor((media.duration || 0) * 1000) : 0
                };
            }

            function findBestAudio(formats) {
                var best = null, bestBr = 0;
                for (var i = 0; i < formats.length; i++) {
                    var f = formats[i];
                    if (f.mimeType && f.mimeType.indexOf('audio/') === 0 && f.url && (f.bitrate||0) > bestBr) {
                        bestBr = f.bitrate; best = f;
                    }
                }
                if (!best) return null;
                return { url: best.url, ext: best.mimeType.indexOf('mp4') !== -1 ? 'm4a' : 'webm' };
            }

            function onDownloadClick() {
                var vid = getCurrentVideoId();
                if (!vid) { console.log('[Shield] No video ID found'); return; }

                try {
                    if (typeof ShieldDownloadBridge !== 'undefined') {
                        if (ShieldDownloadBridge.isDownloaded(vid)) { setIconState('downloaded'); return; }
                        if (ShieldDownloadBridge.isDownloading(vid)) { return; }
                    }
                } catch(e) {}

                var info = getTrackInfo();
                var audioUrl = null, ext = 'm4a';

                // Try cache first
                var cached = window.__shieldStreamCache[vid];
                if (cached && (Date.now() - cached.timestamp < 3600000)) {
                    var r = findBestAudio(cached.formats);
                    if (r) { audioUrl = r.url; ext = r.ext; }
                }

                if (audioUrl) {
                    startNativeDownload(vid, info, audioUrl, ext);
                } else {
                    // Fetch fresh
                    setIconState('downloading');

                    var apiKey = '', clientName = 'WEB_REMIX', clientVersion = '1.20260401.01.00';
                    try {
                        if (window.ytcfg && window.ytcfg.get) {
                            apiKey = window.ytcfg.get('INNERTUBE_API_KEY') || '';
                            clientName = window.ytcfg.get('INNERTUBE_CLIENT_NAME') || clientName;
                            clientVersion = window.ytcfg.get('INNERTUBE_CLIENT_VERSION') || clientVersion;
                        }
                    } catch(e) {}

                    var kp = apiKey ? '?key=' + apiKey + '&prettyPrint=false' : '?prettyPrint=false';
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', '/youtubei/v1/player' + kp);
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    xhr.onload = function() {
                        try {
                            var data = JSON.parse(xhr.responseText);
                            cachePlayerData(data);
                            var formats = (data.streamingData && data.streamingData.adaptiveFormats) || [];
                            var result = findBestAudio(formats);
                            if (result) {
                                startNativeDownload(vid, info, result.url, result.ext);
                            } else {
                                console.error('[Shield] No audio streams found in response');
                                setIconState('visible');
                            }
                        } catch(e) {
                            console.error('[Shield] Parse error:', e);
                            setIconState('visible');
                        }
                    };
                    xhr.onerror = function() {
                        console.error('[Shield] XHR error');
                        setIconState('visible');
                    };
                    xhr.send(JSON.stringify({
                        videoId: vid,
                        context: { client: { clientName: clientName, clientVersion: clientVersion } },
                        contentCheckOk: true,
                        racyCheckOk: true
                    }));
                }
            }

            function startNativeDownload(vid, info, audioUrl, ext) {
                setIconState('downloading');
                try {
                    if (typeof ShieldDownloadBridge !== 'undefined') {
                        ShieldDownloadBridge.startDownload(vid, info.title, info.artist, info.coverUrl, info.durationMs, audioUrl, ext);
                    }
                } catch(e) {
                    console.error('[Shield] Bridge error:', e);
                    setIconState('visible');
                }
            }

            // ============================================
            // ICON STATE
            // ============================================
            function setIconState(state) {
                var btn = document.getElementById('shield-dl-btn');
                if (!btn) return;
                switch(state) {
                    case 'downloading':
                        btn.innerHTML = SVG_LOADING;
                        btn.setAttribute('data-state', 'downloading');
                        break;
                    case 'downloaded':
                        btn.innerHTML = SVG_CHECK;
                        btn.setAttribute('data-state', 'downloaded');
                        // Reset after 3s
                        setTimeout(function() { setIconState('visible'); }, 3000);
                        break;
                    default:
                        btn.innerHTML = SVG_DOWNLOAD;
                        btn.setAttribute('data-state', isPlayerActive() ? 'visible' : 'hidden');
                }
            }

            window.__shieldUpdateDownloadBtn = function(vid, state) { setIconState(state); };
            window.__shieldUpdateProgress = function(vid, pct) { /* tracked by DownloadTracker */ };

            function updateBtnForCurrent() {
                var vid = getCurrentVideoId(); if (!vid) return;
                try {
                    if (typeof ShieldDownloadBridge === 'undefined') return;
                    if (ShieldDownloadBridge.isDownloaded(vid)) setIconState('downloaded');
                    else if (ShieldDownloadBridge.isDownloading(vid)) setIconState('downloading');
                    else setIconState('visible');
                } catch(e) {}
            }

            // ============================================
            // INIT
            // ============================================
            var retries = 0;
            function tryInit() {
                if (ensureButton()) {
                    updateVisibility();
                    updateBtnForCurrent();
                    setInterval(function() { ensureButton(); updateVisibility(); updateBtnForCurrent(); }, 500);
                } else if (retries++ < 60) {
                    setTimeout(tryInit, 2000);
                }
            }

            if (document.body || document.documentElement) { tryInit(); }
            else { document.addEventListener('DOMContentLoaded', tryInit); setTimeout(tryInit, 1500); }

            console.log('[Shield] Download bridge v4');
        })();
    """.trimIndent()
}
