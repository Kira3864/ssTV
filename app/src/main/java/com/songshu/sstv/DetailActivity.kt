package com.songshu.sstv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.songshu.sstv.cache.DlHub
import com.songshu.sstv.cache.DlRunner
import com.songshu.sstv.cache.Downloader
import com.songshu.sstv.data.HistoryStore
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.Vod
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.ui.CachePicker
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.Img

class DetailActivity : AppCompatActivity() {

    private lateinit var pic: ImageView
    private lateinit var name: TextView
    private lateinit var meta: TextView
    private lateinit var remark: TextView
    private lateinit var actor: TextView
    private lateinit var plot: TextView
    private lateinit var plotToggle: TextView
    private lateinit var fromRow: LinearLayout
    private lateinit var epBox: LinearLayout
    private lateinit var epHint: TextView
    private lateinit var progress: View
    private lateinit var hint: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnDownload: TextView
    private lateinit var pickSource: TextView

    private var vod: Vod? = null
    private var lineIndex = 0
    private var expanded = false

    /** 上次看到哪儿：进这部片子时自动定位到那一集 */
    private var continueIdx = 0
    private var continuePos = 0L

    private val dlHandler = Handler(Looper.getMainLooper())
    private var dlWasRunning = false

    /** 自动换源只自动跑一次，免得几个源都没地址时来回弹 */
    private var autoTried = false

    private var api = ""
    private var srcName = ""
    private var vid = ""
    private var fallbackName = ""
    private var fallbackPic = ""

    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        pic = findViewById(R.id.pic)
        name = findViewById(R.id.name)
        meta = findViewById(R.id.meta)
        remark = findViewById(R.id.remark)
        actor = findViewById(R.id.actor)
        plot = findViewById(R.id.plot)
        plotToggle = findViewById(R.id.plotToggle)
        fromRow = findViewById(R.id.fromRow)
        epBox = findViewById(R.id.epBox)
        epHint = findViewById(R.id.epHint)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        btnPlay = findViewById(R.id.btnPlay)
        btnDownload = findViewById(R.id.btnDownload)
        pickSource = findViewById(R.id.pickSource)

        api = intent.getStringExtra("api") ?: SourceStore.current(this).api
        srcName = intent.getStringExtra("srcName") ?: SourceStore.current(this).name
        vid = intent.getStringExtra("id") ?: ""
        fallbackName = intent.getStringExtra("name") ?: ""
        fallbackPic = intent.getStringExtra("pic") ?: ""

        Img.round(pic, 8f * d)
        Img.load(pic, fallbackPic)
        name.text = fallbackName

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        pickSource.setOnClickListener { changeSource() }
        plotToggle.setOnClickListener {
            expanded = !expanded
            plot.maxLines = if (expanded) 100 else 4
            plotToggle.text = if (expanded) "收起简介" else "展开简介"
        }
        btnPlay.setOnClickListener { play(continueIdx, continuePos) }
        btnDownload.setOnClickListener { pickCache() }

