package com.jhoy.shield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jhoy.shield.MainActivity
import com.jhoy.shield.R
import com.jhoy.shield.media.WebViewMediaBridge

/**
 * Foreground service for background audio playback.
 *
 * Manages a MediaSession that integrates with:
 * - Samsung One UI media notification (lock screen + shade)
 * - Bluetooth headset controls
 * - Android Auto / smart devices
 *
 * Dual-mode playback:
 * - WebView mode: delegates play/pause/seek to YouTube Music via JS bridge
 * - Native mode: uses ExoPlayer with a direct audio stream URL for reliable
 *   background playback (activated when the app goes to background)
 *
 * Optimized for Samsung S25 Ultra (One UI 7, Android 15).
 */
class PlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle = "YT Jhoy"
    private var currentArtist = "YouTube Music"
    private var currentCoverUrl = ""
    private var currentBitmap: android.graphics.Bitmap? = null
    private var currentPosition = 0L
    private var currentDuration = -1L
    private var isPlaying = false

    // Native ExoPlayer for background playback
    private var nativePlayer: ExoPlayer? = null
    private var isNativePlaybackActive = false

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "shield_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.jhoy.shield.ACTION_PLAY"
        const val ACTION_PAUSE = "com.jhoy.shield.ACTION_PAUSE"
        const val ACTION_NEXT = "com.jhoy.shield.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.jhoy.shield.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.jhoy.shield.ACTION_STOP"
        const val ACTION_START_NATIVE = "com.jhoy.shield.ACTION_START_NATIVE"
        const val ACTION_STOP_NATIVE = "com.jhoy.shield.ACTION_STOP_NATIVE"

        var mediaBridge: WebViewMediaBridge? = null

        /** Last known stream URL for fallback ExoPlayer start */
        @Volatile
        var lastStreamUrl: String? = null
        @Volatile
        var lastStreamPositionMs: Long = 0L

        /** Last known position of the native player when it was stopped */
        @Volatile
        var lastNativePositionMs: Long = -1L

        /** Set by MainActivity to track foreground/background state */
        @Volatile
        var isAppInBackground = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        acquireWakeLock()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                if (isNativePlaybackActive) {
                    nativePlayer?.play()
                    updatePlaybackState(true)
                } else if (isAppInBackground && lastStreamUrl != null) {
                    // WebView is suspended — start ExoPlayer with last known stream
                    startNativePlayback(lastStreamUrl!!, lastStreamPositionMs)
                } else {
                    mediaBridge?.executePlay()
                    updatePlaybackState(true)
                }
            }
            ACTION_PAUSE -> {
                if (isNativePlaybackActive) {
                    nativePlayer?.pause()
                } else {
                    mediaBridge?.executePause()
                }
                updatePlaybackState(false)
            }
            ACTION_NEXT -> {
                if (isNativePlaybackActive) {
                    stopNativePlayback()
                    mediaBridge?.executeUnmuteAndNext()
                } else {
                    mediaBridge?.executeNext()
                }
            }
            ACTION_PREVIOUS -> {
                if (isNativePlaybackActive) {
                    stopNativePlayback()
                    mediaBridge?.executeUnmuteAndPrevious()
                } else {
                    mediaBridge?.executePrevious()
                }
            }
            "UPDATE_METADATA" -> {
                // During native playback, ignore WebView state updates to avoid conflicts
                if (!isNativePlaybackActive) {
                    val title = intent.getStringExtra("title") ?: ""
                    val artist = intent.getStringExtra("artist") ?: ""
                    val coverUrl = intent.getStringExtra("coverUrl") ?: ""
                    val playing = intent.getBooleanExtra("isPlaying", false)
                    val positionMs = intent.getLongExtra("positionMs", 0L)
                    val durationMs = intent.getLongExtra("durationMs", 0L)
                    updateMetadata(title, artist, coverUrl, playing, positionMs, durationMs)
                }
            }
            ACTION_START_NATIVE -> {
                val streamUrl = intent.getStringExtra("streamUrl") ?: return START_STICKY
                val positionMs = intent.getLongExtra("positionMs", 0L)
                startNativePlayback(streamUrl, positionMs)
            }
            ACTION_STOP_NATIVE -> {
                stopNativePlayback()
            }
            ACTION_STOP -> {
                stopNativePlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Initial start
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopNativePlayback()
        mediaSession.isActive = false
        mediaSession.release()
        releaseWakeLock()
        super.onDestroy()
    }

    // ===== Native ExoPlayer Background Playback =====

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startNativePlayback(streamUrl: String, positionMs: Long) {
        // Release any existing player
        nativePlayer?.release()

        Log.d(TAG, "Starting native playback at ${positionMs}ms")
        isNativePlaybackActive = true
        lastStreamUrl = streamUrl
        lastStreamPositionMs = positionMs

        nativePlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            seekTo(positionMs)

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        Log.d(TAG, "Native playback ended")
                        stopNativePlayback()
                        // Unmute WebView so it can continue with next track via autoplay
                        mediaBridge?.executeUnmute()
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    this@PlaybackService.isPlaying = playing
                    updatePlaybackState(playing)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error, falling back to WebView", error)
                    stopNativePlayback()
                    // Unmute WebView as fallback
                    mediaBridge?.executeUnmute()
                }
            })

            prepare()
            play()
        }

        updatePlaybackState(true)
    }

    private fun stopNativePlayback() {
        nativePlayer?.let { player ->
            lastNativePositionMs = player.currentPosition
            Log.d(TAG, "Stopping native playback at ${lastNativePositionMs}ms")
            player.stop()
            player.release()
        }
        nativePlayer = null
        isNativePlaybackActive = false
    }

    // ===== Public methods called from WebViewMediaBridge =====

    fun updateMetadata(title: String, artist: String, coverUrl: String, playing: Boolean, positionMs: Long, durationMs: Long) {
        currentTitle = title.ifBlank { "YT Jhoy" }
        currentArtist = artist.ifBlank { "YouTube Music" }
        isPlaying = playing
        currentPosition = positionMs
        currentDuration = durationMs

        if (coverUrl.isNotBlank() && coverUrl != currentCoverUrl) {
            currentCoverUrl = coverUrl
            Thread {
                try {
                    val url = java.net.URL(coverUrl)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(url.openConnection().inputStream)
                    currentBitmap = bitmap
                    applyMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        applyMetadata()
    }

    private fun applyMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "YouTube Music")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)

        currentBitmap?.let {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        mediaSession.setMetadata(metadata.build())
        updatePlaybackState(isPlaying)
    }

    // ===== Private setup =====

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ShieldMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (isNativePlaybackActive) {
                        nativePlayer?.play()
                    } else {
                        mediaBridge?.executePlay()
                    }
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    if (isNativePlaybackActive) {
                        nativePlayer?.pause()
                    } else {
                        mediaBridge?.executePause()
                    }
                    updatePlaybackState(false)
                }

                override fun onSkipToNext() {
                    if (isNativePlaybackActive) {
                        stopNativePlayback()
                        mediaBridge?.executeUnmuteAndNext()
                    } else {
                        mediaBridge?.executeNext()
                    }
                }

                override fun onSkipToPrevious() {
                    if (isNativePlaybackActive) {
                        stopNativePlayback()
                        mediaBridge?.executeUnmuteAndPrevious()
                    } else {
                        mediaBridge?.executePrevious()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    if (isNativePlaybackActive) {
                        nativePlayer?.seekTo(pos)
                        currentPosition = pos
                    } else {
                        mediaBridge?.executeSeek(pos)
                        currentPosition = pos
                    }
                    applyMetadata()
                }

                override fun onStop() {
                    if (isNativePlaybackActive) {
                        stopNativePlayback()
                    }
                    mediaBridge?.executePause()
                    stopSelf()
                }
            })

            isActive = true
        }

        // Set initial playback state
        updatePlaybackState(true)
    }

    private fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        // When native player is active, use its position for accurate progress
        val pos = if (isNativePlaybackActive) {
            nativePlayer?.currentPosition ?: currentPosition
        } else {
            currentPosition
        }

        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                pos,
                if (playing) 1.0f else 0f
            )
            .build()

        mediaSession.setPlaybackState(state)

        // Update notification
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reproducción en segundo plano",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controles de reproducción de Shield"
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Return to app intent
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val returnPending = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Media control intents
        val prevPending = buildActionPending(ACTION_PREVIOUS, 1)
        val playPending = buildActionPending(ACTION_PLAY, 2)
        val pausePending = buildActionPending(ACTION_PAUSE, 3)
        val nextPending = buildActionPending(ACTION_NEXT, 4)
        val stopPending = buildActionPending(ACTION_STOP, 5)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("YT Jhoy")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(returnPending)
            .setDeleteIntent(stopPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, next
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPending)
            )

        // Action: Previous
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous, "Anterior", prevPending
            ).build()
        )

        // Action: Play or Pause
        if (isPlaying) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_pause, "Pausar", pausePending
                ).build()
            )
        } else {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_play, "Reproducir", playPending
                ).build()
            )
        }

        // Action: Next
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next, "Siguiente", nextPending
            ).build()
        )

        return builder.build()
    }

    private fun buildActionPending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Shield::PlaybackWakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
