package com.jhoy.shield.offline

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class OfflineSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val filePath: String,
    val coverPath: String,
    val durationMs: Long,
    val downloadedAt: Long,
    val fileSize: Long
)

class OfflineDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "shield_offline.db"
        private const val DB_VERSION = 1
        private const val T = "songs"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T (
                video_id    TEXT PRIMARY KEY,
                title       TEXT NOT NULL,
                artist      TEXT NOT NULL,
                cover_url   TEXT,
                file_path   TEXT NOT NULL,
                cover_path  TEXT,
                duration_ms INTEGER DEFAULT 0,
                downloaded_at INTEGER DEFAULT 0,
                file_size   INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T")
        onCreate(db)
    }

    fun insertSong(song: OfflineSong) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("video_id", song.videoId)
            put("title", song.title)
            put("artist", song.artist)
            put("cover_url", song.coverUrl)
            put("file_path", song.filePath)
            put("cover_path", song.coverPath)
            put("duration_ms", song.durationMs)
            put("downloaded_at", song.downloadedAt)
            put("file_size", song.fileSize)
        }
        db.insertWithOnConflict(T, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSong(videoId: String) {
        writableDatabase.delete(T, "video_id = ?", arrayOf(videoId))
    }

    fun getAllSongs(): List<OfflineSong> {
        val list = mutableListOf<OfflineSong>()
        val cursor = readableDatabase.query(T, null, null, null, null, null, "downloaded_at DESC")
        cursor.use { c ->
            while (c.moveToNext()) list.add(cursorToSong(c))
        }
        return list
    }

    fun getSongById(videoId: String): OfflineSong? {
        val cursor = readableDatabase.query(T, null, "video_id = ?", arrayOf(videoId), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToSong(c)
        }
        return null
    }

    fun isDownloaded(videoId: String): Boolean {
        val cursor = readableDatabase.query(T, arrayOf("video_id"), "video_id = ?", arrayOf(videoId), null, null, null)
        cursor.use { return it.count > 0 }
    }

    fun getSongCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $T", null)
        cursor.use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    private fun cursorToSong(c: Cursor): OfflineSong = OfflineSong(
        videoId = c.getString(c.getColumnIndexOrThrow("video_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        artist = c.getString(c.getColumnIndexOrThrow("artist")),
        coverUrl = c.getString(c.getColumnIndexOrThrow("cover_url")) ?: "",
        filePath = c.getString(c.getColumnIndexOrThrow("file_path")),
        coverPath = c.getString(c.getColumnIndexOrThrow("cover_path")) ?: "",
        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
        downloadedAt = c.getLong(c.getColumnIndexOrThrow("downloaded_at")),
        fileSize = c.getLong(c.getColumnIndexOrThrow("file_size"))
    )
}
