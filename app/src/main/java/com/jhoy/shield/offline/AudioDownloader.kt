package com.jhoy.shield.offline

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads audio files for offline playback.
 *
 * Receives a direct streaming URL (extracted by JS from the InnerTube player response)
 * and downloads the audio to local storage.
 */
class AudioDownloader(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val db = OfflineDatabase(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    interface Callback {
        fun onProgress(videoId: String, percent: Int)
        fun onComplete(videoId: String, song: OfflineSong)
        fun onError(videoId: String, error: String)
    }

    fun isDownloading(videoId: String): Boolean = activeDownloads.containsKey(videoId)

    fun isDownloaded(videoId: String): Boolean = db.isDownloaded(videoId)

    fun download(
        videoId: String,
        title: String,
        artist: String,
        coverUrl: String,
        durationMs: Long,
        audioUrl: String,
        fileExtension: String,
        callback: Callback
    ) {
        if (activeDownloads.containsKey(videoId) || db.isDownloaded(videoId)) return
        activeDownloads[videoId] = true

        executor.execute {
            try {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                    "shield"
                )
                dir.mkdirs()

                // Download audio
                val audioFile = File(dir, "$videoId.$fileExtension")
                downloadFile(audioUrl, audioFile) { percent ->
                    mainHandler.post { callback.onProgress(videoId, percent) }
                }

                // Download cover art
                val coverFile = File(dir, "$videoId.jpg")
                if (coverUrl.isNotBlank()) {
                    try {
                        downloadFile(coverUrl, coverFile) { }
                    } catch (_: Exception) { /* cover is optional */ }
                }

                val song = OfflineSong(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    coverUrl = coverUrl,
                    filePath = audioFile.absolutePath,
                    coverPath = if (coverFile.exists()) coverFile.absolutePath else "",
                    durationMs = durationMs,
                    downloadedAt = System.currentTimeMillis(),
                    fileSize = audioFile.length()
                )
                db.insertSong(song)
                activeDownloads.remove(videoId)
                mainHandler.post { callback.onComplete(videoId, song) }
            } catch (e: Exception) {
                activeDownloads.remove(videoId)
                mainHandler.post { callback.onError(videoId, e.message ?: "Error desconocido") }
            }
        }
    }

    fun deleteSong(videoId: String) {
        val song = db.getSongById(videoId) ?: return
        File(song.filePath).delete()
        if (song.coverPath.isNotBlank()) File(song.coverPath).delete()
        db.deleteSong(videoId)
    }

    private fun downloadFile(urlStr: String, file: File, onProgress: (Int) -> Unit) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 15; SM-S938B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36")

        val totalSize = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        onProgress(((downloaded * 100) / totalSize).toInt())
                    }
                }
            }
        }
    }
}
