package com.jhoy.shield.offline

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jhoy.shield.R
import com.jhoy.shield.service.PlaybackService
import com.jhoy.shield.util.MaterialIcons
import java.io.File

/**
 * Native offline music player with YouTube Music-inspired dark theme.
 * Two tabs: Canciones (downloaded songs) and Descargando (download queue).
 */
class OfflinePlayerActivity : ComponentActivity() {

    private lateinit var db: OfflineDatabase
    private lateinit var downloader: AudioDownloader
    private var exoPlayer: ExoPlayer? = null
    private var songs: MutableList<OfflineSong> = mutableListOf()
    private var currentIndex = -1

    // UI refs
    private lateinit var songListView: ListView
    private lateinit var downloadsListView: ListView
    private lateinit var emptyView: TextView
    private lateinit var emptyDownloadsView: TextView
    private lateinit var songsContainer: FrameLayout
    private lateinit var downloadsContainer: FrameLayout
    private lateinit var miniPlayerLayout: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var progressBar: SeekBar
    private lateinit var txtCurrent: TextView
    private lateinit var txtDuration: TextView
    private lateinit var songCountText: TextView
    private lateinit var tabSongs: TextView
    private lateinit var tabDownloads: TextView
    private lateinit var tabIndicatorSongs: View
    private lateinit var tabIndicatorDownloads: View

    // Colors
    private val bgColor = Color.parseColor("#0F0F0F")
    private val surfaceColor = Color.parseColor("#1D1D1D")
    private val accentColor = Color.parseColor("#FF0000")
    private val textPrimary = Color.WHITE
    private val textSecondary = Color.parseColor("#AAAAAA")
    private val miniPlayerBg = Color.parseColor("#282828")
    private val errorColor = Color.parseColor("#FF4444")
    private val successColor = Color.parseColor("#00CC66")
    private val warningColor = Color.parseColor("#FFAA00")

    private val trackerListener: () -> Unit = { refreshDownloadsList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = OfflineDatabase(this)
        downloader = AudioDownloader(this)
        songs = db.getAllSongs().toMutableList()

        setContentView(buildUI())
        setupExoPlayer()
        setupBackNavigation()
        refreshList()
        refreshDownloadsList()
        DownloadTracker.addListener(trackerListener)
    }

    // ===== UI Construction =====

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        root.addView(buildHeader())
        root.addView(buildTabs())
        root.addView(buildActionBar())

