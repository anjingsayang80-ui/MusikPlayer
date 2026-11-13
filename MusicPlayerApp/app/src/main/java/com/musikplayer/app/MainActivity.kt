package com.musikplayer.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import java.util.concurrent.TimeUnit

data class Song(val id: Long, val title: String, val artist: String, val albumId: Long, val duration: Long)

class MainActivity : Activity() {
    private val songs = mutableListOf<Song>()
    private var player: MediaPlayer? = null
    private var current = 0
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var timeView: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: TextView
    private lateinit var album: ImageView
    private var seeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasAudioPermission()) showPlayer() else requestPermissions(arrayOf(audioPermission()), 41)
    }
    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) showPlayer()
        else { val t = TextView(this); t.text = "Izin akses musik diperlukan agar semua lagu di HP bisa diputar."; t.gravity = Gravity.CENTER; t.setTextColor(Color.WHITE); t.textSize = 18f; setContentView(t) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }

    private fun showPlayer() {
        loadSongs()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF777D7D.toInt(), 0xFF34383B.toInt(), 0xFF54242D.toInt()))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("‹", 38f), LinearLayout.LayoutParams(dp(42), dp(48)))
        top.addView(label("MUSIK PLAYER", 18f, Color.WHITE, true))
        root.addView(top)

        album = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(0xFFB9B9B9.toInt(), 200f); clipToOutline = true }
        val artWrap = FrameLayout(this).apply { gravity = Gravity.CENTER }
        artWrap.addView(album, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        val artParams = LinearLayout.LayoutParams(-1, 0, 1f); artParams.topMargin = dp(16); artParams.bottomMargin = dp(10)
        root.addView(artWrap, artParams)

        titleView = label("Belum ada lagu", 20f, Color.WHITE, true)
        artistView = label("Memindai musik di HP...", 14f, 0xFFE0E0E0.toInt())
        root.addView(titleView); root.addView(artistView)

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(14)) }
        listOf("♫\nEqualizer", "◈\nTampilan", "◷\nTimer", "☷\nDaftar lagu", "♡\nSuka").forEachIndexed { index, s ->
            val v = label(s, 13f); v.setOnClickListener { if (index == 3) showSongList() else Toast.makeText(this@MainActivity, "Fitur ini segera hadir", Toast.LENGTH_SHORT).show() }
            tools.addView(v, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(tools)

        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeView = label("0:00", 12f)
        seek = SeekBar(this).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        val duration = label("0:00", 12f)
        seekRow.addView(timeView); seekRow.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1f)); seekRow.addView(duration)
        root.addView(seekRow)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo((player?.duration ?: 0) * (s?.progress ?: 0) / 1000); seeking = false }
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {}
        })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(8)) }
        val shuffle = label("⇄", 28f); shuffle.setOnClickListener { songs.shuffle(); current = 0; playCurrent() }
        val prev = label("|◀", 30f); prev.setOnClickListener { previous() }
        playButton = label("▶", 42f); playButton.setOnClickListener { togglePlay() }
        val next = label("▶|", 30f); next.setOnClickListener { nextSong() }
        val queue = label("☷", 30f); queue.setOnClickListener { showSongList() }
        listOf(shuffle to 1f, prev to 1f, playButton to 1.4f, next to 1f, queue to 1f).forEach { (v, w) -> controls.addView(v, LinearLayout.LayoutParams(0, dp(64), w)) }
        root.addView(controls)
        val all = label("PUTAR SEMUA  •  " + songs.size + " LAGU", 13f, Color.WHITE, true)
        all.setPadding(0, dp(8), 0, dp(8)); all.setOnClickListener { if (songs.isNotEmpty()) { current = 0; playCurrent() } }
        root.addView(all)
        setContentView(root)
        renderSong()
        if (songs.isNotEmpty()) playCurrent()
        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.isPlaying && !seeking) {
                    seek.progress = if (p.duration > 0) p.currentPosition * 1000 / p.duration else 0
                    timeView.text = clock(p.currentPosition.toLong())
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    private fun loadSongs() {
        songs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        contentResolver.query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) songs.add(Song(c.getLong(id), c.getString(title) ?: "Tanpa judul", c.getString(artist) ?: "Artis tidak diketahui", c.getLong(albumId), c.getLong(duration)))
        }
    }
    private fun renderSong() {
        if (songs.isEmpty()) { titleView.text = "Belum ada lagu"; artistView.text = "Tidak ditemukan file musik di HP"; album.setImageResource(android.R.drawable.ic_media_play); return }
        val s = songs[current.coerceIn(0, songs.lastIndex)]
        titleView.text = s.title; artistView.text = s.artist
        val artUri = Uri.parse("content://media/external/audio/albumart")
        try { contentResolver.openInputStream(ContentUris.withAppendedId(artUri, s.albumId))?.use { album.setImageBitmap(BitmapFactory.decodeStream(it)) } ?: album.setImageResource(android.R.drawable.ic_media_play) }
        catch (_: Exception) { album.setImageResource(android.R.drawable.ic_media_play) }
        timeView.text = "0:00"
    }
    private fun playCurrent() {
        if (songs.isEmpty()) return
        try {
            player?.release()
            val s = songs[current]
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnCompletionListener { nextSong() }
                setOnPreparedListener { mp -> mp.start(); playButton.text = "Ⅱ" }
                prepareAsync()
            }
            renderSong()
            Toast.makeText(this, "Memutar " + s.title, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Tidak bisa memutar lagu ini", Toast.LENGTH_SHORT).show(); nextSong() }
    }
    private fun togglePlay() {
        val p = player
        if (p == null) playCurrent() else if (p.isPlaying) { p.pause(); playButton.text = "▶" } else { p.start(); playButton.text = "Ⅱ" }
    }
    private fun nextSong() { if (songs.isNotEmpty()) { current = (current + 1) % songs.size; playCurrent() } }
    private fun previous() { if (songs.isNotEmpty()) { current = if (current == 0) songs.lastIndex else current - 1; playCurrent() } }
    private fun clock(ms: Long) = String.format("%d:%02d", TimeUnit.MILLISECONDS.toMinutes(ms), TimeUnit.MILLISECONDS.toSeconds(ms) % 60)
    private fun showSongList() {
        val names = songs.map { it.title + " — " + it.artist }.toTypedArray()
        if (names.isEmpty()) { Toast.makeText(this, "Belum ada lagu ditemukan", Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Semua Lagu (" + songs.size + ")").setItems(names) { _, which -> current = which; playCurrent() }
            .setPositiveButton("Putar semua") { _, _ -> current = 0; playCurrent() }.setNegativeButton("Tutup", null).show()
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
