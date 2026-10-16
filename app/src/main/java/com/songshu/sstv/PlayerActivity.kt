package com.songshu.sstv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import androidx.media3.ui.R as Media3R
import com.songshu.sstv.cache.CacheMgr
import com.songshu.sstv.cache.DlHub
import com.songshu.sstv.cache.DlRunner
import com.songshu.sstv.cache.Downloader
import com.songshu.sstv.data.HisItem
import com.songshu.sstv.data.HistoryStore
import com.songshu.sstv.data.Mc
import com.songshu.sstv.data.PlayResolver
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.ui.CachePicker
import com.songshu.sstv.ui.Dlg
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ThemedActivity() {

    private lateinit var view: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var titleTv: TextView
    private lateinit var toastTv: TextView
    private lateinit var btnEp: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnFull: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var speedPanel: GridLayout
    private lateinit var epPanel: FrameLayout
    private lateinit var epCard: LinearLayout
    private lateinit var epTitle: TextView
    private lateinit var epScroll: ScrollView
    private lateinit var epGrid: LinearLayout
    private lateinit var hudBox: LinearLayout
    private lateinit var hudLabel: TextView
    private lateinit var hudValue: TextView
    private lateinit var hudBar: ProgressBar

    private val eps = ArrayList<String>()
    private var index = 0
    private var vodName = ""
    private var pic = ""

    /** 这几个只为了往播放历史里存一份，将来能从历史回到详情页 */
    private var api = ""
    private var srcName = ""
    private var vid = ""
    private var lineName = ""

    /** 详情页点「继续播放」时带过来的位置；0 表示没带 */
    private var wantSeek = 0L
    private var firstPlay = true

    private val histHandler = Handler(Looper.getMainLooper())

    /**
     * 倍速档位。1.5 / 2 / 3 / 4 / 5 是要的，前后各补几个常用的，
     * 0.75 慢放看细节、1.25 看剧最舒服。
     */
    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f)
    private var speed = 1.0f

    /** 长按快进中（松手要还原） */
    private var fastForwarding = false
    private var longPressToken = 0
    private val longPressDelay = 400L

    private val prefs by lazy { getSharedPreferences("sstv_player", MODE_PRIVATE) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ── 顶栏自动隐藏 ──
    private val topHideMs = 5000L
    /** 写成匿名对象而不是 lambda —— lambda 里要用到自己（重排 3 秒），会被 Kotlin 判成类型递归 */
    private val hideTopRunnable = object : Runnable {
        override fun run() {
            // 正在挑倍速 / 挑集数就先别收，等面板关了再说
            if (speedPanel.visibility == View.VISIBLE || epPanel.visibility == View.VISIBLE)
                topBar.postDelayed(this, 3000L)
            else hideTopBar()
        }
    }
    private var topWasShown = false          // 按下那一刻顶栏是不是已经开着（用来做「再点一下收起」）
    private var tapCandidate = false         // 这次按下至今还没被判成长按/滑动 → 抬手时才算「点一下」

    // ── 双击暂停 / 播放（v1.12）──
    /** 上一次「抬手指」的时间与落点，用来判断紧接着这一下是不是双击的第二下 */
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** 当前这一下按下时，已经被认定是「双击的第二下」→ 抬手就暂停/继续 */
    private var doubleTapArmed = false
    /** 双击窗口：两下间隔比它短才算连着点；也正好当「单击晚一步执行」的等待时间 */
    private val doubleTapWindow = 300L
    /** 两次点击的落点差超过这个距离就不算双击（防两处乱点被当成双击） */
    private val doubleTapSlopPx by lazy { 40f * resources.displayMetrics.density }
    /** 单击（本来顶栏开着）→ 等双击窗口过了再收顶栏，免得双击时顶栏先闪走 */
    private val singleTapRunner = Runnable { hideTopBar() }

    // ── 选集面板 ──
    private var epBuilt = false              // 网格是「第一次点开选集」时才建的，省得进播放页先卡一下
    private val epBtns = ArrayList<TextView>()   // 下标 = 集号，用来标红当前集、滚动过去

    // ── 音量条 / 亮度条 / 快进快退 / 手势 ──
    private val hideHudRunnable = Runnable { hudBox.visibility = View.GONE }
    private var swipeSlop = 24f              // 超过这个距离才算「在滑」
    /** 按下时落哪半边：0=中间(不调亮度音量) 1=左(亮度) 2=右(音量) */
    private var gSide = 0
    /** 真正接管后的类型：0=没接管 1=亮度 2=音量 3=快进快退（横滑，不分左右） */
    private var gMode = 0
    /** 这次按下是不是有效的（没落在顶栏/按钮/底部进度条上）—— 无效就整段手势都不管 */
    private var gReady = false
    private var gStartY = 0f
    private var gStartX = 0f
    private var gStartBright = 0.5f
    private var gStartVol = 0
    /** 按下那一刻的播放位置和总时长，横滑要拿它当基准（拖动途中不 seek，松手才跳） */
    private var gStartPos = 0L
    private var gDur = 0L
    /** 这一划最终要跳多少秒，负数是后退 */
    private var gSeekSec = 0
    private var gMaxVol = 15

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = findViewById(R.id.player)
        slimController()          // 自带控制条瘦身：中间只留「播放/暂停」，齿轮也去掉
        titleTv = findViewById(R.id.title)
        toastTv = findViewById(R.id.toast)
        btnEp = findViewById(R.id.btnEp)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnFull = findViewById(R.id.btnFull)
        topBar = findViewById(R.id.topBar)
        speedPanel = findViewById(R.id.speedPanel)
        epPanel = findViewById(R.id.epPanel)
        epCard = findViewById(R.id.epCard)
        epTitle = findViewById(R.id.epTitle)
        epScroll = findViewById(R.id.epScroll)
        epGrid = findViewById(R.id.epGrid)
        hudBox = findViewById(R.id.hudBox)
        hudLabel = findViewById(R.id.hudLabel)
        hudValue = findViewById(R.id.hudValue)
        hudBar = findViewById(R.id.hudBar)

        swipeSlop = 22 * resources.displayMetrics.density
        gMaxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        vodName = intent.getStringExtra("title") ?: ""
        pic = intent.getStringExtra("pic") ?: ""
        api = intent.getStringExtra("api") ?: ""
        srcName = intent.getStringExtra("srcName") ?: ""
        vid = intent.getStringExtra("vid") ?: ""
        lineName = intent.getStringExtra("line") ?: ""
        wantSeek = intent.getLongExtra("seek", 0L)
        intent.getStringArrayListExtra("eps")?.let { eps.addAll(it) }
        index = intent.getIntExtra("index", 0).coerceIn(0, maxOf(0, eps.size - 1))

        player = ExoPlayer.Builder(this).build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (index + 1 < eps.size) play(index + 1) else showToast("已经是最后一集了")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showToast("播放失败（${error.errorCodeName}）· 换源或先缓存再试")
            }
        })

        // 上次选的倍速记着，下次打开还是它
        speed = prefs.getFloat("speed", 1.0f)
        applySpeed(speed, save = false)
        buildSpeedPanel()

        findViewById<View>(R.id.btnBack).setOnClickListener { resetTopTimer(); finish() }
        findViewById<View>(R.id.btnCache).setOnClickListener { resetTopTimer(); cacheCurrent() }
        btnEp.setOnClickListener { resetTopTimer(); showEpPanel() }
        // 点卡片外面那圈黑、或者右上角「关闭」，都把面板收掉
        epPanel.setOnClickListener { hideEpPanel() }
        epCard.setOnClickListener { /* 吃掉点击，免得点到卡片空白处也把面板关了 */ }
        findViewById<View>(R.id.btnEpClose).setOnClickListener { hideEpPanel() }
        btnSpeed.setOnClickListener {
            resetTopTimer()
            if (speedPanel.visibility == View.VISIBLE) hideSpeedPanel() else showSpeedPanel()
        }
        btnFull.setOnClickListener { resetTopTimer(); toggleFullscreen() }

        applySystemBars()
        bindWatchPage()
        loadWatchInfo()
        fitEpPanelHeight()

        // 电影、单集综艺这种只有一集的，不给「选集」按钮 —— 点开就一个格子，没意义
        btnEp.visibility = if (eps.size > 1) View.VISIBLE else View.GONE

        if (eps.isEmpty()) {
            showToast("没有可播放的地址")
        } else {
            play(index)
        }
    }

    override fun onNewIntent(next: android.content.Intent) {
        super.onNewIntent(next)
        if (next.getBooleanExtra("resumeExisting", false)) return
        val entries = next.getStringArrayListExtra("eps") ?: return
        saveHis()
        ++playRequest
        ++sourceRequest
        player.stop()
        intent = next
        eps.clear(); eps.addAll(entries)
        vodName = next.getStringExtra("title").orEmpty()
        pic = next.getStringExtra("pic").orEmpty()
        api = next.getStringExtra("api").orEmpty()
        srcName = next.getStringExtra("srcName").orEmpty()
        vid = next.getStringExtra("vid").orEmpty()
        lineName = next.getStringExtra("line").orEmpty()
        wantSeek = next.getLongExtra("seek", 0L)
        firstPlay = true
        watchVod = null
        epBuilt = false
        hideEpPanel()
        index = next.getIntExtra("index", 0).coerceIn(0, (eps.size - 1).coerceAtLeast(0))
        btnEp.visibility = if (eps.size > 1) View.VISIBLE else View.GONE
        bindWatchPage()
        loadWatchInfo()
        if (eps.isNotEmpty()) play(index, false)
    }

    private var watchVod: com.songshu.sstv.data.Vod? = null
    private var sourceRequest = 0

    private fun bindWatchPage() {
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
        val position = player.currentPosition
        saveHis()
        watchVod = vod; api = vod.sourceApi; srcName = vod.sourceName; vid = vod.id; lineName = line.name
        eps.clear(); eps.addAll(line.episodes.map { it.name + "\u0001" + it.url })
        wantSeek = position; firstPlay = true; epBuilt = false
        btnEp.visibility = if (eps.size > 1) View.VISIBLE else View.GONE
        play(line.episodes.indexOfFirst { it.name == previousName }.coerceAtLeast(0), false)
    }

    // ───────────────────────── 播放历史 / 断点续播 ─────────────────────────

    /** 每 5 秒记一次进度，这样被系统杀掉也不至于从头发 */
    private val hisSaver = object : Runnable {
        override fun run() {
            saveHis()
            histHandler.postDelayed(this, 5000L)
        }
    }

    private fun saveHis() {
        if (!::player.isInitialized) return
        runCatching {
            if (index !in eps.indices) return
            val parts = eps[index].split("\u0001")
            val epName = parts.getOrNull(0) ?: ""
            val url = parts.getOrNull(1) ?: return
            val pos = player.currentPosition
            val dur = player.duration
            if (pos < 3000) return
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

    /**
     * 这一集该从哪儿开始 —— 优先听详情页传过来的位置，
     * 否则查历史：看到 20 秒以上、又没看到快结束（片尾曲不算），就接着上次。
     */
    private fun seekTargetFor(url: String): Long {
        if (firstPlay && wantSeek > 0) return wantSeek
        val h = HistoryStore.byUrl(this, url) ?: return 0L
        if (h.pos < 20_000) return 0L
        if (h.dur > 0 && h.pos >= h.dur - 15_000) return 0L
        return h.pos
    }

    // ───────────────────────── 倍速 ─────────────────────────

    private fun applySpeed(v: Float, save: Boolean = true) {
        speed = v
        runCatching { player.setPlaybackSpeed(v) }
        btnSpeed.text = speedLabel(v)
        if (save) prefs.edit().putFloat("speed", v).apply()
    }

    /** 右下角那个倍速面板：3 列排开，当前档位用红色标出来 */
    private fun buildSpeedPanel() {
        speedPanel.removeAllViews()
        speedPanel.columnCount = 3
        val d = resources.displayMetrics.density
        val padX = (14 * d).toInt()
        val padY = (8 * d).toInt()
        val gap = (4 * d).toInt()
        speeds.forEach { v ->
            val tv = TextView(this).apply {
                text = speedLabel(v)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(padX, padY, padX, padY)
                setOnClickListener {
                    applySpeed(v)
                    showToast("播放速度 " + speedLabel(v))
                    tintSpeedPanel()
                    resetPanelTimer()      // 刚点过，10 秒重新数
                    resetTopTimer()
                }
            }
            val lp = GridLayout.LayoutParams()
            lp.setMargins(gap, gap, gap, gap)
            speedPanel.addView(tv, lp)
        }
        tintSpeedPanel()
    }

    private fun tintSpeedPanel() {
        for (i in 0 until speedPanel.childCount) {
            val tv = speedPanel.getChildAt(i) as TextView
            val on = kotlin.math.abs(speeds[i] - speed) < 0.01f
            tv.setTextColor(if (on) 0xFFE62429.toInt() else 0xFFFFFFFF.toInt())
            tv.background = if (on) ContextCompat.getDrawable(this, R.drawable.bg_chip) else null
        }
    }

    private val hidePanelRunnable = Runnable { hideSpeedPanel() }

    private fun showSpeedPanel() {
        tintSpeedPanel()
        speedPanel.visibility = View.VISIBLE
        resetPanelTimer()
    }

    /** 10 秒不碰它，自己收起来，不挡着看片 */
    private fun resetPanelTimer() {
        speedPanel.removeCallbacks(hidePanelRunnable)
        speedPanel.postDelayed(hidePanelRunnable, 10_000L)
    }

    private fun hideSpeedPanel() {
        speedPanel.removeCallbacks(hidePanelRunnable)
        speedPanel.visibility = View.GONE
    }

    // ─────────── 选集面板：一整季铺成一个网格，点哪集跳哪集 ───────────
    // 播放中要换集，全靠这个面板：顶栏点「选集」，点开就是全集网格，
    // 当前集标红并自动滚到中间。（v1.11 起顶栏那个悬浮的「下一集 ▶」按钮已删除。）

    /** 列数：横屏屏幕宽，一行多塞一个 */
    private fun epColumns(): Int = if (isLandscape()) 6 else 5

    /** 面板高度跟着屏幕走，免得横屏时卡片伸出屏幕外 */
    private fun fitEpPanelHeight() {
        val dm = resources.displayMetrics
        val d = dm.density
        val floor = (170 * d).toInt()
        val ceiling = (dm.heightPixels - 140 * d).toInt()
        val lp = epScroll.layoutParams
        lp.height = (dm.heightPixels * 0.6f).toInt().coerceIn(floor, maxOf(floor, ceiling))
        epScroll.layoutParams = lp
    }

    /** 集名 —— eps 里存的是「集名\u0001地址」，取前半段；真没有就用「第N集」兜底 */
    private fun epNameOf(i: Int): String =
        eps.getOrNull(i)?.split("\u0001")?.getOrNull(0).orEmpty().ifBlank { "第${i + 1}集" }

    /**
     * 建网格。等第一次点「选集」才建 —— 有的源一季几百集，
     * 一进播放页就铺几百个按钮，画面会先卡一下。
     */
    private fun buildEpPanel() {
        epGrid.removeAllViews()
        epBtns.clear()
        val d = resources.displayMetrics.density
        val gap = (6 * d).toInt()
        val per = epColumns()

        for (r in 0 until (eps.size + per - 1) / per) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = gap }

            for (c in 0 until per) {
                val i = r * per + c
                if (i >= eps.size) {
                    // 最后一行不满，补等宽空位把前几个格子顶住，不然它们会被拉宽
                    row.addView(
                        View(this),
                        LinearLayout.LayoutParams(0, 1, 1f).apply { if (c > 0) leftMargin = gap }
                    )
                    continue
                }
                val tv = TextView(this).apply {
                    text = epNameOf(i)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding((4 * d).toInt(), (11 * d).toInt(), (4 * d).toInt(), (11 * d).toInt())
                    setOnClickListener { pickEp(i) }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = gap })
                epBtns.add(tv)          // 按集号顺序加 → 下标就是集号
            }
            epGrid.addView(row)
        }
    }

    /** 当前正在播的那一集标红，其余灰底 */
    private fun tintEpPanel() {
        for (i in epBtns.indices) {
            val on = i == index
            epBtns[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFFC7CDD6.toInt())
            epBtns[i].background = ContextCompat.getDrawable(
                this, if (on) R.drawable.bg_btn_primary else R.drawable.bg_episode
            )
        }
        epTitle.text = if (index in eps.indices)
            "选集 · 正在播第 ${index + 1}/${eps.size} 集"
        else
            "选集 · 共 ${eps.size} 集"
    }

    private fun showEpPanel() {
        if (eps.size <= 1) {
            showToast("这个只有一集")
            return
        }
        if (!epBuilt) {
            buildEpPanel()
            epBuilt = true
        }
        hideSpeedPanel()               // 两个面板别叠在一起
        tintEpPanel()
        fitEpPanelHeight()
        epPanel.visibility = View.VISIBLE
        resetTopTimer()
        // 等这一帧布局完了再滚，不然拿到的还是旧高度，滚不到位置
        epScroll.post { scrollToCurrentEp() }
    }

    private fun hideEpPanel() {
        epPanel.visibility = View.GONE
        resetTopTimer()
    }

    /** 打开时直接滚到当前集，把它摆在可视区中间 —— 前后几集一眼能看见 */
    private fun scrollToCurrentEp() {
        val tv = epBtns.getOrNull(index) ?: return
        val row = tv.parent as? View ?: return
        val target = row.top - (epScroll.height - row.height) / 2
        epScroll.scrollTo(0, target.coerceAtLeast(0))
    }

    private fun pickEp(i: Int) {
        hideEpPanel()
        if (i == index) showToast("正在播这一集") else play(i)
    }

    // ───────────────────── 顶栏：5 秒不碰自动收起 ─────────────────────

    private fun showTopBar() {
        topBar.visibility = View.VISIBLE
        hideGearButton()          // 我们的顶栏跟自带控制条同一节奏显隐，顺手再抹一次齿轮
        resetTopTimer()
    }

    private fun resetTopTimer() {
        topBar.removeCallbacks(hideTopRunnable)
        topBar.postDelayed(hideTopRunnable, topHideMs)
    }

    private fun hideTopBar() {
        topBar.removeCallbacks(hideTopRunnable)
        topBar.visibility = View.GONE
    }

    // ───────────────────── 左侧滑亮度 / 右侧滑音量 ─────────────────────

    /**
     * 提示条：中间飘一个小卡片，显示当前这一档是多少。
     * 手指还按着的时候不收，松手后 0.9 秒自己消失。
     */
    private fun showHud(label: String, percent: Int) {
        val p = percent.coerceIn(0, 100)
        hudLabel.text = label
        hudValue.text = "$p%"
        hudBar.progress = p
        hudBox.visibility = View.VISIBLE
        hudBox.removeCallbacks(hideHudRunnable)
        if (gMode == 0) hudBox.postDelayed(hideHudRunnable, 900L)
    }

    private fun hideHud() {
        hudBox.removeCallbacks(hideHudRunnable)
        hudBox.visibility = View.GONE
    }

    /** 窗口亮度：用户没调过是 -1，那就按系统当前的算起 */
    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0f) return w.coerceIn(0.02f, 1f)
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (sys.coerceIn(1, 255) / 255f).coerceIn(0.02f, 1f)
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    /**
     * 按下：先看落点合不合法，再记下落点是左半边还是右半边。
     *
     * 注意这里**不再因为落在中间就退出** —— 左右滑（快进快退）是哪个位置都能用的，
     * 只有「上下滑」才需要看左右半边。中间那条竖带仍然是长按 2 倍速的地盘。
     */
    private fun gestureDown(ev: MotionEvent) {
        gSide = 0
        gMode = 0
        gReady = false
        if (hit(topBar, ev) || hit(speedPanel, ev)) return
        val w = resources.displayMetrics.widthPixels
        val h = view.height
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        // 自带进度条那一层正亮着的时候，底部这一带让给它 ——
        // 免得想拖进度条结果变成调亮度；它收起来之后整个下半屏都能滑
        if (view.isControllerFullyVisible && ev.rawY > location[1] + h * 0.72f) return
        gSide = when {
            ev.rawX < w * 0.35f -> 1             // 左 35% → 上下滑调亮度
            ev.rawX > w * 0.65f -> 2             // 右 35% → 上下滑调音量
            else -> 0                            // 中间 30% → 只能左右滑，不响应上下滑
        }
        gStartY = ev.rawY
        gStartX = ev.rawX
        gStartBright = currentBrightness()
        gStartVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // 横滑的基准：按下这一瞬间播到哪儿了。拖动途中不 seek（会卡），松手才真跳
        gStartPos = runCatching { player.currentPosition }.getOrDefault(0L)
        gDur = runCatching { player.duration }.getOrDefault(0L).let { if (it > 0) it else 0L }
        gSeekSec = 0
        gReady = true
    }

    /**
     * 滑动：先判方向再接管，两条路互斥 ——
     *   · 横着滑（|dx| 明显大于 |dy|）→ 快进 / 快退，全屏都能起划
     *   · 竖着滑（|dy| 明显大于 |dx|）→ 左半边调亮度、右半边调音量
     * 不满足门槛就什么都不做，免得抢了播放器自带的进度条拖动。
     */
    private fun gestureMove(ev: MotionEvent) {
        if (!gReady) return
        val dx = ev.rawX - gStartX
        val dy = ev.rawY - gStartY

        if (gMode == 0) {
            val adx = abs(dx)
            val ady = abs(dy)
            gMode = when {
                adx >= swipeSlop && adx > ady * 1.5f -> 3          // 横滑 → 快进快退
                ady >= swipeSlop && ady > adx * 1.5f -> gSide      // 竖滑 → 亮度/音量
                else -> 0                                          // 还没看出来，再等等
            }
            if (gMode == 0) return
            longPressToken++          // 在滑，把还没触发的长按作废
            tapCandidate = false
            stopFastForward()
        }

        if (gMode == 3) {
            // 每 40dp 一个 5 秒：轻轻划一下就 5 秒，划得越远跳得越多。
            // 先按绝对值取整再套符号 —— Kotlin 的 roundToInt 是 floor(x+0.5)，
            // 直接对负数取整会让左滑比右滑慢半格（-0.5 → 0，而 +0.5 → 1）。
            val per = 40f * resources.displayMetrics.density
            val steps = (abs(dx) / per).roundToInt()
            val sec = (if (dx >= 0f) steps else -steps) * 5
            if (sec != gSeekSec) {
                gSeekSec = sec.coerceIn(-600, 600)
                // 滑回起点等于取消，提示条也跟着收掉
                if (gSeekSec == 0) hideHud() else showSeekHud(gSeekSec)
            }
            return
        }

        val up = gStartY - ev.rawY                 // 往上滑为正
        // 半屏高走满全程：横屏约 540px，手指不用拉太长
        val span = resources.displayMetrics.heightPixels * 0.5f
        if (gMode == 1) {
            val v = (gStartBright + up / span).coerceIn(0.02f, 1f)
            setBrightness(v)
            showHud("亮度", (v * 100).roundToInt())
        } else {
            val v = (gStartVol + up / span * gMaxVol).roundToInt().coerceIn(0, gMaxVol)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
            showHud("音量", v * 100 / gMaxVol)
        }
    }

    /**
     * 松手。横滑到这里才真的跳 —— 拖动途中只更新提示，不然连着 seek 会卡。
     * commit=false 是手势被系统打断（来电之类），这种情况不跳。
     */
    private fun gestureUp(commit: Boolean) {
        if (gMode == 3 && commit && gSeekSec != 0) {
            val target = seekTargetOf(gSeekSec)
            runCatching { player.seekTo(target) }
        }
        if (gMode != 0) {
            hudBox.removeCallbacks(hideHudRunnable)
            hudBox.postDelayed(hideHudRunnable, 900L)
        }
        gMode = 0
        gSide = 0
        gReady = false
        gSeekSec = 0
    }

    /** 横滑的目标位置：以「按下那一刻」为基准算，拖动过程中来回滑不会累积误差 */
    private fun seekTargetOf(sec: Int): Long {
        val t = gStartPos + sec * 1000L
        return if (gDur > 0) t.coerceIn(0L, gDur) else t.coerceAtLeast(0L)
    }

    /** 横滑的提示条：大字显示跳多少秒，小字带上目标时间，进度条直接显示会跳到哪儿 */
    private fun showSeekHud(sec: Int) {
        gSeekSec = sec
        val forward = sec >= 0
        val target = seekTargetOf(sec)
        hudLabel.text = (if (forward) "快进 → " else "快退 → ") + fmtDur(target)
        hudValue.text = "${abs(sec)} 秒"
        hudBar.progress =
            if (gDur > 0) (target * 100 / gDur).toInt().coerceIn(0, 100) else 50
        hudBox.visibility = View.VISIBLE
        hudBox.removeCallbacks(hideHudRunnable)
    }

    // ───────────────────────── 长按屏幕快进 ─────────────────────────

    /**
     * 画面上的四套手势，全都在 dispatchTouchEvent 里做「旁路」判断、**不消费事件**，
     * 所以播放器自带的控制条、进度条照常好用：
     *
     *   · 点一下         → 顶栏/控制条出来，再点一下收起来；顶栏 5 秒不碰自己收
     *   · 双击           → 暂停 / 继续（v1.12 加）
     *   · 左右滑         → 快进 / 快退，每 40dp 一个 5 秒，松手才真跳（全屏都能划）
     *   · 左边上下滑     → 调画面亮度（只影响本 App，不动系统亮度）
     *   · 右边上下滑     → 调媒体音量
     *   · 长按 0.4 秒    → 临时 2 倍速，松手恢复
     *
     * 单击和双击是一对矛盾：单击在按下那一刻就切顶栏的话，双击的第一下会先把顶栏
     * 闪没了。所以拆成两条 —— **关着的顶栏按下就亮**（单击双击都受用，手感不该等），
     * **开着的顶栏等 300ms 双击窗口过了再收**。这样单击只是「收」慢了半拍，
     * 双击则完全不动顶栏。
     *
     * 按在顶栏、倍速面板上的不算；底部进度条那一带也不抢（免得滑到一半变成拖进度）。
     */
    private var touchingVideo = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchingVideo = hit(findViewById(R.id.videoStage), ev)
        if (!touchingVideo) return super.dispatchTouchEvent(ev)
        // 选集面板开着的时候，画面那几套手势全让路 —— 面板里的滚动和点击优先
        if (epPanel.visibility == View.VISIBLE) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 点面板外就把倍速面板收掉；这一次点击不再管顶栏，免得一次点两件事
                val closedPanel = speedPanel.visibility == View.VISIBLE &&
                        !hit(speedPanel, ev) && !hit(btnSpeed, ev)
                if (closedPanel) hideSpeedPanel()

                val onCtl = closedPanel || hit(topBar, ev) || hit(speedPanel, ev)
                // 点画面：控制层出来；再点一下收起来（跟播放器自带控制条同一个节奏）
                topWasShown = topBar.visibility == View.VISIBLE
                tapCandidate = false
                doubleTapArmed = false
                if (!onCtl) {
                    // 这一下是不是「双击的第二下」？间隔够短 + 落点够近才算
                    val now = SystemClock.uptimeMillis()
                    doubleTapArmed = now - lastTapUp <= doubleTapWindow &&
                            abs(ev.rawX - lastTapX) <= doubleTapSlopPx &&
                            abs(ev.rawY - lastTapY) <= doubleTapSlopPx
                    if (doubleTapArmed) {
                        // 第二下：把第一下排好的「收顶栏」撤掉 —— 抬手就暂停/继续。
                        // （顶栏此时是亮的，那就不收，双击暂停时画面干净点）
                        topBar.removeCallbacks(singleTapRunner)
                    } else {
                        tapCandidate = true
                        // 顶栏本来关着就立刻亮出来：单击、双击都受用，手感不该等 300ms。
                        // 本来开着就只续 5 秒，收不收等双击窗口过了再定
                        if (topWasShown) resetTopTimer() else showTopBar()
                        longPressToken++
                        val t = longPressToken
                        speedPanel.postDelayed({
                            if (t == longPressToken) {
                                tapCandidate = false
                                startFastForward()
                            }
                        }, longPressDelay)
                    }
                }
                gestureDown(ev)
            }

            MotionEvent.ACTION_MOVE -> gestureMove(ev)

            MotionEvent.ACTION_UP -> {
                gestureUp(commit = true)
                longPressToken++          // 作废还没触发的长按
                stopFastForward()
                if (doubleTapArmed) {
                    // 双击的第二下抬手 → 暂停 / 继续
                    doubleTapArmed = false
                    lastTapUp = 0L        // 清零：免得紧接着再来一下被算成「又一双击」
                    togglePlayPause()
                } else if (tapCandidate) {
                    // 记下这一下，等看有没有第二下
                    lastTapUp = SystemClock.uptimeMillis()
                    lastTapX = ev.rawX
                    lastTapY = ev.rawY
                    if (topWasShown) {
                        // 本来开着：先别收，等双击窗口过去再收（不然双击时顶栏先闪一下）
                        topBar.removeCallbacks(singleTapRunner)
                        topBar.postDelayed(singleTapRunner, doubleTapWindow)
                    }
                }
                tapCandidate = false
            }

            // 手势被系统打断（来电、被别的窗口抢走）→ 只收摊，不跳进度
            MotionEvent.ACTION_CANCEL -> {
                gestureUp(commit = false)
                longPressToken++
                stopFastForward()
                tapCandidate = false
                doubleTapArmed = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hit(v: View, ev: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    // ─────────────────── 播放器自带控制条的取舍（v1.12）───────────────────

    /**
     * 自带控制条瘦身。
     *
     * 要去掉的四个：上一集、下一集、后退 5 秒、前进 15 秒 ——
     * 换集走顶栏「选集」，快进快退在画面上左右滑，都比戳这两个小按钮顺手。
     * 顺带把乱序/字幕/VR 也关了（影视源压根没有这些轨）。
     *
     * 走 media3 自己的开关（`setShowXxxButton`），**不要**用 findViewById 设 GONE：
     * 控制条每次重新显示时 media3 会重算一遍按钮可见性，手动隐藏会被它刷回来。
     */
    private fun slimController() {
        view.setShowPreviousButton(false)      // ⏮ 上一集
        view.setShowNextButton(false)          // ⏭ 下一集
        view.setShowRewindButton(false)        // ↺5  后退 5 秒
        view.setShowFastForwardButton(false)   // ↻15 前进 15 秒
        view.setShowShuffleButton(false)
        view.setShowSubtitleButton(false)
        view.setShowVrButton(false)
        // 右下角那个「齿轮」media3 没给开关，只能按 id 拿 ——
        // 它点开是音轨/字幕那一串（默认是英文），看片用不上，一并去掉。
        // 布局一变就再抹一次，防止被它自己刷回来。
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> hideGearButton() }
        hideGearButton()
    }

    /** 把控制条右下角那个齿轮藏掉（media3 没有对应的开关属性，只能按 id 藏） */
    private fun hideGearButton() {
        val g = view.findViewById<View>(Media3R.id.exo_settings) ?: return
        if (g.visibility != View.GONE) g.visibility = View.GONE
    }

    /** 双击画面：暂停 / 继续（已经播到片尾了，就从头再放） */
    private fun togglePlayPause() {
        runCatching {
            when {
                player.playbackState == Player.STATE_ENDED -> {
                    player.seekTo(0L)
                    player.play()
                }
                player.isPlaying -> player.pause()
                else -> player.play()
            }
        }
    }

    /** 倍速的中文写法：1.0x → 1倍，0.75x → 0.75倍 */
    private fun speedLabel(v: Float): String {
        val n = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
        return n + "倍"
    }

    private fun startFastForward() {
        if (fastForwarding) return
        fastForwarding = true
        if (speed < 2.0f) runCatching { player.setPlaybackSpeed(2.0f) }
        showToast("2.0x 快进中 · 松手恢复")
    }

    private fun stopFastForward() {
        if (!fastForwarding) return
        fastForwarding = false
        runCatching { player.setPlaybackSpeed(speed) }
    }

    // ───────────────────────── 横屏 / 全屏 ─────────────────────────

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 点一下切方向：竖屏→强制横屏全屏，横屏→回竖屏 */
    private fun toggleFullscreen() {
        requestedOrientation = if (isLandscape())
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /**
     * 系统栏跟着**实际屏幕方向**走，而不是跟着按钮标志位 ——
     * 这样用户把手机横过来（系统自动旋转）时，也自动进全屏。
     */
    private fun applySystemBars() {
        val land = isLandscape()
        val stage = findViewById<View>(R.id.videoStage)
        stage.layoutParams = stage.layoutParams.apply {
            height = if (land) -1 else (resources.configuration.screenWidthDp * resources.displayMetrics.density * 9 / 16).toInt()
        }
        findViewById<View>(R.id.playerDetails).visibility = if (land) View.GONE else View.VISIBLE
        findViewById<View>(R.id.playerNavigation).visibility = if (land) View.GONE else View.VISIBLE
        titleTv.visibility = if (land) View.VISIBLE else View.GONE
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        btnFull.text = if (land) "竖屏" else "横屏"
        // 状态栏藏了，但挖孔/刘海还在，横屏时把顶栏往下让一点，免得按钮被挡
        val pad = if (land) statusBarHeight() else (6 * resources.displayMetrics.density).toInt()
        topBar.setPadding(topBar.paddingLeft, pad, topBar.paddingRight, topBar.paddingBottom)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (24 * resources.displayMetrics.density).toInt()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        fitEpPanelHeight()
        if (epBuilt) buildEpPanel()   // 横竖屏列数不一样（5 / 6），得重新铺一遍
    }

    /** 面板开着的时候按返回键 = 关面板，而不是退出播放页 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (epPanel.visibility == View.VISIBLE) {
            hideEpPanel()
            return
        }
        if (isLandscape()) { toggleFullscreen(); return }
        super.onBackPressed()
    }

    // ───────────────────────── 播放 ─────────────────────────

    private fun refererOf(url: String): String = runCatching {
        val u = URL(url)
        "${u.protocol}://${u.host}/"
    }.getOrDefault("")

    private fun buildSource(url: String): MediaSource {
        // 路径里可能带中文（综艺类很常见），先转义再交给播放器
        val safe = VodApi.encodeUrl(url)
        val local = safe.startsWith("file:")

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

    private var playRequest = 0
    private var deferredPlayback = false

    private fun play(i: Int, savePrevious: Boolean = true) {
        if (i !in eps.indices) return
        if (savePrevious) saveHis()
        val request = ++playRequest
        index = i
        bindWatchPage()
        val parts = eps[i].split("\u0001")
        val epName = parts.getOrNull(0) ?: ""
        val raw = parts.getOrNull(1) ?: return

        titleTv.text = if (vodName.isBlank()) epName else "$vodName · $epName"
        if (epBuilt) tintEpPanel()   // 面板开着自动连播到下一集时，红框得跟着挪

        // 这一集上次看到哪儿了（首次进入才看详情页带来的位置）
        val startAt = seekTargetFor(raw)
        firstPlay = false

        // 本地文件不用解析
        if (raw.startsWith("file:")) {
            startPlay(raw, startAt)
            return
        }
        // 多数资源站给的是「播放页」，得先换成真正的 m3u8
        if (PlayResolver.isPage(raw)) showToast("正在解析播放地址…")
        Bg.io.execute {
            val real = runCatching { PlayResolver.resolve(raw) }.getOrDefault(raw)
            runOnUiThread {
                if (isFinishing || isDestroyed || request != playRequest || index != i) return@runOnUiThread   // 用户已经切到别的集了
                startPlay(real, startAt)
            }
        }
    }

    private fun startPlay(url: String, startAt: Long = 0L) {
        runCatching {
            player.stop()
            player.setMediaSource(buildSource(url))
            player.setPlaybackSpeed(speed)   // stop() 之后再钉一遍，免得切集后倍速被重置
            player.prepare()
            if (startAt > 0) {
                player.seekTo(startAt)
                showToast("从 ${fmtDur(startAt)} 接着看")
            }
            deferredPlayback = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            player.playWhenReady = !deferredPlayback
        }.onFailure { showToast("地址有问题：${it.message}") }
    }

    /**
     * 顶栏「缓存」：默认只勾**正在看的这一集**，想一口气多下几集就接着点。
     *
     * 以前这一下是「当前这一集」直接开下，多集片想连着下几集得退出去到详情页。
     * 现在两边都能挑：这里默认选当前集（顺手），详情页那个默认全选（原来就是全下）。
     */
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
                DlHub.isDownloading(pairs[i].second) -> CachePicker.Mark.DOING
                Downloader.localFile(this, pairs[i].second) != null -> CachePicker.Mark.DONE
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
        val todo = picked.filter { !DlHub.isDownloading(it.second) }
        if (todo.isEmpty()) {
            showToast("这几集正在缓存中，去「我的缓存」看进度")
            return
        }
        val task = DlHub.create(vodName, pic, todo)
        showToast(
            if (todo.size == 1) "开始缓存 ${todo[0].first}，进度在「我的缓存」里看"
            else "开始缓存 ${todo.size} 集，进度在「我的缓存」里看"
        )
        Bg.dl.execute { DlRunner.run(this, task) }
    }

    private var toastToken = 0

    private fun showToast(msg: String) {
        toastToken++
        val mine = toastToken
        toastTv.text = msg
        toastTv.visibility = View.VISIBLE
        toastTv.postDelayed({
            if (mine == toastToken) toastTv.visibility = View.GONE
        }, 2200)
    }

    override fun onStart() {
        super.onStart()
        showTopBar()          // 回到前台先把顶栏亮一下，5 秒后再自己收
        // 播着播着每 5 秒记一次进度
        histHandler.removeCallbacks(hisSaver)
        histHandler.postDelayed(hisSaver, 5000L)
    }

    override fun onResume() {
        super.onResume()
        if (deferredPlayback && ::player.isInitialized) {
            deferredPlayback = false
            player.play()
        }
    }

    override fun onPause() {
        super.onPause()
        hideSpeedPanel()
        hideEpPanel()
        hideHud()
        hideTopBar()
        stopFastForward()
        topBar.removeCallbacks(singleTapRunner)   // 别让「收顶栏」在后台排队，回前台突然收一下
        doubleTapArmed = false
        lastTapUp = 0L
        gMode = 0
        gSide = 0
        gReady = false
        gSeekSec = 0
        runCatching { player.pause() }
    }

    override fun onStop() {
        super.onStop()
        histHandler.removeCallbacks(hisSaver)
        saveHis()
    }

    override fun onDestroy() {
        super.onDestroy()
        speedPanel.removeCallbacks(hidePanelRunnable)
        topBar.removeCallbacks(hideTopRunnable)
        hudBox.removeCallbacks(hideHudRunnable)
        histHandler.removeCallbacks(hisSaver)
        runCatching { player.release() }
    }
}
