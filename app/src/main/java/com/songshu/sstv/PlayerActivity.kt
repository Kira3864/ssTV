package com.songshu.sstv

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.songshu.sstv.cache.*
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.CachePicker
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.PlaybackSpeedSlider
import com.songshu.sstv.cast.*
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ThemedActivity() {
    private lateinit var view: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var btnEp: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var seek: SeekBar
    private lateinit var speedSlider: PlaybackSpeedSlider
    private val eps = ArrayList<String>()
    private var index = 0
    private var vodName = ""
    private var pic = ""
    private var api = ""
    private var srcName = ""
    private var vid = ""
    private var lineName = ""
    private var wantSeek = 0L
    private var firstPlay = true
    private var playRequest = 0
    private var resolvedUrl = ""
    private var resolved = false
    private var foreground = false
    private var wantsPlayback = false
    private var locked = false
    private var scrubbing = false
    private var controlsVisible = true
    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("sstv_player", MODE_PRIVATE) }
    private var speed = 1f
    private var episodeDialog: Dialog? = null
    private var castDialog: Dialog? = null
    private var discovery: Future<*>? = null
    private var remote: CastDevice? = null
    private val castCommands = Executors.newSingleThreadExecutor()
    private var castGeneration = 0
    private var remotePlaying = false
    private var remotePosition = 0L
    private var remoteDuration = 0L
    private var pollPending = false
    private var lastPoll = 0L
    private var pollFailures = 0
    private var lastSave = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        view.useController = false
        player = ExoPlayer.Builder(this).build()
        view.player = player
        seek = findViewById(R.id.playerSeek)
        btnEp = findViewById(R.id.btnEp)
        btnSpeed = findViewById(R.id.btnSpeed)
        speedSlider = findViewById(R.id.speedSlider)
        speed = prefs.getFloat("speed", 1f).takeIf { it.isFinite() && it in .5f..5f } ?: 1f
        player.setPlaybackSpeed(speed)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { updateControls() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && foreground && wantsPlayback && index + 1 < eps.size) play(index + 1)
            }
            override fun onPlayerError(error: PlaybackException) { showToast("播放失败，可尝试换源") }
        })
        findViewById<View>(R.id.btnBack).setOnClickListener { leaveFullscreenOrFinish() }
        findViewById<View>(R.id.btnFull).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPlayPause).setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.btnNext).setOnClickListener { if (index + 1 < eps.size) play(index + 1) }
        findViewById<View>(R.id.btnCache).setOnClickListener { cacheCurrent() }
        findViewById<View>(R.id.btnCast).setOnClickListener { showCastPicker() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(!locked) }
        findViewById<View>(R.id.btnFavorite).setOnClickListener { if (eps.isNotEmpty()) com.songshu.sstv.ui.LibraryActions.favorite(this, mediaRef()) { updateFavorite() } }
        findViewById<View>(R.id.btnLike).setOnClickListener {
            if (eps.isNotEmpty()) { LibraryStore.like(this, mediaRef(), position()); showToast("已喜欢 ${fmtDur(position())}"); it.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start() }
        }
        findViewById<View>(R.id.btnClearPlayback).setOnClickListener {
            Dlg.confirm(this, "清空播放内容", "同时删除这部影片的相关播放历史？") { clearPlayback() }
        }
        btnEp.setOnClickListener { showEpisodes() }
        btnSpeed.setOnClickListener {
            if (remote != null) { showToast("投屏倍速由电视控制"); return@setOnClickListener }
            val panel = findViewById<View>(R.id.speedPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showControls(true)
            if (panel.visibility == View.VISIBLE) speedSlider.setSpeed(speed)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; main.removeCallbacks(hideControls) }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) findViewById<TextView>(R.id.playerTime).text = "${fmtDur(duration() * value / 1000)} / ${fmtDur(duration())}"
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seekTo(duration() * bar.progress / 1000); scrubbing = false; showControls(true)
            }
        })
        speedSlider.setSpeed(speed)
        speedSlider.onSpeedSelected = { setSpeed(it) }
        bindVideoGestures()
        if (savedInstanceState != null) intent = Intent(intent).putExtras(savedInstanceState).putExtra("startPaused", true)
        loadIntent(intent)
        applySystemBars()
    }

    private fun loadIntent(next: Intent) {
        eps.clear(); eps.addAll(next.getStringArrayListExtra("eps").orEmpty())
        vodName = next.getStringExtra("title").orEmpty(); pic = next.getStringExtra("pic").orEmpty()
        api = next.getStringExtra("api").orEmpty(); srcName = next.getStringExtra("srcName").orEmpty()
        vid = next.getStringExtra("vid").orEmpty(); lineName = next.getStringExtra("line").orEmpty()
        index = next.getIntExtra("index", 0).coerceIn(0, (eps.size - 1).coerceAtLeast(0))
        wantSeek = next.getLongExtra("seek", 0L); firstPlay = true; watchVod = null
        if (eps.isNotEmpty()) PlaybackPreferences.setCleared(this, false)
        updateFavorite()
        bindWatchPage(); loadWatchInfo()
        if (eps.isNotEmpty()) play(index, false, !next.getBooleanExtra("startPaused", false))
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        if (next.getBooleanExtra("resumeExisting", false)) {
            pausePlayback()
            if (eps.isEmpty() && PlaybackPreferences.restoreLast(this) && !PlaybackPreferences.cleared(this)) loadIntent(next)
            return
        }
        saveHis(); ++playRequest; ++sourceRequest
        player.stop(); disconnectCast(false); intent = next; setLocked(false)
        loadIntent(next)
    }

    private fun updateContentVisibility() {
        val hasContent = eps.isNotEmpty()
        findViewById<View>(R.id.videoStage).visibility = if (hasContent) View.VISIBLE else View.GONE
        findViewById<View>(R.id.playerDetails).visibility = if (hasContent && !isLandscape()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.emptyPlayback).visibility = if (hasContent) View.GONE else View.VISIBLE
        findViewById<View>(R.id.watchFind).visibility = if (hasContent) View.GONE else View.VISIBLE
    }

    private fun play(i: Int, savePrevious: Boolean = true, autoplay: Boolean = true) {
        if (i !in eps.indices) return
        if (savePrevious) saveHis()
        val token = ++playRequest
        player.stop(); resolved = false; wantsPlayback = autoplay
        index = i; bindWatchPage(); showControls(true)
        val raw = eps[i].substringAfter('\u0001', "")
        val start = seekTargetFor(raw); firstPlay = false
        val finish: (String) -> Unit = { url ->
            if (token == playRequest && !isFinishing && !isDestroyed) {
                resolvedUrl = url; resolved = true
                if (remote != null) castMedia(remote!!, start) else startPlay(url, start)
            }
        }
        if (raw.startsWith("file:") || raw.startsWith("content:")) finish(raw)
        else Bg.io.execute {
            val result = runCatching { PlayResolver.resolve(raw) }
            showResult {
                if (token != playRequest) return@showResult
                result.onSuccess(finish).onFailure { wantsPlayback = false; updateControls(); showToast("无法解析播放地址") }
            }
        }
    }

    private fun startPlay(url: String, startAt: Long) {
        runCatching {
            player.setMediaSource(buildSource(url)); player.setPlaybackSpeed(speed)
            player.prepare(); player.seekTo(startAt.coerceAtLeast(0))
            player.playWhenReady = foreground && wantsPlayback
        }.onFailure { wantsPlayback = false; showToast("播放地址不可用") }
        updateControls()
    }

    private fun togglePlayPause() {
        if (eps.isEmpty()) return
        if (!resolved) { wantsPlayback = !wantsPlayback; updateControls(); return }
        val next = !(if (remote != null) remotePlaying else player.playWhenReady)
        wantsPlayback = next
        remote?.let { device ->
            remotePlaying = next
            castCommand {
                if (next) {
                    DlnaCast.play(device)
                    if (remotePosition > 0) runCatching { DlnaCast.seek(device, remotePosition) }
                } else DlnaCast.pause(device)
            }
        } ?: run {
            if (next) { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
            else player.pause()
        }
        updateControls(); showControls(true)
    }

    private fun pausePlayback() {
        wantsPlayback = false; player.pause(); remotePlaying = false
        remote?.let { device -> castCommand { DlnaCast.pause(device) } }
        updateControls()
    }
    private fun position() = if (remote != null) remotePosition else player.currentPosition.coerceAtLeast(0)
    private fun duration() = if (remote != null) remoteDuration else player.duration.coerceAtLeast(0)
    private fun seekTo(target: Long) {
        remote?.let { device -> remotePosition = target; castCommand { DlnaCast.seek(device, target) } }
            ?: player.seekTo(target.coerceAtLeast(0))
    }

    private fun updateControls() {
        if (!::player.isInitialized) return
        val playing = if (remote != null) remotePlaying else player.playWhenReady
        findViewById<ImageButton>(R.id.btnPlayPause).apply {
            setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
            contentDescription = if (playing) "暂停" else "播放"
            isEnabled = eps.isNotEmpty()
        }
        findViewById<View>(R.id.btnNext).visibility = if (!locked && index + 1 < eps.size) View.VISIBLE else View.GONE
        btnEp.visibility = if (!locked && eps.size > 1) View.VISIBLE else View.GONE
        btnSpeed.visibility = if (locked) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.title).text = listOf(vodName, eps.getOrNull(index)?.substringBefore('\u0001')).filterNot { it.isNullOrBlank() }.joinToString(" · ")
        btnSpeed.text = speedLabel(speed)
        seek.isEnabled = !locked && resolved && duration() > 0
        if (!scrubbing) {
            seek.progress = if (duration() > 0) (position() * 1000 / duration()).toInt().coerceIn(0, 1000) else 0
            findViewById<TextView>(R.id.playerTime).text = "${fmtDur(position())} / ${fmtDur(duration())}"
        }
        findViewById<ImageButton>(R.id.btnCast).setColorFilter(if (remote != null) themeColor(R.attr.appAccent) else Color.WHITE)
        if (playing && foreground) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val hideControls = Runnable { if (!locked && player.isPlaying && !scrubbing && findViewById<View>(R.id.speedPanel).visibility != View.VISIBLE) showControls(false) }
    private fun showControls(show: Boolean) {
        controlsVisible = show || locked
        findViewById<View>(R.id.videoControls).visibility = if (controlsVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.topBar).visibility = if (locked) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnLock).visibility = if (eps.isNotEmpty() && (isLandscape() || locked)) View.VISIBLE else View.GONE
        main.removeCallbacks(hideControls)
        if (controlsVisible && !locked) main.postDelayed(hideControls, 4000)
    }
    private fun setLocked(value: Boolean) {
        locked = value
        if (value) { episodeDialog?.dismiss(); findViewById<View>(R.id.speedPanel).visibility = View.GONE }
        if (value) {
            val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            detector.onTouchEvent(cancel); cancel.recycle()
        }
        gestureMode = 0
        if (holdingSpeed) { player.setPlaybackSpeed(speed); holdingSpeed = false }
        findViewById<View>(R.id.hudBox).visibility = View.GONE
        findViewById<ImageButton>(R.id.btnLock).apply {
            setImageResource(if (value) R.drawable.ic_player_lock else R.drawable.ic_player_unlock)
            contentDescription = if (value) "解锁屏幕" else "锁定屏幕"
        }
        updateControls()
        showControls(true)
    }

    private fun speedLabel(value: Float) = (if (value % 1f == 0f) value.toInt().toString() else value.toString()) + "×"
    private fun setSpeed(value: Float) {
        speed = value; player.setPlaybackSpeed(value); prefs.edit().putFloat("speed", value).apply()
        updateControls()
    }

    private fun showEpisodes() {
        if (eps.size <= 1) return
        findViewById<View>(R.id.speedPanel).visibility = View.GONE
        val dialog = Dialog(this); episodeDialog = dialog
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpWatch(18), dpWatch(12), dpWatch(18), dpWatch(18)); setBackgroundResource(R.drawable.bg_card) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply { text = "选集"; gravity = Gravity.CENTER_VERTICAL; textSize = 19f; setTextColor(themeColor(R.attr.appText)) }, LinearLayout.LayoutParams(0, dpWatch(42), 1f))
        heading.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_player_close); setColorFilter(themeColor(R.attr.appTextMuted))
            setBackgroundColor(Color.TRANSPARENT); setPadding(dpWatch(9), dpWatch(9), dpWatch(9), dpWatch(9))
            contentDescription = "关闭选集"; setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dpWatch(40), dpWatch(40)))
        box.addView(heading)
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val columns = if (isLandscape()) 3 else 4
        var current: View? = null
        eps.chunked(columns).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this)
            chunk.forEachIndexed { column, entry ->
                val target = rowIndex * columns + column
                val button = watchButton(entry.substringBefore('\u0001'), target == index) { dialog.dismiss(); play(target) }
                row.addView(button, LinearLayout.LayoutParams(0, dpWatch(42), 1f).apply { setMargins(0, dpWatch(6), dpWatch(6), 0) })
                if (target == index) current = row
            }
            repeat(columns - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            rows.addView(row)
        }
        scroll.addView(rows)
        box.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .52f).toInt()))
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setGravity(if (isLandscape()) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
        dialog.show()
        dialog.window?.setLayout(if (isLandscape()) dpWatch(330) else resources.displayMetrics.widthPixels - dpWatch(20), -2)
        scroll.post { scroll.scrollTo(0, ((current?.top ?: 0) - scroll.height / 3).coerceAtLeast(0)) }
    }

    private var watchVod: com.songshu.sstv.data.Vod? = null
    private var sourceRequest = 0

    private fun bindWatchPage() {
        updateContentVisibility()
        updateControls()
        findViewById<TextView>(R.id.watchTitle).text = vodName.ifBlank { "尚未播放" }
        findViewById<TextView>(R.id.watchMeta).apply {
            text = watchVod?.meta.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.watchPlot).apply {
            text = watchVod?.content.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.watchFind).apply {
            visibility = if (eps.isEmpty()) View.VISIBLE else View.GONE
            setOnClickListener { com.songshu.sstv.ui.BottomNav.open(this@PlayerActivity, SearchActivity::class.java) }
        }
        findViewById<TextView>(R.id.playerSource).apply {
            text = "${srcName.ifBlank { "切换片源" }}  ▾"
            visibility = if (api.isBlank()) View.GONE else View.VISIBLE
            setOnClickListener {
                com.songshu.sstv.ui.SourcePicker.show(this@PlayerActivity, SourceStore.sources(this@PlayerActivity), api) { source ->
                    switchWatchSource(source)
                }
            }
        }
        val lines = findViewById<LinearLayout>(R.id.watchLines)
        lines.removeAllViews()
        watchVod?.takeIf { it.lines.size > 1 }?.let { vod ->
            val row = android.widget.HorizontalScrollView(this)
            val buttons = LinearLayout(this)
            vod.lines.forEach { line ->
                buttons.addView(watchButton(line.name, line.name == lineName) {
                    replaceWatchLine(vod, line)
                }, LinearLayout.LayoutParams(-2, dpWatch(36)).apply { rightMargin = dpWatch(8) })
            }
            row.addView(buttons)
            lines.addView(row)
        }
        val box = findViewById<LinearLayout>(R.id.watchEpisodes)
        box.removeAllViews()
        findViewById<View>(R.id.watchEpisodeLabel).visibility = if (eps.size > 1) View.VISIBLE else View.GONE
        if (eps.size > 1) eps.chunked(4).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this)
            chunk.forEachIndexed { col, entry ->
                val target = rowIndex * 4 + col
                row.addView(watchButton(entry.substringBefore('\u0001'), target == index) { play(target) },
                    LinearLayout.LayoutParams(0, dpWatch(40), 1f).apply { setMargins(0, 0, dpWatch(6), dpWatch(8)) })
            }
            repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            box.addView(row)
        }
    }

    private fun dpWatch(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun watchButton(label: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dpWatch(12), 0, dpWatch(12), 0)
        setTextColor(themeColor(if (selected) R.attr.appOnAccent else R.attr.appText))
        setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_chip)
        setOnClickListener { action() }
    }

    private fun loadWatchInfo() {
        if (api.isBlank() || vid.isBlank()) return
        val request = ++sourceRequest
        val endpoint = api; val id = vid; val source = srcName
        Bg.io.execute {
            val vod = runCatching { VodApi.detail(endpoint, id, source) }.getOrNull()
            showResult {
                if (request != sourceRequest || vod == null) return@showResult
                watchVod = vod
                bindWatchPage()
            }
        }
    }

    private fun switchWatchSource(source: com.songshu.sstv.data.Source) {
        val request = ++sourceRequest
        findViewById<TextView>(R.id.playerSource).text = "${source.name} · 正在查找"
        Bg.io.execute {
            val vod = runCatching {
                val hit = VodApi.search(source.api, vodName, source.name).list
                    .firstOrNull { it.name == vodName } ?: return@runCatching null
                VodApi.detail(source.api, hit.id, source.name)
            }.getOrNull()
            showResult {
                if (request != sourceRequest) return@showResult
                if (vod == null || vod.lines.none { it.episodes.isNotEmpty() }) {
                    bindWatchPage(); showToast("该片源没有可播放的同名影片")
                } else {
                    SourceStore.setCurrent(this, source)
                    replaceWatchLine(vod, vod.lines.first { it.episodes.isNotEmpty() })
                }
            }
        }
    }

    private fun replaceWatchLine(vod: com.songshu.sstv.data.Vod, line: com.songshu.sstv.data.PlayLine) {
        val previousName = eps.getOrNull(index)?.substringBefore('\u0001')
        val position = position()
        val wasPlaying = wantsPlayback
        saveHis()
        watchVod = vod; api = vod.sourceApi; srcName = vod.sourceName; vid = vod.id; lineName = line.name
        eps.clear(); eps.addAll(line.episodes.map { it.name + "\u0001" + it.url })
        wantSeek = position; firstPlay = true
        btnEp.visibility = if (eps.size > 1) View.VISIBLE else View.GONE
        play(line.episodes.indexOfFirst { it.name == previousName }.coerceAtLeast(0), false, wasPlaying)
    }


    private fun mediaRef() = MediaRef(vid, vodName, pic, api, srcName, ArrayList(eps), index, lineName)
    private fun updateFavorite() {
        val selected = eps.isNotEmpty() && LibraryStore.favorites(this).any { it.media.key == mediaRef().key }
        findViewById<ImageButton>(R.id.btnFavorite).apply { alpha = if (selected) 1f else .7f; contentDescription = if (selected) "已收藏" else "收藏"; setColorFilter(if (selected) 0xFFFFCC66.toInt() else Color.WHITE) }
    }
    private fun clearPlayback() {
        val media = mediaRef()
        ++playRequest; ++sourceRequest; pausePlayback(); disconnectCast(false)
        player.stop(); player.clearMediaItems()
        eps.clear(); resolved = false; resolvedUrl = ""; wantsPlayback = false; watchVod = null
        vodName = ""; pic = ""; api = ""; vid = ""; srcName = ""; lineName = ""; index = 0; wantSeek = 0L
        intent = Intent(this, PlayerActivity::class.java).putExtra("startPaused", true)
        HistoryStore.removeMedia(this, media); PlaybackPreferences.setCleared(this, true)
        bindWatchPage(); updateContentVisibility(); updateControls()
    }
    private fun saveHis() {
        if (!::player.isInitialized) return
        runCatching {
            if (index !in eps.indices) return
            val parts = eps[index].split("\u0001")
            val epName = parts.getOrNull(0) ?: ""
            val url = parts.getOrNull(1) ?: return
            if (!resolved) return
            val pos = position()
            val dur = duration()
            HistoryStore.put(
                this,
                HisItem(
                    key = url, title = vodName, ep = epName, pic = pic, url = url,
                    pos = pos, dur = if (dur > 0) dur else 0L,
                    time = System.currentTimeMillis(),
                    eps = ArrayList(eps),
                    api = api, srcName = srcName, vid = vid
                )
            )
        }
    }

    private fun seekTargetFor(url: String): Long {
        if (firstPlay && intent.hasExtra("seek")) return wantSeek.coerceAtLeast(0)
        val h = HistoryStore.byUrl(this, url, api) ?: return 0L
        if (h.pos < 20_000) return 0L
        if (h.dur > 0 && h.pos >= h.dur - 15_000) return 0L
        return h.pos
    }

    private fun buildSource(url: String): MediaSource {
        // 路径里可能带中文（综艺类很常见），先转义再交给播放器
        val safe = VodApi.encodeUrl(url)
        val local = safe.startsWith("file:") || safe.startsWith("content:")

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(VodApi.UA)
            .setDefaultRequestProperties(mapOf("Referer" to refererOf(safe)))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
            .setAllowCrossProtocolRedirects(true)

        val base: DataSource.Factory =
            if (local) DefaultDataSource.Factory(this) else http

        val ds: DataSource.Factory = if (!local && SourceStore.autoCache(this)) {
            val cache = CacheMgr.get(this)
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(base)
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory().setCache(cache)
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else base

        return if (!local && Mc.isHls(safe)) {
            HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(safe))
        } else {
            ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(safe))
        }
    }

    private fun cacheCurrent() {
        if (index !in eps.indices) return
        val pairs = eps.map { e ->
            val p = e.split("\u0001")
            p.getOrNull(0).orEmpty() to p.getOrNull(1).orEmpty()
        }
        if (pairs[index].second.startsWith("file:")) {
            showToast("这一集已经在本地了")
            return
        }
        if (pairs.size <= 1) {                 // 单集电影，没什么好挑的
            Dlg.confirm(
                this, "缓存「${pairs[index].first}」",
                "会完整下载这一集，存在 App 私有目录，可离线观看。\n下完之前别把 App 从后台划掉。"
            ) {
                startCache(listOf(pairs[index]))
            }
            return
        }

        val marks = Array(pairs.size) { i ->
            when {
                DlHub.isDownloading(pairs[i].second, api) -> CachePicker.Mark.DOING
                Downloader.localFile(this, pairs[i].second, api) != null -> CachePicker.Mark.DONE
                else -> CachePicker.Mark.NONE
            }
        }
        // 默认就勾当前这一集；已经下过的当前集也别勾（下面会提示"已经在本地"）
        val sel = BooleanArray(pairs.size) { it == index && marks[it] == CachePicker.Mark.NONE }
        CachePicker.show(this, "缓存「$vodName」", pairs, sel, marks) { picked ->
            startCache(picked)
        }
    }

    /** 真正开跑。正在下的剔掉 —— 两个任务写同一个 .part 会把文件搞坏 */
    private fun startCache(picked: List<Pair<String, String>>) {
        val todo = picked.filter { !DlHub.isDownloading(it.second, api) }
        if (todo.isEmpty()) {
            showToast("这几集正在缓存中，去「我的缓存」看进度")
            return
        }
        val task = DlHub.create(vodName, pic, todo, api, srcName, vid, eps.size > 1)
        showToast(
            if (todo.size == 1) "开始缓存 ${todo[0].first}，进度在「我的缓存」里看"
            else "开始缓存 ${todo.size} 集，进度在「我的缓存」里看"
        )
        Bg.dl.execute { DlRunner.run(this, task) }
    }


    private fun refererOf(url: String) = runCatching { val parsed = URL(url); "${parsed.protocol}://${parsed.host}/" }.getOrDefault("")
    private fun showToast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun toggleFullscreen() {
        if (locked) setLocked(false)
        requestedOrientation = if (isLandscape()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    private fun applySystemBars() {
        val full = isLandscape() && eps.isNotEmpty()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !full)
        if (full) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller.show(WindowInsetsCompat.Type.systemBars())
        findViewById<View>(R.id.videoStage).layoutParams = findViewById<View>(R.id.videoStage).layoutParams.apply {
            height = if (full) -1 else (resources.configuration.screenWidthDp * resources.displayMetrics.density * 9 / 16).toInt()
        }
        findViewById<View>(R.id.playerNavigation).visibility = if (full) View.GONE else View.VISIBLE
        findViewById<View>(R.id.speedPanel).layoutParams = findViewById<View>(R.id.speedPanel).layoutParams.apply {
            width = dpWatch((resources.configuration.screenWidthDp - 24).coerceIn(120, 304))
        }
        findViewById<ImageButton>(R.id.btnFull).apply {
            setImageResource(if (full) R.drawable.ic_player_collapse else R.drawable.ic_player_fullscreen)
            contentDescription = if (full) "退出全屏" else "全屏"
        }
        updateContentVisibility(); showControls(true)
    }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        if (!isLandscape() && locked) setLocked(false)
        episodeDialog?.dismiss(); findViewById<View>(R.id.speedPanel).visibility = View.GONE
        applySystemBars()
    }
    private fun leaveFullscreenOrFinish() {
        when {
            findViewById<View>(R.id.speedPanel).visibility == View.VISIBLE -> findViewById<View>(R.id.speedPanel).visibility = View.GONE
            isLandscape() -> toggleFullscreen()
            locked -> setLocked(false)
            else -> finish()
        }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { leaveFullscreenOrFinish() }

    private var dismissSpeedTouch = false
    private var downX = 0f
    private var downY = 0f
    private var downPosition = 0L
    private var downVolume = 0
    private var downBrightness = .5f
    private var gestureMode = 0
    private var seekPreview = 0L
    private var holdingSpeed = false
    private val detector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (locked) return true
                findViewById<View>(R.id.videoGestures).performClick()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean { if (!locked) togglePlayPause(); return true }
            override fun onLongPress(event: MotionEvent) {
                if (!locked && player.isPlaying && remote == null && gestureMode == 0) {
                    holdingSpeed = true; player.setPlaybackSpeed(2f); showHud("临时倍速", "2×")
                }
            }
            override fun onScroll(start: MotionEvent?, event: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked) return true
                val deltaX = event.x - downX; val deltaY = event.y - downY
                val stage = findViewById<View>(R.id.videoGestures)
                if (gestureMode == 0) {
                    gestureMode = if (abs(deltaX) > abs(deltaY)) 3 else if (isLandscape()) {
                        if (downX < stage.width / 2f) 1 else 2
                    } else 0
                }
                if (holdingSpeed) { player.setPlaybackSpeed(speed); holdingSpeed = false }
                if (gestureMode == 3 && duration() > 0) {
                    seekPreview = (downPosition + deltaX / stage.width * 120_000).toLong().coerceIn(0, duration())
                    showHud("进度", fmtDur(seekPreview))
                } else if (gestureMode == 1) {
                    val value = (downBrightness - deltaY / stage.height).coerceIn(.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = value }
                    showHud("亮度", "${(value * 100).roundToInt()}%")
                } else if (gestureMode == 2) {
                    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val value = (downVolume - deltaY / stage.height * maximum).roundToInt().coerceIn(0, maximum)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    showHud("音量", "${value * 100 / maximum}%")
                }
                return true
            }
        })
    }
    // GestureDetector confirms taps through performClick; buttons use native hit testing above this view.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun bindVideoGestures() {
        findViewById<View>(R.id.videoGestures).apply {
            setOnClickListener { if (!locked) showControls(!controlsVisible) }
            setOnTouchListener { _, event -> handleVideoTouch(event) }
        }
    }
    private fun handleVideoTouch(event: MotionEvent): Boolean {
        if (locked) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val panel = findViewById<View>(R.id.speedPanel)
            dismissSpeedTouch = panel.visibility == View.VISIBLE
            if (dismissSpeedTouch) { panel.visibility = View.GONE; showControls(true) }
            downX = event.x; downY = event.y; downPosition = position(); gestureMode = 0
            downVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            downBrightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: (runCatching {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(.5f))
        }
        if (dismissSpeedTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dismissSpeedTouch = false
            return true
        }
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_UP && gestureMode == 3) seekTo(seekPreview)
            if (holdingSpeed) { player.setPlaybackSpeed(speed); holdingSpeed = false }
            gestureMode = 0
            main.postDelayed({ findViewById<View>(R.id.hudBox).visibility = View.GONE }, 800)
        }
        return true
    }
    private fun showHud(label: String, value: String) {
        findViewById<TextView>(R.id.hudLabel).text = label
        findViewById<TextView>(R.id.hudValue).text = value
        findViewById<View>(R.id.hudBox).visibility = View.VISIBLE
    }

    private fun showCastPicker() {
        if (!resolved || resolvedUrl.isBlank()) { showToast("请先选择并加载影片"); return }
        if (!resolvedUrl.startsWith("http")) { showToast("本地文件暂不支持投屏"); return }
        val dialog = Dialog(this); castDialog = dialog
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpWatch(20), dpWatch(16), dpWatch(20), dpWatch(20)); setBackgroundResource(R.drawable.bg_card) }
        box.addView(TextView(this).apply { text = "投屏 · DLNA"; textSize = 19f; setTextColor(themeColor(R.attr.appText)) })
        remote?.let { device ->
            box.addView(watchButton("断开 ${device.name}", false) { disconnectCast(); dialog.dismiss() }, LinearLayout.LayoutParams(-1, dpWatch(46)))
        }
        val status = TextView(this).apply { text = "正在查找同一 Wi-Fi 下的电视…"; textSize = 13f; setTextColor(themeColor(R.attr.appTextMuted)); setPadding(0, dpWatch(16), 0, dpWatch(12)) }
        box.addView(status)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, dpWatch(190)))
        val found = HashSet<String>()
        fun search() {
            discovery?.cancel(true); found.clear(); rows.removeAllViews(); status.text = "正在查找同一 Wi-Fi 下的电视…"
            discovery = Bg.io.submit {
                val result = runCatching {
                    DlnaCast.discover(applicationContext) { device ->
                        showResult {
                            if (dialog.isShowing && found.add(device.controlUrl)) {
                                rows.addView(watchButton(device.name, false) { dialog.dismiss(); castMedia(device, position()) }, LinearLayout.LayoutParams(-1, dpWatch(46)))
                            }
                        }
                    }
                }
                showResult {
                    if (dialog.isShowing) status.text = when {
                        result.isFailure -> "设备搜索失败，请检查 Wi-Fi 连接"
                        found.isEmpty() -> "未找到设备，请开启电视的 DLNA 投屏"
                        else -> "选择电视"
                    }
                }
            }
        }
        box.addView(watchButton("重新搜索", false) { search() }, LinearLayout.LayoutParams(-1, dpWatch(42)))
        dialog.setContentView(box); dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setGravity(Gravity.BOTTOM); dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dpWatch(20)).coerceAtMost(dpWatch(400)), -2)
        dialog.setOnDismissListener { discovery?.cancel(true) }
        search()
    }
    private fun castMedia(device: CastDevice, start: Long) {
        if (!resolved) return
        val previous = remote
        val shouldPlay = wantsPlayback && foreground
        val generation = ++castGeneration
        pollFailures = 0
        val length = duration()
        remote = device; remotePosition = start; remoteDuration = length; remotePlaying = false
        player.pause(); updateControls()
        val url = resolvedUrl; val title = vodName
        castCommands.execute {
            if (previous != null && previous != device) runCatching { DlnaCast.stop(previous) }
            val result = runCatching { DlnaCast.load(device, url, title, start, shouldPlay) }
            showResult {
                if (generation != castGeneration) return@showResult
                result.onSuccess { remotePlaying = shouldPlay && foreground; updateControls(); showToast("已连接 ${device.name}") }
                    .onFailure {
                        remote = null; wantsPlayback = false; startPlay(url, start)
                        updateControls(); showToast("投屏失败：${it.message}")
                    }
            }
        }
    }
    private fun castCommand(command: () -> Unit) {
        if (castCommands.isShutdown) return
        castCommands.execute {
            runCatching(command).onFailure {
                showResult { remotePlaying = false; updateControls(); showToast("电视未完成操作：${it.message}") }
            }
        }
    }
    private fun disconnectCast(restoreLocal: Boolean = true) {
        val device = remote ?: return
        ++castGeneration; remote = null; remotePlaying = false; wantsPlayback = false
        if (restoreLocal && resolved) startPlay(resolvedUrl, remotePosition)
        castCommand { DlnaCast.stop(device) }; updateControls()
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!foreground) return
            updateControls()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSave > 5000) { saveHis(); lastSave = now }
            remote?.let { device ->
                if (!pollPending && now - lastPoll > 2000) {
                    pollPending = true; lastPoll = now
                    val generation = castGeneration
                    castCommands.execute {
                        val result = runCatching { DlnaCast.position(device) }
                        showResult {
                            pollPending = false
                            if (remote == device && castGeneration == generation) result.onSuccess {
                                pollFailures = 0; remotePosition = it.position; remoteDuration = it.duration
                            }.onFailure {
                                if (++pollFailures >= 3) {
                                    ++castGeneration; remote = null; remotePlaying = false; wantsPlayback = false
                                    if (resolved) startPlay(resolvedUrl, remotePosition)
                                    updateControls(); showToast("投屏连接已断开")
                                }
                            }
                        }
                    }
                }
            }
            main.postDelayed(this, 500)
        }
    }
    override fun onResume() {
        super.onResume(); foreground = true
        updateFavorite()
        if (wantsPlayback && resolved && remote == null) player.play()
        main.removeCallbacks(ticker); main.post(ticker); showControls(true)
    }
    override fun onPause() {
        foreground = false; pausePlayback(); saveHis()
        episodeDialog?.dismiss(); castDialog?.dismiss()
        if (holdingSpeed) { player.setPlaybackSpeed(speed); holdingSpeed = false }
        main.removeCallbacks(ticker)
        findViewById<View>(R.id.speedPanel).visibility = View.GONE
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("eps", ArrayList(eps)); outState.putString("title", vodName); outState.putString("pic", pic)
        outState.putString("api", api); outState.putString("srcName", srcName); outState.putString("vid", vid); outState.putString("line", lineName)
        outState.putInt("index", index); outState.putLong("seek", position())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        ++playRequest; ++sourceRequest
        discovery?.cancel(true); main.removeCallbacksAndMessages(null)
        remote?.let { device -> castCommand { DlnaCast.pause(device) } }
        castCommands.shutdown(); player.release()
        super.onDestroy()
    }
}