        // Songs content
        songListView = ListView(this).apply {
            setBackgroundColor(bgColor)
            divider = null; dividerHeight = 0
            setOnItemClickListener { _, _, pos, _ -> playSong(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> showDeleteDialog(pos); true }
        }
        emptyView = TextView(this).apply {
            text = "No hay canciones descargadas\n\nUsa el botón ⬇ en YouTube Music\npara descargar canciones"
            setTextColor(textSecondary); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(32), dp(64), dp(32), dp(64))
        }
        songsContainer = FrameLayout(this).apply {
            addView(songListView, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Downloads content
        downloadsListView = ListView(this).apply {
            setBackgroundColor(bgColor)
            divider = null; dividerHeight = 0
        }
        emptyDownloadsView = TextView(this).apply {
            text = "No hay descargas en curso"
            setTextColor(textSecondary); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(32), dp(64), dp(32), dp(64))
        }
        downloadsContainer = FrameLayout(this).apply {
            addView(downloadsListView, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyDownloadsView, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            visibility = View.GONE
        }

        val contentWrapper = FrameLayout(this).apply {
            addView(songsContainer, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(downloadsContainer, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(contentWrapper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        miniPlayerLayout = buildMiniPlayer()
        miniPlayerLayout.visibility = View.GONE
        root.addView(miniPlayerLayout)

        return root
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surfaceColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(16), dp(12))

            val backBtn = ImageButton(this@OfflinePlayerActivity).apply {
                setImageDrawable(MaterialIcons.arrowBackStroke(textPrimary, dp(24)))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { finish() }
            }
            addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

            val titleLayout = LinearLayout(this@OfflinePlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            val headerTitle = TextView(this@OfflinePlayerActivity).apply {
                text = "Descargas"; setTextColor(textPrimary); textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            titleLayout.addView(headerTitle)
            songCountText = TextView(this@OfflinePlayerActivity).apply {
                text = "${songs.size} canciones"; setTextColor(textSecondary); textSize = 13f
            }
            titleLayout.addView(songCountText)
            addView(titleLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildTabs(): LinearLayout {
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(16), 0, dp(16), 0)
        }

        // Tab: Canciones
        val tab1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { switchTab(0) }
        }
        tabSongs = TextView(this).apply {
            text = "Canciones"; setTextColor(textPrimary); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        tab1.addView(tabSongs)
        tabIndicatorSongs = View(this).apply { setBackgroundColor(textPrimary) }
        tab1.addView(tabIndicatorSongs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = dp(8)
        })
        tabBar.addView(tab1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Tab: Descargando
        val tab2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { switchTab(1) }
        }
        tabDownloads = TextView(this).apply {
            text = "Descargando"; setTextColor(textSecondary); textSize = 14f
            gravity = Gravity.CENTER
        }
        tab2.addView(tabDownloads)
        tabIndicatorDownloads = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        tab2.addView(tabIndicatorDownloads, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = dp(8)
        })
        tabBar.addView(tab2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return tabBar
    }

    private var currentTab = 0

    private fun switchTab(tab: Int) {
        currentTab = tab
        if (tab == 0) {
            songsContainer.visibility = View.VISIBLE
            downloadsContainer.visibility = View.GONE
            tabSongs.setTextColor(textPrimary)
            tabSongs.setTypeface(tabSongs.typeface, android.graphics.Typeface.BOLD)
            tabDownloads.setTextColor(textSecondary)
            tabDownloads.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabIndicatorSongs.setBackgroundColor(textPrimary)
            tabIndicatorDownloads.setBackgroundColor(Color.TRANSPARENT)
        } else {
            songsContainer.visibility = View.GONE
            downloadsContainer.visibility = View.VISIBLE
            tabSongs.setTextColor(textSecondary)
            tabSongs.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabDownloads.setTextColor(textPrimary)
            tabDownloads.setTypeface(tabDownloads.typeface, android.graphics.Typeface.BOLD)
            tabIndicatorSongs.setBackgroundColor(Color.TRANSPARENT)
            tabIndicatorDownloads.setBackgroundColor(textPrimary)
            refreshDownloadsList()
        }
    }

    private fun buildActionBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))

            val playAllBtn = LinearLayout(this@OfflinePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
                isClickable = true; isFocusable = true
                setOnClickListener { playAll() }
            }
            val playIcon = ImageView(this@OfflinePlayerActivity).apply {
                setImageDrawable(MaterialIcons.play(Color.BLACK, dp(16)))
                setPadding(0, 0, dp(6), 0)
            }
            playAllBtn.addView(playIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
            playAllBtn.addView(TextView(this@OfflinePlayerActivity).apply {
                text = "Reproducir"; setTextColor(Color.BLACK); textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(playAllBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8), 0)
            })

            val shuffleBtn = LinearLayout(this@OfflinePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#2A2A2A"))
                    setStroke(1, Color.parseColor("#444444"))
                }
                isClickable = true; isFocusable = true
                setOnClickListener { playAllShuffled() }
            }
            val shuffleIcon = ImageView(this@OfflinePlayerActivity).apply {
                setImageDrawable(MaterialIcons.shuffle(Color.WHITE, dp(16)))
                setPadding(0, 0, dp(6), 0)
            }
            shuffleBtn.addView(shuffleIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
            shuffleBtn.addView(TextView(this@OfflinePlayerActivity).apply {
                text = "Aleatorio"; setTextColor(Color.WHITE); textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(shuffleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun buildMiniPlayer(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(miniPlayerBg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        miniCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(surfaceColor)
        }
        miniCover.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(surfaceColor) }
        miniCover.clipToOutline = true
        row.addView(miniCover, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(10), 0) })

        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = TextView(this).apply {
            setTextColor(textPrimary); textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniArtist = TextView(this).apply {
            setTextColor(textSecondary); textSize = 12f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLayout.addView(miniTitle); infoLayout.addView(miniArtist)
        row.addView(infoLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnPrev = makeControlButton(MaterialIcons.skipPrevious(textPrimary, dp(24))) { playPrevious() }
        btnPlayPause = makeControlButton(MaterialIcons.play(textPrimary, dp(24))) { togglePlayPause() }
        btnNext = makeControlButton(MaterialIcons.skipNext(textPrimary, dp(24))) { playNext() }
        row.addView(btnPrev, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(btnPlayPause, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) })
        row.addView(btnNext, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(row)

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        txtCurrent = TextView(this).apply { setTextColor(textSecondary); textSize = 10f; text = "0:00" }
        progressRow.addView(txtCurrent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progressBar = SeekBar(this).apply {
            max = 1000; progress = 0
            @Suppress("DEPRECATION")
            progressDrawable.setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
            @Suppress("DEPRECATION")
            thumb?.setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) exoPlayer?.seekTo((exoPlayer!!.duration * p / 1000))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        progressRow.addView(progressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        txtDuration = TextView(this).apply { setTextColor(textSecondary); textSize = 10f; text = "0:00" }
        progressRow.addView(txtDuration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressRow)
        return root
    }

    private fun makeControlButton(icon: android.graphics.drawable.Drawable, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageDrawable(icon); setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { onClick() }
        }
    }

    // ===== ExoPlayer =====

    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    btnPlayPause.setImageDrawable(
                        if (playing) MaterialIcons.pause(textPrimary, dp(24))
                        else MaterialIcons.play(textPrimary, dp(24))
                    )
                    sendMetadataToService(playing)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (currentIndex >= 0 && currentIndex < songs.size) {
                        val newIndex = exoPlayer?.currentMediaItemIndex ?: currentIndex
                        if (newIndex != currentIndex) {
                            currentIndex = newIndex; updateMiniPlayer(); refreshListHighlight()
                        }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        btnPlayPause.setImageDrawable(MaterialIcons.play(textPrimary, dp(24)))
                    }
                }
            })
        }

        val handler = android.os.Handler(mainLooper)
        val updater = object : Runnable {
            override fun run() { updateProgress(); handler.postDelayed(this, 500) }
        }
        handler.post(updater)
    }

    // ===== Playback =====

    private fun playSong(index: Int) {
        if (index < 0 || index >= songs.size) return
        currentIndex = index
        val mediaItems = songs.map { MediaItem.fromUri(Uri.fromFile(File(it.filePath))) }
        exoPlayer?.apply { shuffleModeEnabled = false; setMediaItems(mediaItems, index, 0); prepare(); play() }
        miniPlayerLayout.visibility = View.VISIBLE; updateMiniPlayer(); refreshListHighlight()
        val intent = Intent(this, PlaybackService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun playAll() { if (songs.isNotEmpty()) playSong(0) }

    private fun playAllShuffled() {
        if (songs.isEmpty()) return
        currentIndex = 0
        val mediaItems = songs.map { MediaItem.fromUri(Uri.fromFile(File(it.filePath))) }
        exoPlayer?.apply { shuffleModeEnabled = true; setMediaItems(mediaItems, 0, 0); prepare(); play() }
        miniPlayerLayout.visibility = View.VISIBLE; updateMiniPlayer(); refreshListHighlight()
        val intent = Intent(this, PlaybackService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }

    private fun playNext() {
        exoPlayer?.let {
            if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); currentIndex = it.currentMediaItemIndex; updateMiniPlayer(); refreshListHighlight() }
        }
    }

    private fun playPrevious() {
        exoPlayer?.let {
            if (it.hasPreviousMediaItem()) { it.seekToPreviousMediaItem(); currentIndex = it.currentMediaItemIndex; updateMiniPlayer(); refreshListHighlight() }
        }
    }

    private fun updateMiniPlayer() {
        if (currentIndex < 0 || currentIndex >= songs.size) return
        val song = songs[currentIndex]
        miniTitle.text = song.title; miniArtist.text = song.artist
        if (song.coverPath.isNotBlank() && File(song.coverPath).exists()) {
            try { miniCover.setImageBitmap(BitmapFactory.decodeFile(song.coverPath)) }
            catch (_: Exception) { miniCover.setImageDrawable(MaterialIcons.musicNote(textSecondary, dp(24))) }
        } else { miniCover.setImageDrawable(MaterialIcons.musicNote(textSecondary, dp(24))) }
    }

    private fun updateProgress() {
        val player = exoPlayer ?: return
        if (player.duration <= 0) return
        progressBar.progress = ((player.currentPosition * 1000) / player.duration).toInt()
        txtCurrent.text = formatTime(player.currentPosition)
        txtDuration.text = formatTime(player.duration)
    }

    private fun sendMetadataToService(isPlaying: Boolean) {
        if (currentIndex < 0 || currentIndex >= songs.size) return
        val song = songs[currentIndex]
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = "UPDATE_METADATA"
            putExtra("title", song.title); putExtra("artist", song.artist)
            putExtra("coverUrl", song.coverUrl); putExtra("isPlaying", isPlaying)
            putExtra("positionMs", exoPlayer?.currentPosition ?: 0L)
            putExtra("durationMs", exoPlayer?.duration ?: song.durationMs)
            putExtra("isOffline", true)
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    // ===== Songs List =====

    private fun refreshList() {
        songs = db.getAllSongs().toMutableList()
        songCountText.text = "${songs.size} canciones"
        emptyView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        songListView.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE

        songListView.adapter = object : BaseAdapter() {
            override fun getCount() = songs.size
            override fun getItem(pos: Int) = songs[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val song = songs[pos]
                val playing = pos == currentIndex

                val row = LinearLayout(this@OfflinePlayerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setBackgroundColor(if (playing) Color.parseColor("#1A1A1A") else bgColor)
                }

                val cover = ImageView(this@OfflinePlayerActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(surfaceColor) }
                    clipToOutline = true
                }
                if (song.coverPath.isNotBlank() && File(song.coverPath).exists()) {
                    try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        cover.setImageBitmap(BitmapFactory.decodeFile(song.coverPath, opts))
                    } catch (_: Exception) { cover.setImageDrawable(MaterialIcons.musicNote(textSecondary, dp(24))) }
                } else { cover.setImageDrawable(MaterialIcons.musicNote(textSecondary, dp(24))) }
                row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(12), 0) })

                val info = LinearLayout(this@OfflinePlayerActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this@OfflinePlayerActivity).apply {
                    text = song.title; setTextColor(if (playing) accentColor else textPrimary)
                    textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    if (playing) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                info.addView(TextView(this@OfflinePlayerActivity).apply {
                    text = "${song.artist}  •  ${formatTime(song.durationMs)}"
                    setTextColor(textSecondary); textSize = 13f; maxLines = 1
                })
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (playing) {
                    row.addView(TextView(this@OfflinePlayerActivity).apply {
                        text = "♪"; setTextColor(accentColor); textSize = 18f; setPadding(dp(8), 0, 0, 0)
                    })
                }
                return row
            }
        }
    }