        load()
    }

    override fun onResume() {
        super.onResume()
        dlHandler.removeCallbacks(dlTicker)
        dlHandler.postDelayed(dlTicker, 800L)
    }

    override fun onPause() {
        super.onPause()
        dlHandler.removeCallbacks(dlTicker)
    }

    /** 在详情页也能看见缓存跑到哪儿了，不用来回切页面 */
    private val dlTicker = object : Runnable {
        override fun run() {
            val nm = vod?.name.orEmpty()
            val t = DlHub.list().firstOrNull { it.isRunning && it.title == nm }
            if (t != null) {
                hint.text = buildString {
                    append("缓存中 ")
                    if (t.epCount > 1) append("第 ${t.epIndex + 1}/${t.epCount} 集 ")
                    append("${t.epName}  ${t.percent}%")
                    if (t.speed > 0) append(" · ${fmtSize(t.speed)}/s")
                }
                hint.visibility = View.VISIBLE
                dlWasRunning = true
            } else if (dlWasRunning) {
                dlWasRunning = false
                hint.text = "缓存结束，结果去「我的缓存」里看"
                hint.visibility = View.VISIBLE
            }
            dlHandler.postDelayed(this, 800L)
        }
    }

    private fun load() {
        if (vid.isBlank()) {
            showError("这部片子缺 ID，返回重进一次")
            return
        }
        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        fromRow.removeAllViews()
        epBox.removeAllViews()
        val a = api
        val id = vid
        val sn = srcName
        Bg.io.execute {
            val r = runCatching { VodApi.detail(a, id, sn) }
            runOnUiThread {
                progress.visibility = View.GONE
                val v = r.getOrNull()
                if (v == null || v.lines.isEmpty()) {
                    // 这个源拿不到播放地址 —— 别让用户自己点换源，自动往下找
                    if (!autoTried) autoSwitch() else showError(
                        "换过的源也都没有播放地址。点右上角「换源」手动挑一个，或者换个片名重搜。"
                    )
                    return@runOnUiThread
                }
                vod = v
                bind(v)
            }
        }
    }

    /**
     * 当前源没播放地址 → 自动把其他源挨个试一遍，谁先拿到能播的地址就用谁。
     *
     * 和「换源」按钮的区别：这个不用用户点，也不会弹列表，
     * 直接切过去并把剧集刷出来；只有全试完都失败才让用户自己挑。
     */
    private fun autoSwitch() {
        val nm = (vod?.name ?: fallbackName).trim()
        if (nm.isBlank()) {
            showError("这个源没有取到播放地址，点右上角「换源」试试")
            return
        }
        autoTried = true
        progress.visibility = View.VISIBLE
        hint.text = "这个源没有播放地址，正在自动换下一个源…"
        hint.visibility = View.VISIBLE

        val curApi = api
        // 按设置里的片源顺序挨个试，跳开当前源
        val others = SourceStore.sources(this).filter { it.api != curApi }
        if (others.isEmpty()) {
            showError("没有别的片源可换了，去设置里加几个吧")
            return
        }

        Bg.io.execute {
            val found = java.util.Collections.synchronizedList(ArrayList<Pair<Source, Vod>>())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            others.forEach { s ->
                pool.execute {
                    val r = runCatching {
                        val hit = VodApi.search(s.api, nm, s.name).list
                            .firstOrNull { it.name.contains(nm) || nm.contains(it.name) }
                        if (hit == null) null else {
                            val d = VodApi.detail(s.api, hit.id, s.name)
                            if (d != null && d.lines.isNotEmpty()) s to d else null
                        }
                    }.getOrNull()
                    if (r != null) found.add(r)
                }
            }
            pool.shutdown()
            pool.awaitTermination(70, java.util.concurrent.TimeUnit.SECONDS)

            runOnUiThread {
                progress.visibility = View.GONE
                if (found.isEmpty()) {
                    showError("自动试了 ${others.size} 个源都没找到能播的。点右上角「换源」手动挑挑看。")
                    return@runOnUiThread
                }
                // 并发回来的顺序是乱的，按片源列表顺序挑第一个成功的 —— 等价于「一个一个往下试」
                val order = others.mapIndexed { i, s -> s.api to i }.toMap()
                val pick = found.minByOrNull { order[it.first.api] ?: Int.MAX_VALUE } ?: found[0]

                api = pick.first.api
                srcName = pick.first.name
                vid = pick.second.id
                SourceStore.setCurrent(this, pick.first)

                vod = pick.second
                bind(pick.second)
                hint.visibility = View.GONE
                epHint.text = "⚠ 原片源没取到播放地址，已自动切到「${pick.first.name}」"
            }
        }
    }

    private fun showError(msg: String) {
        progress.visibility = View.GONE
        hint.text = msg
        hint.visibility = View.VISIBLE
    }

    private fun bind(v: Vod) {
        if (v.name.isNotBlank()) name.text = v.name
        if (v.pic.isNotBlank()) Img.load(pic, v.pic)
        meta.text = v.meta
        remark.text = v.remark
        actor.text = buildString {
            if (v.director.isNotBlank()) append("导演：${v.director}\n")
            if (v.actor.isNotBlank()) append("主演：${v.actor}")
        }.trim()
        plot.text = v.content.ifBlank { "暂无简介" }
        plotToggle.visibility = if (v.content.length > 120) View.VISIBLE else View.GONE

        lineIndex = 0
        buildFromRow(v)
        buildEpisodes(v)
        applyHistory(v)
    }

    /**
     * 查一下这部片子的播放历史：有的话把「播放」变成「继续播放」，
     * 点下去直接跳到上次那一集的进度，不用自己找集数、拖进度条。
     */
    private fun applyHistory(v: Vod) {
        continueIdx = 0
        continuePos = 0L
        btnPlay.text = getString(R.string.play)
        val h = HistoryStore.lastOfTitle(this, v.name) ?: return
        val line = v.lines.getOrNull(lineIndex) ?: return
        var i = line.episodes.indexOfFirst { it.name == h.ep && it.url == h.url }
        if (i < 0) i = line.episodes.indexOfFirst { it.name == h.ep }
        if (i < 0) return
        continueIdx = i
        continuePos = if (h.pos > 20_000) h.pos else 0L
        val seen = if (h.dur > 0) "（看到 ${((h.pos * 100 / h.dur)).coerceIn(0, 99)}%）" else ""
        if (continuePos > 0) {
            btnPlay.text = "继续播放 · ${h.ep}"
            epHint.text = "上次看到 ${h.ep} ${fmtDur(continuePos)} $seen · 点「继续播放」接着看"
        } else {
            epHint.text = "上次看的 ${h.ep} 已经看完了，点「播放」从头开始"
        }
    }

    private fun buildFromRow(v: Vod) {
        fromRow.removeAllViews()
        if (v.lines.size <= 1) return
        v.lines.forEachIndexed { i, line ->
            val tv = TextView(this).apply {
                text = line.name
                textSize = 13f
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_chip)
                setTextColor(0xFF98A2B3.toInt())
                setOnClickListener {
                    lineIndex = i
                    tintRow()
                    buildEpisodes(v)
                    applyHistory(v)
                }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = (8 * d).toInt()
            fromRow.addView(tv, lp)
        }
        tintRow()
    }

    private fun tintRow() {
        for (k in 0 until fromRow.childCount) {
            val tv = fromRow.getChildAt(k) as TextView
            if (k == lineIndex) {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
            } else {
                tv.setTextColor(0xFF98A2B3.toInt())
                tv.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
            }
        }
    }

    private fun buildEpisodes(v: Vod) {
        epBox.removeAllViews()
        val line = v.lines.getOrNull(lineIndex) ?: return
        val eps = line.episodes
        epHint.text = "共 ${eps.size} 集 · 点一下就能播，长按可以单独缓存，右上角「缓存选集」可以挑着下"

        val per = 4
        val rows = (eps.size + per - 1) / per
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rlp = LinearLayout.LayoutParams(-1, -2)
            rlp.topMargin = (8 * d).toInt()
            rlp.leftMargin = (16 * d).toInt()
            rlp.rightMargin = (16 * d).toInt()
            row.layoutParams = rlp

            for (c in 0 until per) {
                val i = r * per + c
                if (i >= eps.size) {
                    val sp = View(this)
                    val slp = LinearLayout.LayoutParams(0, 1, 1f)
                    if (c > 0) slp.leftMargin = (8 * d).toInt()
                    row.addView(sp, slp)
                    continue
                }
                val ep = eps[i]
                val tv = TextView(this).apply {
                    text = ep.name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFFF2F4F7.toInt())
                    background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_episode)
                    setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt())
                    setOnClickListener { play(i) }
                    setOnLongClickListener {
                        downloadOne(v, ep.name, ep.url)
                        true
                    }
                }
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                if (c > 0) lp.leftMargin = (8 * d).toInt()
                row.addView(tv, lp)
            }
            epBox.addView(row)
        }
    }

    private fun play(index: Int, seek: Long = 0L) {
        val v = vod ?: return
        val line = v.lines.getOrNull(lineIndex) ?: return
        if (index !in line.episodes.indices) return
        val arr = ArrayList<String>()
        line.episodes.forEach { arr.add(it.name + "\u0001" + it.url) }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("title", v.name)
                .putExtra("line", line.name)
                .putStringArrayListExtra("eps", arr)
                .putExtra("index", index)
                .putExtra("pic", v.pic)
                .putExtra("api", api)
                .putExtra("srcName", srcName)
                .putExtra("vid", vid)
                .putExtra("seek", seek)
        )
    }

    private fun downloadOne(v: Vod, epName: String, url: String) {
        if (DlHub.isDownloading(url)) {
            Dlg.tip(this, "已经在缓存", "这一集正在缓存中，去「我的缓存」看进度。")
            return
        }
        Dlg.confirm(
            this, "缓存「$epName」",
            "下载后可离线观看。文件存在 App 私有目录，卸载 App 会一并清除。\n下完之前别把 App 从后台划掉。"
        ) {
            startCache(v, listOf(epName to url))
        }
    }

    /**
     * 「缓存选集」按钮：先让人挑下哪几集，再开跑。
     *
     * 原来这一下是整季无脑全下（一百多集的动漫，点错了只能去缓存页取消）。
     * 现在默认**全勾上**——老杨原来的习惯就是「一下把整季下了」，
     * 保持默认全选最贴近原来的手感；想只下几集，点「清空」再点着挑就行。
     */
    private fun pickCache() {
        val v = vod ?: return
        val line = v.lines.getOrNull(lineIndex) ?: return
        val list = line.episodes
        if (list.isEmpty()) {
            Dlg.tip(this, "缓存", "这条线路还没有剧集，换个线路试试。")
            return
        }
        if (list.size == 1) {                 // 单集电影没什么好挑的，直接确认
            downloadOne(v, list[0].name, list[0].url)
            return
        }

        val pairs = list.map { it.name to it.url }
        val marks = Array(pairs.size) { i ->
            when {
                DlHub.isDownloading(pairs[i].second) -> CachePicker.Mark.DOING
                Downloader.localFile(this, pairs[i].second) != null -> CachePicker.Mark.DONE
                else -> CachePicker.Mark.NONE
            }
        }
        // 默认勾上「还没下过的」，已经下过 / 正在下的不勾 —— 免得白下一遍
        val sel = BooleanArray(pairs.size) { marks[it] == CachePicker.Mark.NONE }

        CachePicker.show(this, "缓存「${v.name}」", pairs, sel, marks) { picked ->
            startCache(v, picked)
        }
    }

    /** 真正开跑。挑进来的集里如果有的正在下，剔掉 —— 两个任务写同一个 .part 会把文件搞坏 */
    private fun startCache(v: Vod, picked: List<Pair<String, String>>) {
        val todo = picked.filter { !DlHub.isDownloading(it.second) }
        if (todo.isEmpty()) {
            Dlg.tip(this, "已经在缓存了", "挑的这几集正在缓存中，去「我的缓存」看进度。")
            return
        }
        val task = DlHub.create(v.name, v.pic, todo)
        Bg.dl.execute { DlRunner.run(this, task) }

        val skipped = picked.size - todo.size
        hint.text = buildString {
            append(if (todo.size == 1) "开始缓存 ${todo[0].first}" else "开始缓存 ${todo.size} 集")
            if (skipped > 0) append("（有 $skipped 集正在缓存中，跳过了）")
            append("，进度去「我的缓存」里看")
        }
        hint.visibility = View.VISIBLE
        dlWasRunning = true
    }

    private fun changeSource() {
        val nm = (vod?.name ?: fallbackName).trim()
        if (nm.isEmpty()) return
        // 跳过当前源，也跳过站方关了搜索的源（它们搜不出来，白等）
        val others = SourceStore.sources(this)
            .filter { it.api != api && !it.name.contains("(无搜索)") }
        if (others.isEmpty()) {
            Dlg.tip(this, "换源", "没有别的可搜索片源，去设置里再加几个吧。")
            return
        }
        progress.visibility = View.VISIBLE
        Bg.io.execute {
            val found = java.util.Collections.synchronizedList(ArrayList<Pair<Source, Vod>>())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            others.forEach { s ->
                pool.execute {
                    runCatching {
                        val pg = VodApi.search(s.api, nm, s.name)
                        pg.list.firstOrNull { it.name.contains(nm) || nm.contains(it.name) }
                            ?.let { found.add(s to it) }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
            runOnUiThread {
                progress.visibility = View.GONE
                if (found.isEmpty()) {
                    Dlg.tip(this, "换源", "搜了 ${others.size} 个片源，都没找到这部。")
                    return@runOnUiThread
                }
                val sorted = found.sortedByDescending { it.second.name == nm }
                val labels = sorted.map { "${it.first.name}  →  ${it.second.name}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("换个源试试（找到 ${sorted.size} 个）")
                    .setItems(labels) { _, i ->
                        val (s, nv) = sorted[i]
                        api = s.api
                        srcName = s.name
                        vid = nv.id
                        SourceStore.setCurrent(this, s)
                        load()
                    }
                    .show()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unusedCtx(c: Context) = Unit
}
