package com.jhoy.shield.offline

import android.os.Handler
import android.os.Looper

/**
 * Singleton tracker for download state, shared between DownloadBridge and OfflinePlayerActivity.
 * Provides a live list of downloads with their status.
 */
object DownloadTracker {

    enum class Status { QUEUED, DOWNLOADING, COMPLETED, ERROR }

    data class DownloadItem(
        val videoId: String,
        val title: String,
        val artist: String,
        var status: Status = Status.QUEUED,
        var progress: Int = 0,
        var errorMessage: String = "",
        val startedAt: Long = System.currentTimeMillis()
    )

    private val items = mutableListOf<DownloadItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    fun enqueue(videoId: String, title: String, artist: String) {
        // Don't duplicate
        if (items.any { it.videoId == videoId }) return
        items.add(0, DownloadItem(videoId, title, artist))
        notifyListeners()
    }

    fun updateProgress(videoId: String, percent: Int) {
        items.find { it.videoId == videoId }?.let {
            it.status = Status.DOWNLOADING
            it.progress = percent
            notifyListeners()
        }
    }

    fun markCompleted(videoId: String) {
        items.find { it.videoId == videoId }?.let {
            it.status = Status.COMPLETED
            it.progress = 100
            notifyListeners()
        }
    }

    fun markError(videoId: String, error: String) {
        items.find { it.videoId == videoId }?.let {
            it.status = Status.ERROR
            it.errorMessage = error
            notifyListeners()
        }
    }

    fun dismiss(videoId: String) {
        items.removeAll { it.videoId == videoId }
        notifyListeners()
    }

    fun getAll(): List<DownloadItem> = items.toList()

    fun hasActiveDownloads(): Boolean =
        items.any { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }

    fun activeCount(): Int =
        items.count { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }
}
