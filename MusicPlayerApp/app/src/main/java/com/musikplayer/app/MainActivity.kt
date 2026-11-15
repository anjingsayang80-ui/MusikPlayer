package com.musikplayer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.view.Gravity
import android.util.Size
import android.widget.*
import java.util.concurrent.TimeUnit

data class Song(val id: Long, val title: String, val artist: String, val albumId: Long, val duration: Long, val size: Long)
data class VideoItem(val id: Long, val title: String, val duration: Long, val size: Long)

class MainActivity : Activity() {
    private val songs = mutableListOf<Song>()
    private var player: MediaPlayer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var bassEqualizer: android.media.audiofx.Equalizer? = null
    private var playerVolume = 1.0f
    private var current = -1
    private lateinit var count: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniArt: ImageView
    private lateinit var listBox: LinearLayout
    private lateinit var musicScroll: ScrollView
    private lateinit var search: EditText
    private lateinit var playAllButton: TextView
    private var videoMode = false
    private var selectedTab = "Musik"
    private var selectedPlaylistName: String? = null
    private var playbackQueue: MutableList<Long>? = null
    private val bg = Color.rgb(10,11,17)
    private val panel = Color.rgb(24,25,34)
    private val muted = Color.rgb(155,158,174)
    private val accent = Color.rgb(177,91,255)
    private val handler = Handler()
    private var ticker: Runnable? = null
    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManager
    private val channelId = "musik_player_controls"
    private val actionPrevious = "com.musikplayer.app.PREVIOUS"
    private val actionToggle = "com.musikplayer.app.TOGGLE"
    private val actionNext = "com.musikplayer.app.NEXT"
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadVideo = false
    private var playbackGeneration = 0L
    private var activeVideoDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        setupMediaControls()
        if (hasPermission()) showLibrary() else requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(permission(), Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(permission()), 41)
    }
    private fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasPermission() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r,p,g)
        if (r == 43) {
            val url = pendingDownloadUrl
            pendingDownloadUrl = null
            if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED && url != null) startDownload(url, pendingDownloadVideo)
            else Toast.makeText(this, "Izin penyimpanan diperlukan untuk mengunduh file", Toast.LENGTH_LONG).show()
            return
        }
        if (r == 41 && g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) showLibrary()
        else if (r == 42) { /* Notifikasi opsional; pemutar tetap bisa digunakan. */ }
        else setContentView(label("Izinkan akses musik agar semua lagu di HP bisa ditemukan.",17f,Color.WHITE).apply { gravity=Gravity.CENTER })
    }
    private fun setVideoNavigationHidden(dialog: android.app.Dialog, hidden: Boolean) {
        val decor = dialog.window?.decorView ?: return
        decor.systemUiVisibility = if (hidden) {
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onPause() {
        activeVideoDialog?.let { setVideoNavigationHidden(it, false) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activeVideoDialog?.takeIf { it.isShowing }?.let { setVideoNavigationHidden(it, true) }
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun panelBg(color:Int=panel,r:Int=14)=GradientDrawable().apply { setColor(color); cornerRadius=dp(r).toFloat() }
    private fun label(s:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply { text=s; textSize=size; setTextColor(color); if(bold)setTypeface(null,Typeface.BOLD) }
    private fun centered(s:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=label(s,size,color,bold).apply { gravity=Gravity.CENTER }

    private fun showLibrary() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        loadSongs()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(6)) }
        val brand=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val logo=ImageView(this).apply { setImageResource(R.drawable.app_icon) }
        brand.addView(logo,LinearLayout.LayoutParams(dp(44),dp(44)))
        val brandName=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        brandName.addView(label("MUSIK PLAYER",17f,Color.WHITE,true))
        brandName.addView(label("Temukan irama favoritmu",11f,muted))
        brand.addView(brandName,LinearLayout.LayoutParams(0,-2,1f))
        brand.addView(centered("⌕",29f).apply { setOnClickListener { search.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(search,0) } },LinearLayout.LayoutParams(dp(42),dp(44)))
        header.addView(brand)
        val tabs=LinearLayout(this).apply { gravity=Gravity.CENTER; setPadding(0,dp(14),0,dp(4)) }
        listOf("Unduh","Video","Musik").forEach { name ->
            val t=centered(name,14f,if(name=="Musik") Color.WHITE else muted,name=="Musik").apply {
                setPadding(dp(8),dp(10),dp(8),dp(10))
                if(name=="Musik") background=GradientDrawable().apply { setColor(0x222D2140); setStroke(dp(1),accent); cornerRadius=dp(12).toFloat() }
                setOnClickListener {
                    selectedTab=name
                    for(i in 0 until tabs.childCount) {
                        val tab=tabs.getChildAt(i) as TextView
                        val active=tab.text.toString()==selectedTab
                        tab.setTextColor(if(active)Color.WHITE else muted)
                        tab.typeface=if(active)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        tab.background=if(active) GradientDrawable().apply { setColor(0x222D2140); setStroke(dp(1),accent); cornerRadius=dp(12).toFloat() } else null
                    }
                    if(name=="Unduh") {
                        showDownloadDialog()
                    } else {
                        if(name=="Video" && (player?.isPlaying == true || PlaybackService.retainedPlayer?.isPlaying == true || player != null || PlaybackService.retainedPlayer != null)) {
                            stopPlayback()
                            nowTitle.text="Musik dihentikan"
                            nowArtist.text="Video siap diputar"
                        }
                        videoMode=name=="Video"
                        playAllButton.visibility=if(videoMode)android.view.View.GONE else android.view.View.VISIBLE
                        if(videoMode)renderVideos(search.text.toString()) else renderSongs(search.text.toString())
                    }
                }
            }
            tabs.addView(t,LinearLayout.LayoutParams(0,dp(42),1f))
        }
        header.addView(tabs); root.addView(header)

        val searchWrap=LinearLayout(this).apply { setPadding(dp(18),dp(4),dp(18),dp(10)) }
        search=EditText(this).apply {
            hint="Cari judul lagu atau artis"; setHintTextColor(muted); setTextColor(Color.WHITE); textSize=14f
            isSingleLine = true; setPadding(dp(14),0,dp(14),0); background=panelBg()
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ if(videoMode) renderVideos(s?.toString().orEmpty()) else renderSongs(s?.toString().orEmpty()) }
                override fun afterTextChanged(s:android.text.Editable?){}
            })
        }
        searchWrap.addView(search,LinearLayout.LayoutParams(-1,dp(44))); root.addView(searchWrap)

        val section=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),dp(10)) }
        val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        sectionTitle=label("Semua Lagu",21f,Color.WHITE,true); heading.addView(sectionTitle,LinearLayout.LayoutParams(0,-2,1f))
        count=label(songs.size.toString()+" lagu ditemukan",12f,muted); heading.addView(count); section.addView(heading)
        val actions=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(12),0,0) }
        playAllButton=centered("▶   PUTAR SEMUA",13f,Color.WHITE,true).apply {
            background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF9B4DFF.toInt(),0xFF4D75FF.toInt())).apply { cornerRadius=dp(24).toFloat() }
            setOnClickListener { playVisibleSongs() }
        }
        actions.addView(playAllButton,LinearLayout.LayoutParams(0,dp(44),1f))
        actions.addView(centered("⤨  Acak",13f).apply { setOnClickListener { if(songs.isNotEmpty()){ songs.shuffle(); renderSongs(search.text.toString()); playSong(0) } } },LinearLayout.LayoutParams(dp(82),dp(44)))
        actions.addView(centered("♫ Bass",12f,accent,true).apply { setOnClickListener { showBassSettings() } },LinearLayout.LayoutParams(dp(68),dp(44)))
        actions.addView(centered("☷ List",12f,Color.WHITE,true).apply { setOnClickListener { showPlaylists() } },LinearLayout.LayoutParams(dp(66),dp(44)))
        section.addView(actions); root.addView(section)

        musicScroll=ScrollView(this).apply { clipToPadding=false; setPadding(0,0,0,dp(8)) }
        listBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(8)) }
        musicScroll.addView(listBox); root.addView(musicScroll,LinearLayout.LayoutParams(-1,0,1f))

        val mini=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(7),dp(10),dp(7)); background=panelBg(Color.rgb(31,29,43),15); setOnClickListener { showNowPlaying() } }
        miniArt=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.app_icon); background=panelBg(); clipToOutline=true }
        mini.addView(miniArt,LinearLayout.LayoutParams(dp(44),dp(44)))
        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        nowTitle=label("Pilih lagu untuk diputar",13f,Color.WHITE,true); nowArtist=label("Musik di perangkat kamu",11f,muted)
        info.addView(nowTitle); info.addView(nowArtist); mini.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        miniPlay=centered("▶",22f); miniPlay.setOnClickListener { togglePlay() }; mini.addView(miniPlay,LinearLayout.LayoutParams(dp(44),dp(44)))
        mini.addView(centered("■",18f,Color.WHITE,true).apply {
            setOnClickListener { stopPlayback() }
            contentDescription="Hentikan musik"
        },LinearLayout.LayoutParams(dp(38),dp(44)))
        val miniWrap=LinearLayout(this).apply { setPadding(dp(12),dp(4),dp(12),dp(6)); addView(mini) }; root.addView(miniWrap)

        setContentView(root)
        renderSongs("")
        ticker=object:Runnable { override fun run(){ if(player?.isPlaying==true) miniPlay.text="Ⅱ"; handler.postDelayed(this,700) } }
        handler.post(ticker!!)
    }

    private fun showDownloadDialog() {
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(14),dp(20),dp(8))
            background=panelBg(Color.rgb(25,24,36),18)
        }
        box.addView(label("Tempel tautan langsung file musik atau video",13f,muted).apply { setPadding(0,0,0,dp(10)) })
        val urlInput=EditText(this).apply {
            hint="https://contoh.com/file.mp3"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize=14f
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(12),0,dp(12),0)
            background=panelBg(Color.rgb(39,38,51),10)
        }
        box.addView(urlInput,LinearLayout.LayoutParams(-1,dp(48)))
        box.addView(label("Simpan sebagai",12f,muted).apply { setPadding(0,dp(16),0,dp(6)) })
        val group=RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        val musicChoice=RadioButton(this).apply { text="Musik"; setTextColor(Color.WHITE); id=android.view.View.generateViewId(); isChecked=true; buttonTintList=android.content.res.ColorStateList.valueOf(accent) }
        val videoChoice=RadioButton(this).apply { text="Video"; setTextColor(Color.WHITE); id=android.view.View.generateViewId(); buttonTintList=android.content.res.ColorStateList.valueOf(accent) }
        group.addView(musicChoice); group.addView(videoChoice)
        box.addView(group)
        box.addView(label("File akan masuk ke folder Music atau Movies. Gunakan tautan unduhan langsung (HTTP/HTTPS).",11f,muted).apply { setPadding(0,dp(8),0,0) })
        val dialog=AlertDialog.Builder(this)
            .setTitle("Unduh Musik / Video")
            .setView(box)
            .setNegativeButton("Batal",null)
            .setPositiveButton("Mulai unduh",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link=urlInput.text.toString().trim()
                if(link.isBlank()) {
                    urlInput.error="Masukkan tautan unduhan"
                    return@setOnClickListener
                }
                startDownload(link,videoChoice.isChecked)
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun startDownload(link:String, video:Boolean) {
        val uri=try { Uri.parse(link) } catch (_:Exception) { null }
        if(uri==null || uri.host.isNullOrBlank() || (uri.scheme!="http" && uri.scheme!="https")) {
            Toast.makeText(this,"Tautan harus diawali http:// atau https://",Toast.LENGTH_LONG).show()
            return
        }
        if(Build.VERSION.SDK_INT<29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl=link
            pendingDownloadVideo=video
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),43)
            return
        }
        if(Build.VERSION.SDK_INT>=29) {
            Toast.makeText(this,"Memeriksa format file dari server...",Toast.LENGTH_SHORT).show()
            Thread { downloadAndValidate(link,video) }.start()
            return
        }
        try {
            var fileName=Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').trim()
            fileName=fileName.replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            if(fileName.isBlank() || fileName=="." || fileName=="..") fileName=if(video)"video.mp4" else "musik.mp3"
            val request=DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription(if(video)"Mengunduh video ke folder Movies" else "Mengunduh musik ke folder Music")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(if(video)Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC,fileName)
            if(Build.VERSION.SDK_INT>=24) request.setAllowedOverMetered(true)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this,"Unduhan dimulai: $fileName",Toast.LENGTH_LONG).show()
        } catch (e:Exception) {
            Toast.makeText(this,"Gagal memulai unduhan: ${e.message ?: "periksa tautan"}",Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadAndValidate(link:String, video:Boolean) {
        var connection:java.net.HttpURLConnection?=null
        var savedUri:Uri?=null
        try {
            connection=(java.net.URL(link).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects=true
                connectTimeout=20000
                readTimeout=30000
                requestMethod="GET"
                setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) MusikPlayer")
            }
            connection.connect()
            val status=connection.responseCode
            if(status !in 200..299) throw Exception("Server mengirim kode $status")
            val rawType=connection.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
            val expected=if(video)"video/" else "audio/"
            if(rawType.startsWith("text/") || rawType.contains("html") || rawType.contains("json") ||
                (rawType.isNotBlank() && rawType!="application/octet-stream" && !rawType.startsWith(expected))) {
                throw Exception("Tautan bukan file ${if(video)"video" else "audio"} (tipe: ${rawType.ifBlank{"tidak diketahui"}}). Gunakan tautan file langsung.")
            }
            val disposition=connection.getHeaderField("Content-Disposition").orEmpty()
            val fromHeader=Regex("""(?i)filename\*?=(?:UTF-8''|")?([^;"\r\n]+)""").find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
            val fromUrl=Uri.parse(connection.url.toString()).lastPathSegment
            var name=(fromHeader ?: fromUrl ?: "").substringAfterLast('/').trim()
            name=try { java.net.URLDecoder.decode(name,"UTF-8") } catch (_:Exception) { name }
            name=name.replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            val extFromType=when(rawType) {
                "audio/mpeg"->"mp3"; "audio/mp4"->"m4a"; "audio/aac"->"aac"; "audio/wav","audio/x-wav"->"wav"
                "audio/ogg"->"ogg"; "audio/flac"->"flac"; "video/mp4"->"mp4"; "video/webm"->"webm"
                "video/3gpp"->"3gp"; "video/x-matroska"->"mkv"; else->""
            }
            val currentExt=name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if(name.isBlank() || name=="." || name==".." || currentExt.isBlank()) {
                if(extFromType.isBlank()) throw Exception("Server tidak memberi format file. Gunakan tautan unduhan langsung.")
                name=(name.substringBeforeLast('.',name).ifBlank { if(video)"video" else "musik" })+"."+extFromType
            } else if(extFromType.isNotBlank() && currentExt!=extFromType && !(extFromType=="mp4" && currentExt=="m4v")) {
                name=name.substringBeforeLast('.')+"."+extFromType
            }
            val mime=if(rawType.startsWith(expected)) rawType else when(name.substringAfterLast('.').lowercase()) {
                "mp3"->"audio/mpeg"; "m4a"->"audio/mp4"; "aac"->"audio/aac"; "wav"->"audio/wav"; "ogg"->"audio/ogg"; "flac"->"audio/flac"
                "mp4"->"video/mp4"; "webm"->"video/webm"; "3gp"->"video/3gpp"; "mkv"->"video/x-matroska"; else->throw Exception("Format file tidak dikenali")
            }
            val values=android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,if(video)Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC)
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            val collection=if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            savedUri=contentResolver.insert(collection,values) ?: throw Exception("Tidak bisa membuat file tujuan")
            val input=connection.inputStream
            val output=contentResolver.openOutputStream(savedUri!!,"w") ?: throw Exception("Tidak bisa menyimpan file")
            input.use { source -> output.use { target ->
                val buffer=ByteArray(64*1024)
                while(true) { val n=source.read(buffer); if(n<0) break; target.write(buffer,0,n) }
                target.flush()
            } }
            contentResolver.update(savedUri!!,android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            handler.post { Toast.makeText(this,"Berhasil diunduh: $name",Toast.LENGTH_LONG).show(); if(videoMode)renderVideos(search.text.toString()) else loadSongs() }
        } catch(e:Exception) {
            savedUri?.let { try { contentResolver.delete(it,null,null) } catch (_:Exception) {} }
            handler.post { Toast.makeText(this,"Unduhan dibatalkan: ${e.message ?: "file tidak valid"}",Toast.LENGTH_LONG).show() }
        } finally { connection?.disconnect() }
    }

    private fun loadSongs() {
        songs.clear()
        val hiddenIds=getSharedPreferences("musik_player",MODE_PRIVATE).getStringSet("hidden_song_ids", emptySet()).orEmpty()
        val cols=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM_ID,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.SIZE,MediaStore.Audio.Media.DATA)
        val uniqueSongs = LinkedHashMap<String, Song>()
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,cols,MediaStore.Audio.Media.IS_MUSIC+" != 0",null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")?.use { c ->
            val id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val album=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val size=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val data=c.getColumnIndex(MediaStore.Audio.Media.DATA)
            while(c.moveToNext()) {
                val song=Song(c.getLong(id),c.getString(title)?:"Tanpa judul",c.getString(artist)?:"Artis tidak diketahui",c.getLong(album),c.getLong(duration),c.getLong(size))
                val path=if(data>=0) c.getString(data)?.trim()?.lowercase().orEmpty() else ""
                val normalizedTitle=song.title.trim().lowercase(java.util.Locale.ROOT)
                val normalizedArtist=song.artist.trim().lowercase(java.util.Locale.ROOT)
                val key="${normalizedTitle}|${normalizedArtist}|${song.duration}|${song.size}"
                if(song.duration>0L && song.size>0L && song.id.toString() !in hiddenIds && !uniqueSongs.containsKey(key)) uniqueSongs[key]=song
            }
        }
        songs.addAll(uniqueSongs.values.sortedWith(compareBy<Song> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.artist.lowercase(java.util.Locale.ROOT) }))
        val saved = getSharedPreferences("musik_player", MODE_PRIVATE).getString("song_order", "").orEmpty().split(",").filter { it.isNotBlank() }.mapNotNull { it.toLongOrNull() }
        if (saved.isNotEmpty()) { val byId = songs.associateBy { it.id }; val ordered = saved.mapNotNull { byId[it] }.toMutableList(); songs.filter { it.id !in saved }.forEach { ordered.add(it) }; songs.clear(); songs.addAll(ordered) }
    }
    private fun saveSongOrder() {
        // Tulis urutan secara sinkron agar perubahan tidak hilang saat daftar segera dirender/aktivitas dibuat ulang.
        getSharedPreferences("musik_player", MODE_PRIVATE).edit()
            .putString("song_order", songs.joinToString(",") { it.id.toString() })
            .commit()
    }
    private fun moveSongToFirst(song:Song) {
        val from=songs.indexOf(song)
        if(from<=0) return
        val item=songs.removeAt(from)
        songs.add(0,item)
        saveSongOrder()
        renderSongs(search.text.toString())
        if(::musicScroll.isInitialized) musicScroll.post {
            musicScroll.smoothScrollTo(0,0)
            musicScroll.post { musicScroll.scrollTo(0,0) }
        }
        Toast.makeText(this,"Lagu dipindahkan ke urutan pertama",Toast.LENGTH_SHORT).show()
    }
    private fun moveSong(from:Int, delta:Int) {
        val to=from+delta
        if(from !in songs.indices || to !in songs.indices) return
        val item=songs.removeAt(from); songs.add(to,item)
        saveSongOrder()
        renderSongs(search.text.toString())
        Toast.makeText(this,"Urutan lagu disimpan",Toast.LENGTH_SHORT).show()
    }
    private fun renderVideos(filter:String) {
        if(!::listBox.isInitialized) return
        listBox.removeAllViews()
        sectionTitle.text="Telah diunduh"
        count.text="Memuat video..."
        Thread {
            val videos=mutableListOf<VideoItem>()
            val cols=arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.TITLE,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.SIZE)
            try {
                contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cols,null,null,MediaStore.Video.Media.TITLE+" COLLATE NOCASE ASC")?.use { cur ->
                    val id=cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val title=cur.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val duration=cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val size=cur.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    while(cur.moveToNext()) videos.add(VideoItem(cur.getLong(id),cur.getString(title)?:"Video tanpa judul",cur.getLong(duration),cur.getLong(size)))
                }
            } catch (_:Exception) {}
            val shown=videos.filter { it.title.contains(filter,true) }
            handler.post {
                if(!videoMode || !::listBox.isInitialized) return@post
                listBox.removeAllViews()
                count.text=shown.size.toString()+" video ditemukan"
                if(shown.isEmpty()) {
                    listBox.addView(centered("Belum ada video di HP",14f,muted).apply{setPadding(0,dp(40),0,dp(40))})
                    return@post
                }
                shown.forEach { video ->
                    val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10)); background=panelBg() }
                    val thumbnail=ImageView(this).apply {
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        background=panelBg(Color.rgb(40,35,55),8)
                        clipToOutline=true
                        setImageResource(R.drawable.app_icon)
                        tag=video.id
                    }
                    row.addView(thumbnail,LinearLayout.LayoutParams(dp(112),dp(76)))
                    val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
                    info.addView(label(video.title,14f,Color.WHITE,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
                    info.addView(label(sizeLabel(video.size),11f,muted).apply{setPadding(0,dp(4),0,0)})
                    val watched=getSharedPreferences("musik_player",MODE_PRIVATE).getInt("video_progress_${video.id}",0)
                    info.addView(label("$watched% menonton",11f,muted).apply{setPadding(0,dp(5),0,0)})
                    row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                    row.setOnClickListener{playVideo(video)}
                    val lp=LinearLayout.LayoutParams(-1,dp(96));lp.bottomMargin=dp(10);listBox.addView(row,lp)
                    Thread {
                        try {
                            val videoUri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,video.id)
                            val bmp=if(Build.VERSION.SDK_INT>=29) contentResolver.loadThumbnail(videoUri,Size(dp(150),dp(100)),null)
                            else MediaStore.Video.Thumbnails.getThumbnail(contentResolver,video.id,MediaStore.Video.Thumbnails.MINI_KIND,null)
                            handler.post { if(thumbnail.tag==video.id && bmp!=null) thumbnail.setImageBitmap(bmp) }
                        } catch (_:Exception) {}
                    }.start()
                }
            }
        }.start()
    }

    private fun playVideo(video:VideoItem) {
        val videoList=mutableListOf<VideoItem>()
        val videoCols=arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.TITLE,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.SIZE)
        try {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,videoCols,null,null,MediaStore.Video.Media.TITLE+" COLLATE NOCASE ASC")?.use { cur ->
                val idCol=cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol=cur.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationCol=cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol=cur.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while(cur.moveToNext()) videoList.add(VideoItem(cur.getLong(idCol),cur.getString(titleCol)?:"Video tanpa judul",cur.getLong(durationCol),cur.getLong(sizeCol)))
            }
        } catch (_:Exception) {}
        val videoIndex=videoList.indexOfFirst { it.id==video.id }
        val uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,video.id)
        // Ambil ukuran asli video dari metadata; VideoView tidak membuka videoWidth/videoHeight.
        val (sourceWidth,sourceHeight)=try {
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(this,uri)
                var w=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if(rotation==90 || rotation==270) { val temp=w; w=h; h=temp }
                Pair(w,h)
            } finally { retriever.release() }
        } catch (_:Exception) { Pair(0,0) }
        // Sesuaikan ukuran tampilan agar video utuh terlihat tanpa terpotong.
        val videoView=object:VideoView(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                val maxW=MeasureSpec.getSize(widthMeasureSpec)
                val maxH=MeasureSpec.getSize(heightMeasureSpec)
                if(sourceWidth>0 && sourceHeight>0 && maxW>0 && maxH>0) {
                    val scale=minOf(maxW.toFloat()/sourceWidth,maxH.toFloat()/sourceHeight)
                    val fittedW=(sourceWidth*scale).toInt().coerceAtLeast(1)
                    val fittedH=(sourceHeight*scale).toInt().coerceAtLeast(1)
                    setMeasuredDimension(fittedW,fittedH)
                } else {
                    setMeasuredDimension(maxW,maxH)
                }
            }
        }
        val frame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isFocusable=true; isFocusableInTouchMode=true }
        frame.clipChildren=true
        frame.clipToPadding=true
        frame.addView(videoView,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        var videoZoom=1f
        fun applyVideoZoom(value:Float) {
            videoZoom=value.coerceIn(1f,3f)
            // Perbesar dari titik tengah video, bukan dari titik tengah layar.
            videoView.pivotX=videoView.width/2f
            videoView.pivotY=videoView.height/2f
            videoView.scaleX=videoZoom
            videoView.scaleY=videoZoom
        }
        val zoomBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(dp(6),dp(4),dp(6),dp(4))
            background=panelBg(Color.argb(190,18,18,26),12)
        }
        zoomBar.addView(centered("−",24f).apply { setPadding(dp(10),0,dp(10),0); setOnClickListener { applyVideoZoom(videoZoom-0.25f) } })
        zoomBar.addView(centered("1×",13f,Color.WHITE,true).apply { setPadding(dp(8),0,dp(8),0); setOnClickListener { applyVideoZoom(1f) } })
        zoomBar.addView(centered("+",24f).apply { setPadding(dp(10),0,dp(10),0); setOnClickListener { applyVideoZoom(videoZoom+0.25f) } })
        val videoSwitch=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(dp(6),dp(4),dp(6),dp(4))
            background=panelBg(Color.argb(190,18,18,26),12)
        }
        val previousVideo=centered("◀  Video sebelumnya",12f,Color.WHITE,true).apply {
            setPadding(dp(12),dp(10),dp(12),dp(10))
            isEnabled=videoIndex>0
            alpha=if(isEnabled)1f else 0.35f
            setOnClickListener {
                if(videoIndex>0) {
                    val next=videoList[videoIndex-1]
                    dialog.dismiss()
                    playVideo(next)
                }
            }
        }
        val nextVideo=centered("Video berikutnya  ▶",12f,Color.WHITE,true).apply {
            setPadding(dp(12),dp(10),dp(12),dp(10))
            isEnabled=videoIndex>=0 && videoIndex<videoList.lastIndex
            alpha=if(isEnabled)1f else 0.35f
            setOnClickListener {
                if(videoIndex>=0 && videoIndex<videoList.lastIndex) {
                    val next=videoList[videoIndex+1]
                    dialog.dismiss()
                    playVideo(next)
                }
            }
        }
        videoSwitch.addView(previousVideo)
        videoSwitch.addView(nextVideo)
        val videoOverlay=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.END
            addView(zoomBar,LinearLayout.LayoutParams(-2,dp(42)))
            addView(videoSwitch,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(10) })
        }
        frame.addView(videoOverlay,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(16); rightMargin=dp(14) })
        val controlsTimeout=object:Runnable { override fun run() { videoOverlay.animate().alpha(0f).setDuration(250).withEndAction { videoOverlay.visibility=android.view.View.GONE }.start() } }
        fun showVideoOverlay() {
            handler.removeCallbacks(controlsTimeout)
            videoOverlay.visibility=android.view.View.VISIBLE
            videoOverlay.alpha=1f
            handler.postDelayed(controlsTimeout,15000)
        }
        val pinchZoom=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector:android.view.ScaleGestureDetector):Boolean {
                applyVideoZoom(videoZoom*detector.scaleFactor)
                return true
            }
        })
        val controller=android.widget.MediaController(this)
        controller.setAnchorView(frame)
        videoView.setMediaController(controller)
        val dialog=android.app.Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            setLayout(-1,-1)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val progressKey="video_progress_${video.id}"
        var saveProgress:Runnable?=null
        val oldOrientation=requestedOrientation
        // Match the screen orientation to the source video's dimensions.
        val videoOrientation = try {
            val retriever=android.media.MediaMetadataRetriever()
            retriever.setDataSource(this,uri)
            val width=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()
            val isLandscape=if(rotation==90 || rotation==270) height>width else width>=height
            if(isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_:Exception) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        videoView.setOnPreparedListener { mp ->
            if(Build.VERSION.SDK_INT>=21) {
                try { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (_:Exception) {}
            }
            val saved=getSharedPreferences("musik_player",MODE_PRIVATE).getInt(progressKey,0)
            if(saved in 1..99 && video.duration>0) videoView.seekTo((video.duration*saved/100).toInt())
            videoView.start()
            controller.show(2500)
            showVideoOverlay()
            saveProgress=object:Runnable {
                override fun run() {
                    if(videoView.isPlaying && video.duration>0) {
                        val percent=(videoView.currentPosition*100L/video.duration).toInt().coerceIn(0,99)
                        getSharedPreferences("musik_player",MODE_PRIVATE).edit().putInt(progressKey,percent).apply()
                    }
                    handler.postDelayed(this,1000)
                }
            }
            handler.post(saveProgress!!)
        }
        videoView.setOnCompletionListener {
            getSharedPreferences("musik_player",MODE_PRIVATE).edit().putInt(progressKey,100).apply()
            videoView.seekTo(0)
        }
        var startSeekPosition=0
        var startTouchX=0f
        var moved=false
        val gesture=android.view.GestureDetector(this,object:android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:android.view.MotionEvent)=true
            override fun onSingleTapConfirmed(e:android.view.MotionEvent):Boolean {
                if(videoOverlay.visibility==android.view.View.VISIBLE) {
                    handler.removeCallbacks(controlsTimeout)
                    videoOverlay.visibility=android.view.View.GONE
                    if(controller.isShowing) controller.hide()
                } else {
                    showVideoOverlay()
                    controller.show(3000)
                }
                return true
            }
            override fun onDoubleTap(e:android.view.MotionEvent):Boolean {
                val jump=if(e.x<videoView.width/2) -20000 else 20000
                val duration=videoView.duration.coerceAtLeast(0)
                videoView.seekTo((videoView.currentPosition+jump).coerceIn(0,duration))
                Toast.makeText(this@MainActivity,if(jump<0)"Mundur 20 detik" else "Maju 20 detik",Toast.LENGTH_SHORT).show()
                return true
            }
            override fun onLongPress(e:android.view.MotionEvent) {
                val jump=if(e.x<videoView.width/2) -10000 else 10000
                videoView.seekTo((videoView.currentPosition+jump).coerceIn(0,videoView.duration.coerceAtLeast(0)))
                Toast.makeText(this@MainActivity,if(jump<0)"Mundur 10 detik" else "Maju 10 detik",Toast.LENGTH_SHORT).show()
            }
            override fun onScroll(e1:android.view.MotionEvent?,e2:android.view.MotionEvent,dx:Float,dy:Float):Boolean {
                if(e1==null || kotlin.math.abs(e2.x-e1.x)<dp(10)) return false
                moved=true
                val delta=(e2.x-startTouchX).toInt()*250
                val duration=videoView.duration.coerceAtLeast(0)
                videoView.seekTo((startSeekPosition+delta).coerceIn(0,duration))
                return true
            }
        })
        videoView.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startSeekPosition=videoView.currentPosition
                    startTouchX=event.x
                    moved=false
                }
            }
            pinchZoom.onTouchEvent(event)
            if(!pinchZoom.isInProgress) gesture.onTouchEvent(event)
            true
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(controlsTimeout)
            setVideoNavigationHidden(dialog, false)
            if (activeVideoDialog === dialog) activeVideoDialog = null
            saveProgress?.let{handler.removeCallbacks(it)}
            if(video.duration>0 && videoView.currentPosition>0 && videoView.currentPosition<video.duration) {
                val percent=(videoView.currentPosition*100L/video.duration).toInt().coerceIn(0,99)
                getSharedPreferences("musik_player",MODE_PRIVATE).edit().putInt(progressKey,percent).apply()
            }
            videoView.stopPlayback()
            requestedOrientation=oldOrientation
        }
        requestedOrientation=videoOrientation
        dialog.show()
        activeVideoDialog = dialog
        dialog.window?.apply {
            setLayout(-1,-1)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setVideoNavigationHidden(dialog, true)
        videoView.setVideoURI(uri)
    }

    private fun playlistMap(): LinkedHashMap<String, MutableList<Long>> {
        val result=LinkedHashMap<String,MutableList<Long>>()
        try {
            val raw=getSharedPreferences("musik_player",MODE_PRIVATE).getString("playlists_json","{}") ?: "{}"
            val obj=org.json.JSONObject(raw)
            val keys=obj.keys()
            while(keys.hasNext()) {
                val name=keys.next()
                val arr=obj.optJSONArray(name) ?: org.json.JSONArray()
                val ids=mutableListOf<Long>()
                for(i in 0 until arr.length()) { val id=arr.optLong(i,-1L); if(id>0L)ids.add(id) }
                result[name]=ids
            }
        } catch (_:Exception) {}
        return result
    }
    private fun savePlaylistMap(map:LinkedHashMap<String,MutableList<Long>>) {
        val obj=org.json.JSONObject()
        map.forEach { (name,ids) -> obj.put(name,org.json.JSONArray(ids)) }
        getSharedPreferences("musik_player",MODE_PRIVATE).edit().putString("playlists_json",obj.toString()).apply()
    }
    private fun playVisibleSongs() {
        val ids=selectedPlaylistName?.let { playlistMap()[it] }
        val first=if(ids==null) songs.firstOrNull() else songs.firstOrNull { it.id in ids }
        if(first==null) { Toast.makeText(this,"Belum ada lagu di daftar ini",Toast.LENGTH_SHORT).show(); return }
        playbackQueue=ids?.toMutableList()
        playSong(songs.indexOf(first))
    }
    private fun showPlaylists() {
        val map=playlistMap()
        val names=map.keys.toList()
        val options=arrayOf("Buat daftar putar baru") + names.map { it + "  (" + (map[it]?.size ?: 0) + " lagu)" } + arrayOf("Semua Lagu")
        AlertDialog.Builder(this).setTitle("Daftar Putar").setItems(options) { _,which ->
            when {
                which==0 -> createPlaylist()
                which<=names.size -> {
                    val name=names[which-1]
                    AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Buka daftar putar","Putar semua lagu","Hapus daftar putar")) { _,action ->
                        when(action) {
                            0 -> { selectedPlaylistName=name; renderSongs(search.text.toString()) }
                            1 -> { selectedPlaylistName=name; renderSongs(search.text.toString()); playbackQueue=map[name]?.toMutableList(); playVisibleSongs() }
                            2 -> AlertDialog.Builder(this).setMessage("Hapus daftar putar " + name + "? File musik tidak akan dihapus.")
                                .setNegativeButton("Batal",null).setPositiveButton("Hapus") { _,_ ->
                                    val updated=playlistMap(); updated.remove(name); savePlaylistMap(updated)
                                    if(selectedPlaylistName==name){selectedPlaylistName=null;renderSongs(search.text.toString())}
                                    Toast.makeText(this,"Daftar putar dihapus",Toast.LENGTH_SHORT).show()
                                }.show()
                        }
                    }.show()
                }
                else -> { selectedPlaylistName=null; playbackQueue=null; renderSongs(search.text.toString()) }
            }
        }.show()
    }
    private fun createPlaylist(song:Song?=null) {
        val field=EditText(this).apply { hint="Nama daftar putar"; setTextColor(Color.WHITE); setHintTextColor(muted) }
        AlertDialog.Builder(this).setTitle("Buat daftar putar").setView(field)
            .setNegativeButton("Batal",null).setPositiveButton("Buat") { _,_ ->
                val name=field.text.toString().trim()
                if(name.isBlank()) { Toast.makeText(this,"Nama daftar putar belum diisi",Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val map=playlistMap()
                if(map.containsKey(name)) { Toast.makeText(this,"Nama daftar putar sudah ada",Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                map[name]=mutableListOf<Long>().apply { if(song!=null)add(song.id) }
                savePlaylistMap(map)
                Toast.makeText(this,if(song!=null)"Lagu ditambahkan ke " + name else "Daftar putar dibuat",Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun addSongToPlaylist(song:Song) {
        val map=playlistMap()
        val names=map.keys.toList()
        val options=names.map { it + "  (" + (map[it]?.size ?: 0) + " lagu)" }.toMutableList()
        options.add("Buat daftar putar baru")
        AlertDialog.Builder(this).setTitle("Tambah ke daftar putar").setItems(options.toTypedArray()) { _,which ->
            if(which==names.size) createPlaylist(song) else {
                val updated=playlistMap()
                val ids=updated[names[which]] ?: mutableListOf<Long>()
                if(song.id !in ids) ids.add(song.id)
                updated[names[which]]=ids
                savePlaylistMap(updated)
                Toast.makeText(this,"Ditambahkan ke " + names[which],Toast.LENGTH_SHORT).show()
                if(selectedPlaylistName==names[which])renderSongs(search.text.toString())
            }
        }.show()
    }
    private fun renderSongs(filter:String) {
        if(!::listBox.isInitialized)return
        listBox.removeAllViews()
        val playlistIds=selectedPlaylistName?.let { playlistMap()[it]?.toSet() }
        sectionTitle.text=selectedPlaylistName ?: "Semua Lagu"
        val shown=songs.filter { (playlistIds==null || it.id in playlistIds) && (it.title.contains(filter,true)||it.artist.contains(filter,true)) }
        count.text=shown.size.toString()+" lagu ditemukan"
        if(shown.isEmpty()){ listBox.addView(centered(if(selectedPlaylistName!=null)"Daftar putar ini masih kosong" else if(songs.isEmpty())"Belum ada file musik di HP" else "Lagu tidak ditemukan",14f,muted).apply{setPadding(0,dp(40),0,dp(40))}); return }
        shown.forEach { song ->
            val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(7),dp(8),dp(7)); background=panelBg() }
            val art=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=panelBg(Color.rgb(40,35,55),10); clipToOutline=true; setImageResource(R.drawable.app_icon) }
            art.tag=song.id
            Thread {
                val bitmap=loadSongArtwork(song)
                handler.post { if(art.tag==song.id && bitmap!=null) art.setImageBitmap(bitmap) }
            }.start()
            row.addView(art,LinearLayout.LayoutParams(dp(58),dp(58)))
            val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(5),0) }
            info.addView(label(song.title,14f,Color.WHITE,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            info.addView(label(song.artist,12f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(3),0,dp(3))})
            info.addView(label(clock(song.duration)+"  •  "+sizeLabel(song.size),10f,muted))
            row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            val songNumber = songs.indexOf(song) + 1
            row.addView(centered(songNumber.toString(),15f,accent,true).apply {
                contentDescription="Nomor $songNumber. Ketuk untuk memindahkan lagu ke nomor 1"
                setOnClickListener { moveSongToFirst(song) }
                background=GradientDrawable().apply {
                    setColor(Color.rgb(48,35,65))
                    cornerRadius=dp(10).toFloat()
                }
            },LinearLayout.LayoutParams(dp(38),dp(38)))
            row.addView(centered("⋮",22f).apply{setOnClickListener{AlertDialog.Builder(this@MainActivity).setTitle(song.title).setItems(arrayOf("Putar sekarang","Tambah ke daftar putar","Pindahkan ke urutan pertama","Hapus dari daftar aplikasi","Hapus lagu dari HP")){_,which->val pos=songs.indexOf(song);when(which){0->{playbackQueue=selectedPlaylistName?.let{playlistMap()[it]?.toMutableList()};playSong(pos)};1->addSongToPlaylist(song);2->moveSongToFirst(song);3->hideSongFromLibrary(song);4->confirmDeleteSong(song)}}.show()}},LinearLayout.LayoutParams(dp(30),dp(52)))
            // Pegangan khusus agar pengguna tahu bagian yang harus ditahan untuk menyeret lagu.
            val dragHandle=centered("⠿",24f,accent,true).apply {
                contentDescription="Tahan lalu seret untuk mengubah urutan lagu"
                setOnLongClickListener {
                    val clip=android.content.ClipData.newPlainText("song_id",song.id.toString())
                    val shadow=android.view.View.DragShadowBuilder(row)
                    if(Build.VERSION.SDK_INT>=24) row.startDragAndDrop(clip,shadow,song.id,android.view.View.DRAG_FLAG_GLOBAL)
                    else row.startDrag(clip,shadow,song.id,0)
                }
            }
            row.addView(dragHandle,LinearLayout.LayoutParams(dp(34),dp(52)))
            row.setOnDragListener { target,event ->
                when(event.action){
                    android.view.DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType("text/plain") == true
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { target.alpha=0.65f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { target.alpha=1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        target.alpha=1f
                        val draggedId=(event.localState as? Long)
                            ?: event.clipData?.getItemAt(0)?.text?.toString()?.toLongOrNull()
                        val from=songs.indexOfFirst{it.id==draggedId}
                        val to=songs.indexOf(song)
                        if(from>=0 && to>=0 && from!=to){
                            val moving=songs.removeAt(from)
                            songs.add(to.coerceAtMost(songs.size),moving)
                            saveSongOrder()
                            renderSongs(search.text.toString())
                            Toast.makeText(this@MainActivity,"Lagu dipindahkan ke nomor ${songs.indexOf(moving)+1}",Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { target.alpha=1f; true }
                    else -> true
                }
            }
            row.setOnClickListener{playbackQueue=selectedPlaylistName?.let{playlistMap()[it]?.toMutableList()};playSong(songs.indexOf(song))}
            val lp=LinearLayout.LayoutParams(-1,dp(76));lp.bottomMargin=dp(8);listBox.addView(row,lp)
        }
    }
    private fun hideSongFromLibrary(song: Song) {
        val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
        val hidden=prefs.getStringSet("hidden_song_ids",emptySet()).orEmpty().toMutableSet()
        hidden.add(song.id.toString())
        prefs.edit().putStringSet("hidden_song_ids",hidden).apply()
        if(current in songs.indices && songs[current].id==song.id){
            player?.release(); player=null; PlaybackService.retainedPlayer=null; current=-1
            nowTitle.text="Pilih lagu untuk diputar"; nowArtist.text="Musik di perangkat kamu"; miniPlay.text="▶"
            notificationManager.cancel(77)
        }
        loadSongs()
        renderSongs(search.text.toString())
        Toast.makeText(this,"Lagu disembunyikan dari daftar aplikasi. File asli tetap ada di HP.",Toast.LENGTH_LONG).show()
    }

    private fun confirmDeleteSong(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Hapus musik?")
            .setMessage("“${song.title}” akan dihapus dari penyimpanan HP, bukan hanya dari daftar aplikasi.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> deleteSong(song) }
            .show()
    }

    private fun deleteSong(song: Song) {
        pendingDeleteSongId = song.id
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                startIntentSenderForResult(request.intentSender, 70, null, 0, 0, 0)
            } else {
                val deleted = contentResolver.delete(uri, null, null)
                if (deleted > 0) finishSongDeletion(song) else Toast.makeText(this, "Musik tidak dapat dihapus", Toast.LENGTH_SHORT).show()
            }
        } catch (e: android.app.RecoverableSecurityException) {
            if (Build.VERSION.SDK_INT == 29) {
                try { startIntentSenderForResult(e.userAction.actionIntent.intentSender, 70, null, 0, 0, 0) }
                catch (_: Exception) { Toast.makeText(this, "Izin hapus tidak dapat dibuka", Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(this, "Musik tidak dapat dihapus", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Musik tidak dapat dihapus", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishSongDeletion(song: Song) {
        if (current in songs.indices && songs[current].id == song.id) {
            player?.release()
            player = null
            current = -1
            nowTitle.text = "Pilih lagu untuk diputar"
            nowArtist.text = "Musik di perangkat kamu"
            miniPlay.text = "▶"
            notificationManager.cancel(77)
        }
        getSharedPreferences("musik_player", MODE_PRIVATE).edit().remove("song_order").apply()
        loadSongs()
        renderSongs(search.text.toString())
        Toast.makeText(this, "Musik berhasil dihapus dari HP", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 70 && resultCode == Activity.RESULT_OK) {
            val pending = songs.firstOrNull { it.id == pendingDeleteSongId }
            if (pending != null) finishSongDeletion(pending)
            else {
                loadSongs()
                renderSongs(search.text.toString())
            }
        }
    }

    private var pendingDeleteSongId: Long = -1L

    private fun loadSongArtwork(song: Song): android.graphics.Bitmap? {
        try {
            val albumUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
            contentResolver.openInputStream(albumUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { return it }
            }
        } catch (_: Exception) {}

        try {
            val audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, audioUri)
                val picture = retriever.embeddedPicture
                if (picture != null) return BitmapFactory.decodeByteArray(picture, 0, picture.size)
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {}
        return createGeneratedArtwork(song)
    }

    private fun createGeneratedArtwork(song:Song):android.graphics.Bitmap {
        val size=512
        val bmp=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bmp)
        val hash=song.title.hashCode() xor song.artist.hashCode()
        val colors=intArrayOf(
            android.graphics.Color.rgb(55+(hash ushr 1 and 90),35+(hash ushr 4 and 65),100+(hash ushr 7 and 120)),
            android.graphics.Color.rgb(25+(hash ushr 9 and 75),35+(hash ushr 13 and 90),75+(hash ushr 17 and 130))
        )
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader=android.graphics.LinearGradient(0f,0f,size.toFloat(),size.toFloat(),colors,null,android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,size.toFloat(),size.toFloat(),paint)
        paint.shader=null
        paint.color=android.graphics.Color.argb(45,255,255,255)
        canvas.drawCircle(390f,110f,155f,paint)
        canvas.drawCircle(80f,460f,190f,paint)
        paint.color=android.graphics.Color.argb(35,255,255,255)
        for(i in 0..4) canvas.drawCircle(256f,256f,(75+i*23).toFloat(),paint)
        paint.color=android.graphics.Color.WHITE
        paint.textAlign=android.graphics.Paint.Align.CENTER
        paint.typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.BOLD)
        paint.textSize=150f
        val initials=song.title.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.take(2).map{it.take(1).uppercase()}.joinToString("").ifBlank{"♫"}
        canvas.drawText(initials,256f,305f,paint)
        paint.textSize=27f
        paint.typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        canvas.drawText(song.title.take(28),256f,425f,paint)
        paint.textSize=19f
        paint.color=android.graphics.Color.argb(220,255,255,255)
        canvas.drawText(song.artist.take(34),256f,463f,paint)
        return bmp
    }

    private fun markSongUnplayable(song:Song) {
        val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
        val hidden=prefs.getStringSet("hidden_song_ids",emptySet()).orEmpty().toMutableSet()
        hidden.add(song.id.toString())
        prefs.edit().putStringSet("hidden_song_ids",hidden).apply()
        songs.removeAll { it.id==song.id }
        saveSongOrder()
        if(::listBox.isInitialized) renderSongs(search.text.toString())
    }

    private fun playSong(index:Int, failedAttempts:Int=0) {
        if(songs.isEmpty())return
        if(failedAttempts>=songs.size) {
            stopPlayback()
            Toast.makeText(this,"Tidak ada lagu berikutnya yang bisa diputar",Toast.LENGTH_LONG).show()
            return
        }
        val nextIndex=((index%songs.size)+songs.size)%songs.size
        val requestId=++playbackGeneration
        current=nextIndex
        try {
            bassBoost?.release(); bassBoost=null
            bassEqualizer?.release(); bassEqualizer=null
            val old=player ?: PlaybackService.retainedPlayer
            player=null
            PlaybackService.retainedPlayer=null
            try { old?.setOnCompletionListener(null); old?.setOnErrorListener(null); old?.release() } catch (_:Exception) {}
            val serviceIntent=Intent(this,PlaybackService::class.java)
            if(Build.VERSION.SDK_INT>=26) startForegroundService(serviceIntent) else startService(serviceIntent)
            val song=songs[nextIndex]
            val newPlayer=MediaPlayer()
            player=newPlayer
            PlaybackService.retainedPlayer=newPlayer
            newPlayer.setDataSource(this,ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,song.id))
            newPlayer.setOnCompletionListener { mp ->
                if(requestId==playbackGeneration && player===mp) playNextInQueue()
            }
            newPlayer.setOnErrorListener { mp,_,_ ->
                if(requestId==playbackGeneration && player===mp) {
                    player=null
                    PlaybackService.retainedPlayer=null
                    try { mp.release() } catch (_:Exception) {}
                    markSongUnplayable(song)
                    if(songs.isNotEmpty()) playSong(current.coerceAtLeast(0)%songs.size,failedAttempts+1)
                    else { miniPlay.text="▶"; nowTitle.text="Tidak ada lagu yang bisa diputar"; stopPlayback() }
                }
                true
            }
            newPlayer.setOnPreparedListener { mp ->
                if(requestId!=playbackGeneration || player!==mp) {
                    try { mp.release() } catch (_:Exception) {}
                    return@setOnPreparedListener
                }
                if(mp.duration<=0) {
                    player=null
                    PlaybackService.retainedPlayer=null
                    try { mp.release() } catch (_:Exception) {}
                    markSongUnplayable(song)
                    if(songs.isNotEmpty()) playSong(current.coerceAtLeast(0)%songs.size,failedAttempts+1)
                    return@setOnPreparedListener
                }
                mp.setVolume(playerVolume,playerVolume)
                setupBassBoost(mp.audioSessionId)
                mp.start()
                miniPlay.text="Ⅱ"
                updateMediaNotification(true)
            }
            nowTitle.text=song.title
            nowArtist.text=song.artist
            miniArt.setImageBitmap(loadSongArtwork(song))
            miniPlay.text="…"
            updateMediaNotification(false)
            newPlayer.prepareAsync()
        } catch(e:Exception) {
            if(requestId==playbackGeneration) {
                val failedSong=songs.getOrNull(nextIndex)
                player?.let { try { it.release() } catch (_:Exception) {} }
                player=null
                PlaybackService.retainedPlayer=null
                if(failedSong!=null) markSongUnplayable(failedSong)
                if(songs.isNotEmpty()) playSong(nextIndex.coerceAtLeast(0)%songs.size,failedAttempts+1)
                else { miniPlay.text="▶"; nowTitle.text="Tidak ada lagu yang bisa diputar"; stopPlayback() }
            }
        }
    }

    private fun stopPlayback() {
        playbackGeneration++
        val old=player ?: PlaybackService.retainedPlayer
        player=null
        PlaybackService.retainedPlayer=null
        try { old?.setOnCompletionListener(null); old?.setOnErrorListener(null); old?.release() } catch (_:Exception) {}
        try { bassBoost?.release() } catch (_:Exception) {}
        try { bassEqualizer?.release() } catch (_:Exception) {}
        bassBoost=null
        bassEqualizer=null
        miniPlay.text="▶"
        updateMediaNotification(false)
        try { mediaSession?.isActive=false } catch (_:Exception) {}
        try { stopService(Intent(this,PlaybackService::class.java)) } catch (_:Exception) {}
    }

    private fun setupBassBoost(sessionId:Int) {
        try {
            bassBoost?.release()
            bassBoost=null
            bassEqualizer?.release()
            bassEqualizer=null
            if(sessionId<=0) return
            val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
            val strength=prefs.getInt("bass_strength",700).coerceIn(0,1000)
            val enabled=prefs.getBoolean("bass_enabled",true) && strength>0
            try {
                val effect=android.media.audiofx.BassBoost(0,sessionId)
                if(effect.hasControl()) {
                    effect.setStrength(strength.toShort())
                    effect.enabled=enabled
                    bassBoost=effect
                } else effect.release()
            } catch (_:Exception) {}
            try {
                val eq=android.media.audiofx.Equalizer(0,sessionId)
                if(eq.hasControl() && eq.numberOfBands>0) {
                    val range=eq.bandLevelRange
                    val maxBoost=range[1].toInt().coerceAtLeast(0)
                    val boost=(maxBoost*(strength/1000f)).toInt().coerceIn(0,maxBoost).toShort()
                    for(i in 0 until eq.numberOfBands.toInt()) {
                        val band=i.toShort()
                        if(eq.getCenterFreq(band) in 20..250) eq.setBandLevel(band,if(enabled) boost else 0)
                    }
                    eq.enabled=enabled
                    bassEqualizer=eq
                } else eq.release()
            } catch (_:Exception) {}
            if(bassBoost==null && bassEqualizer==null) {
                Toast.makeText(this,"Efek bass tidak didukung oleh sistem audio HP ini",Toast.LENGTH_LONG).show()
            }
        } catch (_:Exception) {
            try { bassBoost?.release() } catch (_:Exception) {}
            try { bassEqualizer?.release() } catch (_:Exception) {}
            bassBoost=null
            bassEqualizer=null
        }
    }

    private fun applyBassSettings(enabled:Boolean,strength:Int) {
        val amount=strength.coerceIn(0,1000)
        val active=enabled && amount>0
        try {
            bassBoost?.let {
                if(it.hasControl()) {
                    it.setStrength(amount.toShort())
                    it.enabled=active
                }
            }
        } catch (_:Exception) {}
        try {
            bassEqualizer?.let { eq ->
                if(eq.hasControl()) {
                    val range=eq.bandLevelRange
                    val maxBoost=range[1].toInt().coerceAtLeast(0)
                    val boost=(maxBoost*(amount/1000f)).toInt().coerceIn(0,maxBoost).toShort()
                    for(i in 0 until eq.numberOfBands.toInt()) {
                        val band=i.toShort()
                        if(eq.getCenterFreq(band) in 20..250) eq.setBandLevel(band,if(active) boost else 0)
                    }
                    eq.enabled=active
                }
            }
        } catch (_:Exception) {}
    }

    private fun showBassSettings() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(18),dp(22),dp(12)); background=panelBg(Color.rgb(25,24,36),18) }
        box.addView(label("BASS BOOST",18f,Color.WHITE,true))
        box.addView(label("Perkuat suara bass saat musik diputar",12f,muted).apply{setPadding(0,dp(5),0,dp(14))})
        val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
        val enabled=CheckBox(this).apply { text="Aktifkan efek bass"; setTextColor(Color.WHITE); buttonTintList=android.content.res.ColorStateList.valueOf(accent); isChecked=prefs.getBoolean("bass_enabled",true) }
        box.addView(enabled)
        val value=label("${prefs.getInt("bass_strength",700)/10}%",13f,Color.WHITE,true).apply{gravity=Gravity.END}
        box.addView(value)
        val seek=SeekBar(this).apply { max=1000; progress=prefs.getInt("bass_strength",700).coerceIn(0,1000); progressTintList=android.content.res.ColorStateList.valueOf(accent); thumbTintList=android.content.res.ColorStateList.valueOf(accent) }
        box.addView(seek)
        fun applyBass() {
            val strength=seek.progress
            val on=enabled.isChecked && strength>0
            prefs.edit().putInt("bass_strength",strength).putBoolean("bass_enabled",enabled.isChecked).apply()
            value.text="${strength/10}%"
            if(player!=null) {
                if(bassBoost==null && bassEqualizer==null) setupBassBoost(player!!.audioSessionId)
                applyBassSettings(enabled.isChecked,strength)
                if(bassBoost==null && bassEqualizer==null)
                    Toast.makeText(this,"HP tidak menyediakan efek bass untuk sesi audio ini",Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this,"Putar musik dulu agar efek bass bisa diterapkan",Toast.LENGTH_SHORT).show()
            }
        }
        enabled.setOnCheckedChangeListener { _,_->applyBass() }
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){if(fromUser)applyBass()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })
        AlertDialog.Builder(this).setView(box).setPositiveButton("Selesai",null).show().window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
    private fun togglePlay(){val p=player;if(p==null){if(songs.isNotEmpty())playSong(0)}else if(p.isPlaying){p.pause();miniPlay.text="▶";updateMediaNotification(false)}else{p.start();miniPlay.text="Ⅱ";updateMediaNotification(true)}}
    private fun nextSong(){playNextInQueue()}
    private fun playNextInQueue(){
        if(songs.isEmpty())return
        val queue=playbackQueue
        if(queue.isNullOrEmpty()){playSong(if(current<0)0 else(current+1)%songs.size);return}
        val currentId=songs.getOrNull(current)?.id
        val at=queue.indexOf(currentId)
        val nextId=queue.getOrNull(if(at<0)0 else (at+1)%queue.size) ?: return
        val nextIndex=songs.indexOfFirst{it.id==nextId}
        if(nextIndex>=0)playSong(nextIndex) else {queue.remove(nextId);playNextInQueue()}
    }
    private fun showNowPlaying(){
        if(current !in songs.indices)return
        val s=songs[current]
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(22),dp(22),dp(22));background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF24203B.toInt(),bg))}
        box.addView(ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageDrawable(miniArt.drawable)},LinearLayout.LayoutParams(dp(210),dp(210)))
        box.addView(centered(s.title,19f,Color.WHITE,true).apply{setPadding(0,dp(18),0,dp(4))})
        box.addView(centered(s.artist,13f,muted))
        val durationMs = (s.duration).coerceAtLeast(1L)
        val timeLabels = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val elapsedLabel = label(clock(player?.currentPosition?.toLong() ?: 0L),11f,muted)
        val remainingLabel = label(clock(durationMs),11f,muted).apply { gravity=Gravity.END }
        timeLabels.addView(elapsedLabel,LinearLayout.LayoutParams(0,-2,1f))
        timeLabels.addView(remainingLabel,LinearLayout.LayoutParams(0,-2,1f))
        box.addView(timeLabels,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
        val positionBar = SeekBar(this).apply {
            max=1000
            progress=(((player?.currentPosition?.toLong() ?: 0L).coerceIn(0L,durationMs)*1000L)/durationMs).toInt()
            progressTintList=android.content.res.ColorStateList.valueOf(accent)
            thumbTintList=android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                    if(fromUser) {
                        val target=durationMs*progress/1000L
                        try { player?.seekTo(target.toInt()); elapsedLabel.text=clock(target) } catch (_:Exception) {}
                        updateMediaNotification(player?.isPlaying==true)
                    }
                }
                override fun onStartTrackingTouch(seekBar:SeekBar?) {}
                override fun onStopTrackingTouch(seekBar:SeekBar?) {}
            })
        }
        box.addView(positionBar,LinearLayout.LayoutParams(-1,dp(32)))
        val controls=LinearLayout(this).apply{gravity=Gravity.CENTER}
        controls.addView(centered("|◀",27f).apply{setOnClickListener{if(songs.isNotEmpty())playSong(if(current<=0)songs.lastIndex else current-1)}},LinearLayout.LayoutParams(dp(65),dp(60)))
        controls.addView(centered(if(player?.isPlaying==true)"Ⅱ" else "▶",34f).apply{setOnClickListener{togglePlay();text=if(player?.isPlaying==true)"Ⅱ" else "▶"}},LinearLayout.LayoutParams(dp(70),dp(60)))
        controls.addView(centered("▶|",27f).apply{setOnClickListener{nextSong()}},LinearLayout.LayoutParams(dp(65),dp(60)))
        box.addView(controls)
        box.addView(centered("♫  LIHAT LIRIK",13f,Color.WHITE,true).apply {
            background=panelBg(Color.rgb(55,39,78),22)
            setPadding(dp(22),dp(11),dp(22),dp(11))
            setOnClickListener { showLyrics(s) }
        },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(10) })

        box.addView(label("VOLUME",12f,muted,true).apply{setPadding(0,dp(16),0,dp(2))})
        val volumeValue=TextView(this).apply{text= "${(playerVolume*100).toInt()}%";setTextColor(Color.WHITE);textSize=12f;gravity=Gravity.END}
        val volumeBar=SeekBar(this).apply{
            max=100
            progress=(playerVolume*100).toInt()
            progressTintList=android.content.res.ColorStateList.valueOf(accent)
            thumbTintList=android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){
                    if(fromUser){playerVolume=progress/100f;player?.setVolume(playerVolume,playerVolume);volumeValue.text="$progress%"}
                }
                override fun onStartTrackingTouch(seekBar:SeekBar?){}
                override fun onStopTrackingTouch(seekBar:SeekBar?){}
            })
        }
        val volumeRow=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        volumeRow.addView(volumeValue);volumeRow.addView(volumeBar)
        box.addView(volumeRow,LinearLayout.LayoutParams(-1,-2))

        box.addView(label("BASS BOOST",12f,muted,true).apply{setPadding(0,dp(12),0,dp(2))})
        val savedBass=getSharedPreferences("musik_player",MODE_PRIVATE).getInt("bass_strength",500)
        val bassValue=TextView(this).apply{text="${(savedBass/10)}%";setTextColor(Color.WHITE);textSize=12f;gravity=Gravity.END}
        val bassBar=SeekBar(this).apply{
            max=1000
            progress=savedBass
            progressTintList=android.content.res.ColorStateList.valueOf(accent)
            thumbTintList=android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){
                    if(fromUser){
                        bassValue.text="${progress/10}%"
                        getSharedPreferences("musik_player",MODE_PRIVATE).edit().putInt("bass_strength",progress).apply()
                        val bassOn=getSharedPreferences("musik_player",MODE_PRIVATE).getBoolean("bass_enabled",true)
                        if(bassBoost==null && bassEqualizer==null && player!=null) setupBassBoost(player!!.audioSessionId)
                        applyBassSettings(bassOn,progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar:SeekBar?){}
                override fun onStopTrackingTouch(seekBar:SeekBar?){}
            })
        }
        box.addView(bassValue);box.addView(bassBar)
        val dialog=AlertDialog.Builder(this).setView(box).setPositiveButton("Tutup",null).create()
        val positionTicker=object:Runnable {
            override fun run() {
                if(!dialog.isShowing)return
                val active=player
                if(active!=null && current in songs.indices && songs[current].id==s.id) {
                    val pos=try { active.currentPosition.toLong().coerceIn(0L,durationMs) } catch (_:Exception) { 0L }
                    elapsedLabel.text=clock(pos)
                    remainingLabel.text=clock(durationMs)
                    if(!positionBar.isPressed) positionBar.progress=(pos*1000L/durationMs).toInt()
                }
                handler.postDelayed(this,300)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(positionTicker) }
        dialog.show()
        handler.post(positionTicker)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showLyrics(song:Song) {
        val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
        val key="lyrics_${song.id}"
        val input=EditText(this).apply {
            setText(prefs.getString(key,"") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint="Tempel atau tulis lirik di sini. Format LRC didukung untuk lirik berjalan.\n\n[00:12.50]Baris lirik pertama"
            textSize=14f
            gravity=Gravity.TOP
            minLines=8
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=panelBg(Color.rgb(30,29,42),12)
        }
        val live=label("Lirik akan tampil di sini. Kamu bisa menambahkan lirik sendiri atau menempel teks LRC.",14f,Color.WHITE).apply {
            gravity=Gravity.CENTER
            setPadding(dp(14),dp(20),dp(14),dp(20))
            minHeight=dp(105)
        }
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(14),dp(18),dp(8))
            background=panelBg(Color.rgb(24,23,35),18)
            addView(label(song.title,17f,Color.WHITE,true))
            addView(label(song.artist,12f,muted).apply { setPadding(0,dp(3),0,dp(10)) })
            addView(live,LinearLayout.LayoutParams(-1,-2))
            addView(label("LIRIK LAGU",11f,accent,true).apply { setPadding(0,dp(10),0,dp(5)) })
            addView(input,LinearLayout.LayoutParams(-1,dp(210)))
        }
        val dialog=AlertDialog.Builder(this).setTitle("Lirik Musik").setView(box)
            .setNegativeButton("Tutup",null)
            .setNeutralButton("Simpan lirik",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text=input.text.toString().trim()
                prefs.edit().putString(key,text).apply()
                Toast.makeText(this,"Lirik tersimpan untuk lagu ini",Toast.LENGTH_SHORT).show()
                updateLyricsPreview(text,live)
            }
        }
        updateLyricsPreview(input.text.toString(),live)
        val sync=object:Runnable {
            override fun run() {
                if(!dialog.isShowing)return
                val text=prefs.getString(key,"") ?: ""
                val lines=text.lines()
                val position=player?.takeIf{current in songs.indices && songs[current].id==song.id}?.currentPosition ?: 0
                val timed=lines.mapNotNull { line ->
                    val match=Regex("""\[(\d{1,2}):(\d{2})(?:[.](\d{1,3}))?]""").find(line)
                    if(match==null)null else {
                        val min=match.groupValues[1].toLongOrNull()?:0L
                        val sec=match.groupValues[2].toLongOrNull()?:0L
                        val fraction=match.groupValues[3].padEnd(3,'0').take(3).toLongOrNull()?:0L
                        (min*60000+sec*1000+fraction) to line.substring(match.range.last+1).trim()
                    }
                }.sortedBy{it.first}
                if(timed.isNotEmpty()) {
                    val currentLine=timed.lastOrNull{it.first<=position}
                    live.text=currentLine?.second?.ifBlank{"♪"} ?: "Menunggu lirik..."
                } else live.text=text.ifBlank{"Lirik belum ditambahkan"}
                handler.postDelayed(this,400)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(sync) }
        dialog.show()
        handler.post(sync)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun updateLyricsPreview(text:String,target:TextView) {
        val timed=text.lines().mapNotNull { line ->
                    val match=Regex("""\[(\d{1,2}):(\d{2})(?:[.](\d{1,3}))?]""").find(line)
            if(match==null)null else {
                val min=match.groupValues[1].toLongOrNull()?:0L
                val sec=match.groupValues[2].toLongOrNull()?:0L
                val frac=match.groupValues[3].padEnd(3,'0').take(3).toLongOrNull()?:0L
                (min*60000+sec*1000+frac) to line.substring(match.range.last+1).trim()
            }
        }.sortedBy{it.first}
        val pos=if(current in songs.indices && songs[current].id==lyricsCurrentSongId) player?.currentPosition?:0 else 0
        target.text=timed.lastOrNull{it.first<=pos}?.second?.ifBlank{"♪"} ?: text.lines().firstOrNull{it.isNotBlank()} ?: "Lirik belum ditambahkan"
    }

    private var lyricsCurrentSongId:Long = -1L

    private fun setupMediaControls() {
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(NotificationChannel(channelId, "Kontrol Musik", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kontrol pemutar musik di panel notifikasi"
                setShowBadge(false)
            })
        }
        mediaSession = MediaSession(this, "MusikPlayerSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (player == null && songs.isNotEmpty()) playSong(if (current < 0) 0 else current) else if (player?.isPlaying == false) togglePlay() }
                override fun onPause() { if (player?.isPlaying == true) togglePlay() }
                override fun onSkipToNext() { nextSong() }
                override fun onSeekTo(pos:Long) {
                    try {
                        val active=player ?: return
                        val target=pos.coerceIn(0L,active.duration.toLong())
                        active.seekTo(target.toInt())
                        updateMediaNotification(active.isPlaying)
                    } catch (_:Exception) {}
                }
                override fun onSkipToPrevious() { if (songs.isNotEmpty()) { val q=playbackQueue; if(q.isNullOrEmpty()) playSong(if(current<=0)songs.lastIndex else current-1) else { val at=q.indexOf(songs.getOrNull(current)?.id); val id=q.getOrNull(if(at<=0) q.lastIndex else at-1); val ix=songs.indexOfFirst{it.id==id}; if(ix>=0)playSong(ix) } } }
            })
            isActive = true
        }
    }

    private fun mediaActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMediaNotification(playing: Boolean) {
        if (current !in songs.indices) return
        val song = songs[current]
        val session = mediaSession ?: return
        val duration = song.duration
        val position = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
        session.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration)
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, if (playing) 1f else 0f)
            .build())
        val previous = Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", mediaActionIntent(actionPrevious, 1)).build()
        val playPause = Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Jeda" else "Putar", mediaActionIntent(actionToggle, 2)).build()
        val next = Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", mediaActionIntent(actionNext, 3)).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.app_icon)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon((miniArt.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
            .setContentIntent(mediaActionIntent("", 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(previous)
            .addAction(playPause)
            .addAction(next)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        notificationManager.notify(77, builder.build())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            actionPrevious -> if (songs.isNotEmpty()) { val q=playbackQueue; if(q.isNullOrEmpty()) playSong(if(current<=0)songs.lastIndex else current-1) else { val at=q.indexOf(songs.getOrNull(current)?.id); val id=q.getOrNull(if(at<=0) q.lastIndex else at-1); val ix=songs.indexOfFirst{it.id==id}; if(ix>=0)playSong(ix) } }
            actionToggle -> togglePlay()
            actionNext -> nextSong()
        }
        intent.action = null
    }

    private fun clock(ms:Long)=String.format("%d:%02d",TimeUnit.MILLISECONDS.toMinutes(ms),TimeUnit.MILLISECONDS.toSeconds(ms)%60)
    private fun sizeLabel(bytes:Long)=if(bytes>=1048576)String.format("%.1f MB",bytes/1048576.0) else (bytes/1024).toString()+" KB"
    override fun onDestroy(){
        ticker?.let{handler.removeCallbacks(it)}
        if(isFinishing) stopPlayback()
        mediaSession?.isActive=false
        mediaSession?.release()
        mediaSession=null
        super.onDestroy()
    }
}
