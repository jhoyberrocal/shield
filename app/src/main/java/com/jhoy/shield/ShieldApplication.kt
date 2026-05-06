package com.jhoy.shield

import android.app.Application

/**
 * Custom Application class for Shield.
 *
 * Use this class for app-wide initialization such as:
 * - Dependency injection setup
 * - Logging framework initialization
 * - Crash reporting setup
 */
class ShieldApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize app-wide dependencies here
    }
}
