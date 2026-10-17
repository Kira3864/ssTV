package com.songshu.sstv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.Vod
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.ui.SearchResultsAdapter
import com.songshu.sstv.ui.SearchGroup
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Parallel search grouped by provider, ordered by measured response latency. */
class SearchActivity : ThemedActivity() {

    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var adapter: SearchResultsAdapter
    private lateinit var progress: View
    private lateinit var hint: TextView
    private lateinit var tip: TextView

    private class SearchSession {
        val lock = Any()
        val seen = HashSet<String>()
        val groups = HashMap<String, SearchGroup>()
        val results = ArrayList<Vod>()
        @Volatile var cancelled = false
        val pools = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.ExecutorService>()
        fun cancel() { cancelled = true; pools.forEach { it.shutdownNow() } }
    }
    private var session: SearchSession? = null
    private var searchJob: java.util.concurrent.Future<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.input)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        tip = findViewById(R.id.tip)

        adapter = SearchResultsAdapter { v ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra("id", v.id)
                    .putExtra("name", v.name)
                    .putExtra("api", v.sourceApi)
                    .putExtra("srcName", v.sourceName)
                    .putExtra("pic", v.pic)
            )
        }
        list.adapter = adapter
        list.itemAnimator = null
        list.setItemViewCacheSize(8)
        applySpan()

        tip.visibility = View.GONE

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGo).setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, action, event ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                doSearch()
                true
            } else false
        }
        input.requestFocus()
    }

    private fun applySpan() {
        val width = resources.configuration.screenWidthDp
        val columns = maxOf(2, width / 112)
        list.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isHeader(position)) columns else 1
            }
        }
        adapter.pictureHeight = (((width / columns - 12) * 1.42f * resources.displayMetrics.density).toInt())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applySpan()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 只在页面还活着的时候碰 UI，免得搜完回来页面已经关了 */
    private fun ui(block: () -> Unit) {
        showResult { if (!isFinishing && !isDestroyed) block() }
    }

    private fun setTip(work: SearchSession, s: String) = ui { if (session === work && !work.cancelled) tip.text = s }

    /** 片名归一化：只用来判断「是不是同一部」 */
    private fun keyOf(name: String): String =
        name.replace(Regex("\\s+"), "").replace(Regex("[·•．.\\-—_（）()\\[\\]]"), "").lowercase()

    /**
     * 关键词降级方案：第一轮搜不到时，自动把《》、年份、"全XX集"这类修饰词去掉再试。
     * 片源库里同一部片子常被写上不同的后缀，关键词越干净越容易命中。
     */
    private fun variants(wd: String): List<String> {
        val out = ArrayList<String>()
        var s = wd
        // 标点全清掉：全角冒号/顿号这类不处理的话会黏在片名里
        s = s.replace(Regex("[《》〈〉\\[\\]【】()（）\"'“”·：:；;，,。、！!？?…—～~]"), " ")
        s = s.replace(Regex("(?i)\\b(19|20)\\d{2}\\b"), " ")
        s = s.replace(
            Regex(
                "(?i)(全\\s*\\d+\\s*[集季部]|第\\s*[0-9一二三四五六七八九十]+\\s*[季部集]|" +
                        "高清|超清|蓝光|完整版|未删减|国语版|国语|粤语版|粤语|中字|中文字幕|" +
                        "HD|TS|4K|2160P|1080P|720P|BLURAY|WEB-DL)"
            ), " "
        )
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isNotEmpty() && s != wd) out.add(s)

        // 再兜一层：中文只留第一段（片名一般就 4-6 个字，硬截断会截出「复仇者联」这种废词）
        val core = if (s.any { it.code in 0x4E00..0x9FFF }) {
            s.split(" ").firstOrNull().orEmpty()
        } else {
            s.split(" ").take(2).joinToString(" ")
        }
        if (core.length >= 2 && core != s && core != wd && !out.contains(core)) out.add(core)
        return out
    }

    private fun doSearch() {
        val wd = input.text.toString().trim()
        if (wd.isEmpty()) {
            hint.text = "先输入片名"
            hint.visibility = View.VISIBLE
            return
        }
        session?.cancel()
        searchJob?.cancel(true)
        val work = SearchSession()
        session = work
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)

        tip.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        adapter.submit(emptyList())


        val all = SourceStore.sources(this)
        val cur = SourceStore.current(this)
        // 当前源排最前；站方关了搜索的先放一边，等实在搜不到再硬试
        val ordered = (listOf(cur) + all).distinctBy { it.api }
        val canSearch = ordered.filterNot { it.name.contains("(无搜索)") }
        val closed = ordered.filter { it.name.contains("(无搜索)") }

        if (ordered.isEmpty()) {
            progress.visibility = View.GONE
            hint.text = "一个片源都没有，去设置里加几个吧"
            hint.visibility = View.VISIBLE
            return
        }

        val filterAdult = SourceStore.filterAdult(this)

        searchJob = Bg.io.submit {
            var total = 0

            // ── 第 1 轮：所有能搜的源一起问，当前源排最前
            if (canSearch.isNotEmpty()) {
                setTip(work, "第 1 轮：正在问 ${canSearch.size} 个片源…")
                total = runRound(work, canSearch, wd, "第 1 轮", filterAdult)
            }

            // ── 第 2 轮：一个都没搜到 → 自动换个干净点的关键词重来
            if (total == 0) {
                for (v in variants(wd)) {
                    if (total > 0 || work.cancelled) break
                    setTip(work, "第 1 轮没搜到，自动换成「$v」再搜一遍…")
                    total = runRound(work, canSearch, v, "第 2 轮（「$v」）", filterAdult)
                }
            }

            // ── 第 3 轮：还是空 → 连站方关了搜索的源也硬试一遍
            if (total == 0 && !work.cancelled && closed.isNotEmpty()) {
                setTip(work, "还没有，连 ${closed.size} 个关了搜索的源也硬试一遍…")
                runRound(work, closed, wd, "第 3 轮", filterAdult)
            }

            val n = synchronized(work.lock) { work.results.size }
            ui {
                if (session !== work || work.cancelled) return@ui
                progress.visibility = View.GONE
                tip.text = "${synchronized(work.lock) { work.groups.size }} 个片源 · $n 个结果"
                if (n == 0) {
                    hint.text = "未找到相关影片"
                    hint.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 把一轮搜索跑完：并发发给 targets 里的每个源，结果累加进 acc。
     * 返回这一轮结束时一共找到多少个（跨轮累计）。
     */
    private fun runRound(
        work: SearchSession,
        targets: List<Source>,
        wd: String,
        label: String,
        filterAdult: Boolean
    ): Int {
        if (targets.isEmpty() || work.cancelled) return synchronized(work.lock) { work.results.size }
        val done = AtomicInteger(0)
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val pool = Executors.newFixedThreadPool(8)
        work.pools.add(pool)
        try {
            targets.forEach { source ->
                if (work.cancelled) return@forEach
                pool.execute {
                    if (work.cancelled) return@execute
                    val started = System.nanoTime()
                    val got = runCatching { VodApi.search(source.api, wd, source.name).list }
                        .getOrDefault(emptyList())
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1)
                    synchronized(work.lock) {
                        if (work.cancelled || !accepting.get()) return@execute
                        got.forEach lit@ { v ->
                            if (filterAdult && SourceStore.isAdultCategory(v.typeName)) return@lit
                            val key = source.api + "|" + v.id
                            if (key.isNotEmpty() && work.seen.add(key)) work.results.add(v)
                        }
                        val own = work.results.filter { it.sourceApi == source.api }
                        if (own.isNotEmpty()) work.groups[source.api] = SearchGroup(source,
                            minOf(elapsed, work.groups[source.api]?.latencyMs ?: Long.MAX_VALUE), own)
                    }
                    val count = done.incrementAndGet()
                    ui {
                        if (session !== work || work.cancelled || !accepting.get()) return@ui
                        // Snapshot on the main thread so late UI posts cannot replace newer results.
                        val snapshot = synchronized(work.lock) { work.groups.values.toList() }
                        adapter.submit(snapshot)
                        tip.text = "$label · 已试 $count/${targets.size} 个源 · 已找到 ${snapshot.sumOf { it.items.size }} 个"
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(70, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A new query cancelled this round while tasks were being scheduled.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(work.lock) { accepting.set(false) }
            pool.shutdownNow()
            work.pools.remove(pool)
        }
        ui {
            if (session === work && !work.cancelled) {
                adapter.submit(synchronized(work.lock) { work.groups.values.toList() })
            }
        }
        return synchronized(work.lock) { work.results.size }
    }

    override fun onDestroy() {
        session?.cancel()
        searchJob?.cancel(true)
        super.onDestroy()
    }
}
