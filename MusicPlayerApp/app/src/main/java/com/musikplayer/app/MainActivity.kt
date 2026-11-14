package com.musikplayer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
    private var playerVolume = 1.0f
    private var current = -1
    private lateinit var count: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniArt: ImageView
    private lateinit var listBox: LinearLayout
    private lateinit var search: EditText
    private lateinit var playAllButton: TextView
    private var videoMode = false
    private var selectedTab = "Musik"
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
        if (r == 41 && g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) showLibrary()
        else if (r == 42) { /* Notifikasi opsional; pemutar tetap bisa digunakan. */ }
        else setContentView(label("Izinkan akses musik agar semua lagu di HP bisa ditemukan.",17f,Color.WHITE).apply { gravity=Gravity.CENTER })
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
                    if(name=="Video" || name=="Musik") {
                        selectedTab=name
                        tabsLoop@ for(i in 0 until tabs.childCount) {
                            val tab=tabs.getChildAt(i) as TextView
                            val active=tab.text.toString()==selectedTab
                            tab.setTextColor(if(active)Color.WHITE else muted)
                            tab.typeface=if(active)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            tab.background=if(active) GradientDrawable().apply { setColor(0x222D2140); setStroke(dp(1),accent); cornerRadius=dp(12).toFloat() } else null
                        }
                        videoMode=name=="Video"
                        playAllButton.visibility=if(videoMode)android.view.View.GONE else android.view.View.VISIBLE
                        if(videoMode)renderVideos(search.text.toString()) else renderSongs(search.text.toString())
                    } else Toast.makeText(this@MainActivity,"Bagian $name segera hadir",Toast.LENGTH_SHORT).show()
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
            setOnClickListener { if(songs.isNotEmpty()) playSong(0) else Toast.makeText(this@MainActivity,"Belum ada lagu",Toast.LENGTH_SHORT).show() }
        }
        actions.addView(playAllButton,LinearLayout.LayoutParams(0,dp(44),1f))
        actions.addView(centered("⤨  Acak",13f).apply { setOnClickListener { if(songs.isNotEmpty()){ songs.shuffle(); renderSongs(search.text.toString()); playSong(0) } } },LinearLayout.LayoutParams(dp(82),dp(44)))
        actions.addView(centered("♫ Bass",12f,accent,true).apply { setOnClickListener { showBassSettings() } },LinearLayout.LayoutParams(dp(72),dp(44)))
        section.addView(actions); root.addView(section)

        val scroll=ScrollView(this).apply { clipToPadding=false; setPadding(0,0,0,dp(8)) }
        listBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(8)) }
        scroll.addView(listBox); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        val mini=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(7),dp(10),dp(7)); background=panelBg(Color.rgb(31,29,43),15); setOnClickListener { showNowPlaying() } }
        miniArt=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.app_icon); background=panelBg(); clipToOutline=true }
        mini.addView(miniArt,LinearLayout.LayoutParams(dp(44),dp(44)))
        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        nowTitle=label("Pilih lagu untuk diputar",13f,Color.WHITE,true); nowArtist=label("Musik di perangkat kamu",11f,muted)
        info.addView(nowTitle); info.addView(nowArtist); mini.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        miniPlay=centered("▶",22f); miniPlay.setOnClickListener { togglePlay() }; mini.addView(miniPlay,LinearLayout.LayoutParams(dp(44),dp(44)))
        val miniWrap=LinearLayout(this).apply { setPadding(dp(12),dp(4),dp(12),dp(6)); addView(mini) }; root.addView(miniWrap)

        setContentView(root)
        renderSongs("")
        ticker=object:Runnable { override fun run(){ if(player?.isPlaying==true) miniPlay.text="Ⅱ"; handler.postDelayed(this,700) } }
        handler.post(ticker!!)
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
    private fun moveSongToFirst(song:Song) {
        val from=songs.indexOf(song)
        if(from<=0) return
        val item=songs.removeAt(from)
        songs.add(0,item)
        getSharedPreferences("musik_player",MODE_PRIVATE).edit().putString("song_order",songs.joinToString(","){it.id.toString()}).apply()
        renderSongs(search.text.toString())
        Toast.makeText(this,"Lagu dipindahkan ke urutan pertama",Toast.LENGTH_SHORT).show()
    }
    private fun moveSong(from:Int, delta:Int) {
        val to=from+delta
        if(from !in songs.indices || to !in songs.indices) return
        val item=songs.removeAt(from); songs.add(to,item)
        getSharedPreferences("musik_player", MODE_PRIVATE).edit().putString("song_order",songs.joinToString(","){it.id.toString()}).apply()
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
        val uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,video.id)
        val videoView=VideoView(this)
        val frame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isFocusable=true; isFocusableInTouchMode=true }
        frame.addView(videoView,FrameLayout.LayoutParams(-1,-1))
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
        videoView.setOnPreparedListener {
            it.isLooping=false
            val saved=getSharedPreferences("musik_player",MODE_PRIVATE).getInt(progressKey,0)
            if(saved in 1..99) videoView.seekTo((video.duration*saved/100).toInt())
            videoView.start()
            controller.show(2500)
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
        var lastSeekPosition=0
        var longPressHandled=false
        val gesture=android.view.GestureDetector(this,object:android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:android.view.MotionEvent)=true
            override fun onSingleTapConfirmed(e:android.view.MotionEvent):Boolean {
                if(controller.isShowing) controller.hide() else controller.show(3000)
                return true
            }
            override fun onDoubleTap(e:android.view.MotionEvent):Boolean {
                val jump=if(e.x<videoView.width/2) -20000 else 20000
                val target=(videoView.currentPosition+jump).coerceIn(0,videoView.duration.coerceAtLeast(0))
                videoView.seekTo(target)
                Toast.makeText(this@MainActivity,if(jump<0)"Mundur 20 detik" else "Maju 20 detik",Toast.LENGTH_SHORT).show()
                return true
            }
            override fun onLongPress(e:android.view.MotionEvent) {
                val jump=if(e.x<videoView.width/2) -10000 else 10000
                videoView.seekTo((videoView.currentPosition+jump).coerceIn(0,videoView.duration.coerceAtLeast(0)))
                longPressHandled=true
                Toast.makeText(this@MainActivity,if(jump<0)"Mundur 10 detik" else "Maju 10 detik",Toast.LENGTH_SHORT).show()
            }
            override fun onScroll(e1:android.view.MotionEvent?,e2:android.view.MotionEvent,dx:Float,dy:Float):Boolean {
                if(e1==null || kotlin.math.abs(e2.x-e1.x)<dp(12)) return false
                val delta=(-(e2.x-e1.x)*250).toInt()
                val target=(lastSeekPosition+delta).coerceIn(0,videoView.duration.coerceAtLeast(0))
                videoView.seekTo(target)
                return true
            }
        })
        videoView.setOnTouchListener { _,event ->
            if(event.action==android.view.MotionEvent.ACTION_DOWN) {
                lastSeekPosition=videoView.currentPosition
                longPressHandled=false
            }
            gesture.onTouchEvent(event)
            if(event.action==android.view.MotionEvent.ACTION_UP && !longPressHandled) {
                // GestureDetector menangani ketuk tunggal, ketuk dua kali, dan seret.
            }
            true
        }
        dialog.setOnDismissListener {
            saveProgress?.let{handler.removeCallbacks(it)}
            if(video.duration>0 && videoView.currentPosition>0 && videoView.currentPosition<video.duration) {
                val percent=(videoView.currentPosition*100L/video.duration).toInt().coerceIn(0,99)
                getSharedPreferences("musik_player",MODE_PRIVATE).edit().putInt(progressKey,percent).apply()
            }
            videoView.stopPlayback()
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(-1,-1)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun renderSongs(filter:String) {
        if(!::listBox.isInitialized)return
        listBox.removeAllViews()
        sectionTitle.text="Semua Lagu"
        val shown=songs.filter { it.title.contains(filter,true)||it.artist.contains(filter,true) }
        count.text=shown.size.toString()+" lagu ditemukan"
        if(shown.isEmpty()){ listBox.addView(centered(if(songs.isEmpty())"Belum ada file musik di HP" else "Lagu tidak ditemukan",14f,muted).apply{setPadding(0,dp(40),0,dp(40))}); return }
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
            row.addView(centered("⋮",22f).apply{setOnClickListener{AlertDialog.Builder(this@MainActivity).setTitle(song.title).setItems(arrayOf("Putar sekarang","Pindahkan ke urutan pertama","Hapus dari daftar aplikasi","Hapus lagu dari HP")){_,which->val pos=songs.indexOf(song);when(which){0->playSong(pos);1->moveSongToFirst(song);2->hideSongFromLibrary(song);3->confirmDeleteSong(song)}}.show()}},LinearLayout.LayoutParams(dp(30),dp(52)))
            row.setOnLongClickListener {
                val clip=android.content.ClipData.newPlainText("song_id",song.id.toString())
                if(Build.VERSION.SDK_INT>=24) row.startDragAndDrop(clip,android.view.View.DragShadowBuilder(row),song,0)
                else { row.startDrag(clip,android.view.View.DragShadowBuilder(row),song,0) }
                true
            }
            row.setOnDragListener { target,event ->
                when(event.action){
                    android.view.DragEvent.ACTION_DRAG_STARTED -> true
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { target.alpha=0.65f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { target.alpha=1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        target.alpha=1f
                        val draggedId=event.clipData.getItemAt(0).text.toString().toLongOrNull()
                        val from=songs.indexOfFirst{it.id==draggedId}
                        val to=songs.indexOf(song)
                        if(from>=0 && to>=0 && from!=to){
                            val moving=songs.removeAt(from); songs.add(to,moving)
                            getSharedPreferences("musik_player",MODE_PRIVATE).edit().putString("song_order",songs.joinToString(","){it.id.toString()}).apply()
                            renderSongs(search.text.toString())
                            Toast.makeText(this@MainActivity,"Lagu dipindahkan",Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { target.alpha=1f; true }
                    else -> true
                }
            }
            row.setOnClickListener{playSong(songs.indexOf(song))}
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
        return null
    }

    private fun markSongUnplayable(song:Song) {
        val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
        val hidden=prefs.getStringSet("hidden_song_ids",emptySet()).orEmpty().toMutableSet()
        hidden.add(song.id.toString())
        prefs.edit().putStringSet("hidden_song_ids",hidden).apply()
        songs.removeAll { it.id==song.id }
        prefs.edit().putString("song_order",songs.joinToString(","){it.id.toString()}).apply()
        if(::listBox.isInitialized) renderSongs(search.text.toString())
    }

    private fun playSong(index:Int, failedAttempts:Int=0) {
        if(songs.isEmpty())return
        if(failedAttempts>=songs.size) {
            player?.release()
            player=null
            miniPlay.text="▶"
            updateMediaNotification(false)
            Toast.makeText(this,"Tidak ada lagu berikutnya yang bisa diputar",Toast.LENGTH_LONG).show()
            return
        }
        val nextIndex=((index%songs.size)+songs.size)%songs.size
        current=nextIndex
        try {
            bassBoost?.release(); bassBoost = null
            player?.release()
            player=null
            val serviceIntent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            val s=songs[nextIndex]
            val newPlayer=MediaPlayer()
            player=newPlayer
            PlaybackService.retainedPlayer = newPlayer
            newPlayer.setDataSource(this,ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,s.id))
            newPlayer.setOnCompletionListener{mp->
                if(player===mp) playSong((current+1)%songs.size)
            }
            newPlayer.setOnErrorListener{mp,_,_->
                if(player===mp) {
                    val failedSong=songs.getOrNull(current)
                    player=null
                    PlaybackService.retainedPlayer = null
                    mp.release()
                    if(failedSong!=null) markSongUnplayable(failedSong)
                    if(songs.isNotEmpty()) playSong(current.coerceAtLeast(0)%songs.size,failedAttempts+1)
                    else { miniPlay.text="▶"; nowTitle.text="Tidak ada lagu yang bisa diputar" }
                }
                true
            }
            newPlayer.setOnPreparedListener{mp->
                if(player!==mp) {
                    mp.release()
                    return@setOnPreparedListener
                }
                if(mp.duration<=0) {
                    val badSong=songs.getOrNull(current)
                    player=null
                    PlaybackService.retainedPlayer=null
                    mp.release()
                    if(badSong!=null) markSongUnplayable(badSong)
                    if(songs.isNotEmpty()) playSong(current.coerceAtLeast(0)%songs.size,failedAttempts+1)
                    return@setOnPreparedListener
                }
                mp.setVolume(playerVolume,playerVolume)
                setupBassBoost(mp.audioSessionId)
                mp.start()
                miniPlay.text="Ⅱ"
                updateMediaNotification(true)
            }
            nowTitle.text=s.title
            nowArtist.text=s.artist
            miniArt.setImageBitmap(loadSongArtwork(s))
            updateMediaNotification(false)
            newPlayer.prepareAsync()
        }catch(_:Exception) {
            val badSong=songs.getOrNull(nextIndex)
            player?.release()
            player=null
            PlaybackService.retainedPlayer=null
            if(badSong!=null) markSongUnplayable(badSong)
            if(songs.isNotEmpty()) playSong(nextIndex.coerceAtLeast(0)%songs.size,failedAttempts+1)
            else { miniPlay.text="▶"; nowTitle.text="Tidak ada lagu yang bisa diputar" }
        }
    }
    private fun setupBassBoost(sessionId:Int) {
        try {
            bassBoost?.release()
            val prefs=getSharedPreferences("musik_player",MODE_PRIVATE)
            val strength=prefs.getInt("bass_strength",700).coerceIn(0,1000)
            val effect=android.media.audiofx.BassBoost(0,sessionId)
            if(!effect.hasControl()) {
                effect.release()
                bassBoost=null
                return
            }
            effect.setStrength(strength.toShort())
            effect.enabled=prefs.getBoolean("bass_enabled",true) && strength>0
            bassBoost=effect
        } catch (_:Exception) { bassBoost=null }
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
            try {
                if(player!=null) {
                    val effect=bassBoost
                    if(effect==null) setupBassBoost(player!!.audioSessionId)
                    else {
                        if(effect.hasControl()) {
                            effect.setStrength(strength.toShort())
                            effect.enabled=on
                        } else Toast.makeText(this,"Efek bass sedang dikendalikan sistem audio",Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this,"Putar musik dulu agar efek bass bisa diterapkan",Toast.LENGTH_SHORT).show()
                }
            } catch (_:Exception) { Toast.makeText(this,"Perangkat tidak mendukung efek Bass Boost ini",Toast.LENGTH_SHORT).show() }
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
    private fun nextSong(){if(songs.isNotEmpty())playSong(if(current<0)0 else(current+1)%songs.size)}
    private fun showNowPlaying(){
        if(current !in songs.indices)return
        val s=songs[current]
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(22),dp(22),dp(22));background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF24203B.toInt(),bg))}
        box.addView(ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageDrawable(miniArt.drawable)},LinearLayout.LayoutParams(dp(210),dp(210)))
        box.addView(centered(s.title,19f,Color.WHITE,true).apply{setPadding(0,dp(18),0,dp(4))})
        box.addView(centered(s.artist,13f,muted))
        val controls=LinearLayout(this).apply{gravity=Gravity.CENTER}
        controls.addView(centered("|◀",27f).apply{setOnClickListener{if(songs.isNotEmpty())playSong(if(current<=0)songs.lastIndex else current-1)}},LinearLayout.LayoutParams(dp(65),dp(60)))
        controls.addView(centered(if(player?.isPlaying==true)"Ⅱ" else "▶",34f).apply{setOnClickListener{togglePlay();text=if(player?.isPlaying==true)"Ⅱ" else "▶"}},LinearLayout.LayoutParams(dp(70),dp(60)))
        controls.addView(centered("▶|",27f).apply{setOnClickListener{nextSong()}},LinearLayout.LayoutParams(dp(65),dp(60)))
        box.addView(controls)

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
                        try{bassBoost?.setStrength(progress.toShort());bassBoost?.enabled=progress>0}catch(_:Exception){}
                    }
                }
                override fun onStartTrackingTouch(seekBar:SeekBar?){}
                override fun onStopTrackingTouch(seekBar:SeekBar?){}
            })
        }
        box.addView(bassValue);box.addView(bassBar)
        AlertDialog.Builder(this).setView(box).setPositiveButton("Tutup",null).show().window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

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
                override fun onSkipToPrevious() { if (songs.isNotEmpty()) playSong(if (current <= 0) songs.lastIndex else current - 1) }
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
            actionPrevious -> if (songs.isNotEmpty()) playSong(if (current <= 0) songs.lastIndex else current - 1)
            actionToggle -> togglePlay()
            actionNext -> nextSong()
        }
        intent.action = null
    }

    private fun clock(ms:Long)=String.format("%d:%02d",TimeUnit.MILLISECONDS.toMinutes(ms),TimeUnit.MILLISECONDS.toSeconds(ms)%60)
    private fun sizeLabel(bytes:Long)=if(bytes>=1048576)String.format("%.1f MB",bytes/1048576.0) else (bytes/1024).toString()+" KB"
    override fun onDestroy(){ticker?.let{handler.removeCallbacks(it)};bassBoost?.release();bassBoost=null;if(player?.isPlaying!=true){player?.release();player=null;PlaybackService.retainedPlayer=null};mediaSession?.isActive=false;mediaSession?.release();mediaSession=null;super.onDestroy()}
}
