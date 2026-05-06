package com.jhoy.shield.media

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * Custom WebView that prevents Chromium from pausing media when the Activity
 * goes to background.
 *
 * Android's WebView internally pauses media playback when it receives
 * [onWindowVisibilityChanged] with [View.GONE] or [View.INVISIBLE].
 * This happens at the native Chromium level, completely bypassing any
 * JavaScript-level Visibility API spoofing.
 *
 * By overriding [onWindowVisibilityChanged] to always report [View.VISIBLE],
 * Chromium's renderer keeps media playing even when the hosting Activity
 * is not in the foreground.
 *
 * This is the same technique used by Brave, Kiwi Browser, and other
 * background-capable WebView-based browsers.
 */
class BackgroundPlayWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /**
     * Always report VISIBLE to Chromium's renderer, preventing it from
     * pausing video/audio when the Activity goes to background.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