    private fun refreshListHighlight() {
        (songListView.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    // ===== Downloads List =====

    private fun refreshDownloadsList() {
        val items = DownloadTracker.getAll()

        // Update tab badge
        val activeCount = DownloadTracker.activeCount()
        tabDownloads.text = if (activeCount > 0) "Descargando ($activeCount)" else "Descargando"

        emptyDownloadsView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        downloadsListView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        downloadsListView.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val item = items[pos]

                val row = LinearLayout(this@OfflinePlayerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setBackgroundColor(bgColor)
                }

                // Status icon
                val statusIcon = ImageView(this@OfflinePlayerActivity).apply {
                    val (drawable, _) = when (item.status) {
                        DownloadTracker.Status.QUEUED -> Pair(MaterialIcons.downloading(textSecondary, dp(20)), textSecondary)
                        DownloadTracker.Status.DOWNLOADING -> Pair(MaterialIcons.downloading(warningColor, dp(20)), warningColor)
                        DownloadTracker.Status.COMPLETED -> Pair(MaterialIcons.check(successColor, dp(20)), successColor)
                        DownloadTracker.Status.ERROR -> Pair(MaterialIcons.error(errorColor, dp(20)), errorColor)
                    }
                    setImageDrawable(drawable)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                row.addView(statusIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(0, 0, dp(12), 0) })

                // Info
                val info = LinearLayout(this@OfflinePlayerActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this@OfflinePlayerActivity).apply {
                    text = item.title; setTextColor(textPrimary); textSize = 14f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })

                val statusText = when (item.status) {
                    DownloadTracker.Status.QUEUED -> "En cola..."
                    DownloadTracker.Status.DOWNLOADING -> "Descargando ${item.progress}%"
                    DownloadTracker.Status.COMPLETED -> "✓ Completada"
                    DownloadTracker.Status.ERROR -> "✗ ${item.errorMessage}"
                }
                val statusColor = when (item.status) {
                    DownloadTracker.Status.QUEUED -> textSecondary
                    DownloadTracker.Status.DOWNLOADING -> warningColor
                    DownloadTracker.Status.COMPLETED -> successColor
                    DownloadTracker.Status.ERROR -> errorColor
                }
                info.addView(TextView(this@OfflinePlayerActivity).apply {
                    text = "${item.artist}  •  $statusText"
                    setTextColor(statusColor); textSize = 12f; maxLines = 1
                })

                // Progress bar for downloading items
                if (item.status == DownloadTracker.Status.DOWNLOADING || item.status == DownloadTracker.Status.QUEUED) {
                    val pb = ProgressBar(this@OfflinePlayerActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = item.progress
                        @Suppress("DEPRECATION")
                        progressDrawable.setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
                        setPadding(0, dp(4), 0, 0)
                    }
                    info.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                        topMargin = dp(4)
                    })
                }

                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                // Dismiss button (for completed/error)
                if (item.status == DownloadTracker.Status.COMPLETED || item.status == DownloadTracker.Status.ERROR) {
                    val dismissBtn = ImageButton(this@OfflinePlayerActivity).apply {
                        setImageDrawable(MaterialIcons.close(textSecondary, dp(18)))
                        setBackgroundColor(Color.TRANSPARENT)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        setOnClickListener {
                            DownloadTracker.dismiss(item.videoId)
                            if (item.status == DownloadTracker.Status.COMPLETED) refreshList()
                        }
                    }
                    row.addView(dismissBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
                }

                return row
            }
        }
    }

    // ===== Dialogs =====

    @Suppress("DEPRECATION")
    private fun showDeleteDialog(position: Int) {
        val song = songs[position]
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Eliminar descarga")
            .setMessage("¿Eliminar \"${song.title}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                if (position == currentIndex) {
                    exoPlayer?.stop(); exoPlayer?.clearMediaItems()
                    currentIndex = -1; miniPlayerLayout.visibility = View.GONE
                }
                downloader.deleteSong(song.videoId); refreshList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ===== Helpers =====

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000; val min = totalSec / 60; val sec = totalSec % 60
        return "$min:${"%02d".format(sec)}"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun lp(w: Int, h: Int) = FrameLayout.LayoutParams(w, h)

    override fun onResume() {
        super.onResume()
        refreshList()
        refreshDownloadsList()
    }

    override fun onDestroy() {
        DownloadTracker.removeListener(trackerListener)
        exoPlayer?.release(); exoPlayer = null
        super.onDestroy()
    }
}
