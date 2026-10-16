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
import com.songshu.sstv.ui.VodAdapter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聚合搜索：把片名丢给**所有片源**，一轮一轮往前顶，直到搜到为止。
 *
 * 三轮兜底（前一轮全空才往下走，搜到就停）：
 *   第 1 轮  并发问所有支持搜索的源，当前源排最前，谁先回来先显示
 *   第 2 轮  还是空 → 自动把《》、年份、"第X季/全XX集"这些修饰词去掉，换个关键词再搜
 *   第 3 轮  还空   → 连站方关了搜索的源也硬试一遍
 *
 * 同名结果只保留先到的那个，避免同一个片子刷屏；
 * 列表里每条都会标出它是从哪个源搜到的，点进去就用那个源播。
 */
class SearchActivity : ThemedActivity() {

    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var adapter: VodAdapter
    private lateinit var progress: View
    private lateinit var hint: TextView
    private lateinit var tip: TextView

    private class SearchSession {
        val lock = Any()
        val seen = HashSet<String>()
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

        adapter = VodAdapter(dp(150)) { v ->
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

        val usable = SourceStore.sources(this).count { !it.name.contains("(无搜索)") }
        tip.text = "共 ${SourceStore.sources(this).size} 个片源，其中 $usable 个支持搜索"

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
        list.layoutManager = GridLayoutManager(this, columns)
        adapter.setPicHeight(((width / columns - 12) * 1.42f * resources.displayMetrics.density).toInt())
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

        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        adapter.clear()


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
                tip.text = "「$wd」共试了 ${ordered.size} 个片源，找到 $n 个结果"
                if (n == 0) {
                    hint.text = "三轮都搜完了还是没找到。\n换个片名试试（只写前两三个字最容易中），\n或者去设置里「一键检测片源」看看源还活着没。"
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
                    val got = runCatching { VodApi.search(source.api, wd, source.name).list }
                        .getOrDefault(emptyList())
                    synchronized(work.lock) {
                        if (work.cancelled || !accepting.get()) return@execute
                        got.forEach lit@ { v ->
                            if (filterAdult && SourceStore.isAdultCategory(v.typeName)) return@lit
                            val key = keyOf(v.name)
                            if (key.isNotEmpty() && work.seen.add(key)) work.results.add(v)
                        }
                    }
                    val count = done.incrementAndGet()
                    ui {
                        if (session !== work || work.cancelled || !accepting.get()) return@ui
                        // Snapshot on the main thread so late UI posts cannot replace newer results.
                        val snapshot = synchronized(work.lock) { ArrayList(work.results) }
                        adapter.submit(snapshot, false)
                        tip.text = "$label · 已试 $count/${targets.size} 个源 · 已找到 ${snapshot.size} 个"
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
                adapter.submit(synchronized(work.lock) { ArrayList(work.results) }, false)
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
