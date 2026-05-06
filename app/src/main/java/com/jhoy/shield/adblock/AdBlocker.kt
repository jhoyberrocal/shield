package com.jhoy.shield.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Core ad blocker engine for Shield.
 *
 * Blocks ads by matching requested URLs against a curated list
 * of known ad/tracking domains and URL patterns.
 */
object AdBlocker {

    private val EMPTY_RESPONSE by lazy {
        WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /** Known ad and tracking domains (O(1) HashSet for instant lookups) */
    private val AD_DOMAINS = hashSetOf(
        // Google Ads
        "pagead2.googlesyndication.com",
        "googleads.g.doubleclick.net",
        "googleadservices.com",
        "adservice.google.com",
        "adservice.google.com.co",
        "www.googleadservices.com",
        "partner.googleadservices.com",
        "tpc.googlesyndication.com",
        "pagead2.googleadservices.com",
        "adclick.g.doubleclick.net",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "m.doubleclick.net",
        "mediavisor.doubleclick.net",
        "ade.googlesyndication.com",

        // YouTube Ads
        "ads.youtube.com",
        "youtube.cleverads.vn",

        // General ad networks
        "ad.turn.com",
        "ad.admitad.com",
        "ads.pubmatic.com",
        "ads.yahoo.com",
        "ads.facebook.com",
        "ads.twitter.com",
        "ads.linkedin.com",
        "adsserver.yadro.ru",
        "adserver.unityads.unity3d.com",
        "ads.api.vungle.com",

        // Tracking
        "www.google-analytics.com",
        "ssl.google-analytics.com",
        "google-analytics.com",
        "analytics.google.com",
        "analyticsengine.s3.amazonaws.com",
        "stats.g.doubleclick.net",
        "cm.g.doubleclick.net",
        "pixel.facebook.com",
        "pixel.adsafeprotected.com",
        "t.co",
        "tr.snapchat.com",
        "bat.bing.com",
        "sb.scorecardresearch.com",
        "b.scorecardresearch.com",

        // Ad exchanges
        "match.adsrvr.org",
        "sync.outbrain.com",
        "us-u.openx.net",
        "prebid.adnxs.com",
        "ib.adnxs.com",
        "acdn.adnxs.com",
        "secure.adnxs.com",
        "adnxs.com",
        "openx.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "simage2.pubmatic.com",

        // Telemetry & Fingerprinting
        "cdn.amplitude.com",
        "api.amplitude.com",
        "cdn.branch.io",
        "app.adjust.com",
        "settings.crashlytics.com",
        "e.crashlytics.com",
        "pagead-googlehosted.l.google.com",
        "play.google.com/log",

        // Pop-ups & Overlays
        "imasdk.googleapis.com",
        "tpc.googlesyndication.com",
    )

    /** URL path patterns that indicate ad content */
    private val AD_URL_PATTERNS = listOf(
        "adformat=",
        "ad_type=",
        "ad_v=",
        "ad_video_id=",
        "ad_preroll=",
        "/pagead/",
        "/ptracking",
        "/ads/",
        "/ad_status",
        "/api/stats/ads",
        "/api/ads/",
        "/get_midroll_",
        "google_ads",
        "doubleclick",
        "googlesyndication",
        "googleadservices",
        "get_video_info.*ad",
        "cpn_ads",
        "sw.js",
        "service-worker.js"
    )

    /**
     * Check if a URL should be blocked.
     * Whitelists essential YouTube API endpoints needed for playback & downloads.
     */
    fun shouldBlock(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // NEVER block essential playback/download APIs
        if (lowerUrl.contains("/youtubei/v1/player") ||
            lowerUrl.contains("/youtubei/v1/next") ||
            lowerUrl.contains("/youtubei/v1/browse") ||
            lowerUrl.contains("/youtubei/v1/search") ||
            lowerUrl.contains("/youtubei/v1/music")) {
            return false
        }

        return matchesDomain(lowerUrl) || matchesPattern(lowerUrl)
    }

    /**
     * Devuelve una respuesta simulada (Error Activo 403).
     * Esto imita el modo de operación de Brave bloqueando limpiamente a nivel servidor
     * sin congelar el cliente que espera un JSON 200 limpio.
     */
    fun createEmptyResponse(url: String): WebResourceResponse {
        val mimeType = if (url.contains("youtubei/v1") || url.contains(".json")) "application/json" else "text/plain"
        return WebResourceResponse(
            mimeType,
            "utf-8",
            403, "Blocked by Shield",
            null,
            ByteArrayInputStream("{}".toByteArray())
        )
    }

    private fun matchesDomain(url: String): Boolean {
        // Examen preciso (Similitud nativa con uBlock)
        val host = try {
            java.net.URL(url).host.lowercase()
        } catch (e: Exception) {
            ""
        }
        
        // Exact match o subdominio
        var currentHost = host
        while (currentHost.isNotEmpty()) {
            if (AD_DOMAINS.contains(currentHost)) return true
            val dotIndex = currentHost.indexOf('.')
            if (dotIndex == -1) break
            currentHost = currentHost.substring(dotIndex + 1)
        }
        return false
    }

    private fun matchesPattern(url: String): Boolean {
        return AD_URL_PATTERNS.any { pattern ->
            url.contains(pattern)
        }
    }
}
