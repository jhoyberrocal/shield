package com.jhoy.shield.offline

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences helper for offline mode state.
 */
class OfflinePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shield_offline", Context.MODE_PRIVATE)

    var isOfflineMode: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()

    companion object {
        private const val KEY_OFFLINE_MODE = "offline_mode"
    }
}
