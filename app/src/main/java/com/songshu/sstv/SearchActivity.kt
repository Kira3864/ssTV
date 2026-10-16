package com.songshu.sstv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
class SearchActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var adapter: VodAdapter
    private lateinit var progress: View
    private lateinit var hint: TextView
    private lateinit var tip: TextView

    private var searching = false

    /** 跨轮次累计的结果与去重表，全部在 lock 内读写 */
    private val lock = Any()
    private val seen = HashSet<String>()
    private val acc = ArrayList<Vod>()

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
        val w = resources.configuration.screenWidthDp
        list.layoutManager = GridLayoutManager(this, maxOf(2, w / 112))
        list.adapter = adapter

        val usable = SourceStore.sources(this).count { !it.name.contains("(无搜索)") }
        tip.text = "共 ${SourceStore.sources(this).size} 个片源，其中 $usable 个支持搜索"

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGo).setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }
        input.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 只在页面还活着的时候碰 UI，免得搜完回来页面已经关了 */
    private fun ui(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun setTip(s: String) = ui { tip.text = s }

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
        if (searching) return
        searching = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)

        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        adapter.clear()
        synchronized(lock) {
            seen.clear()
            acc.clear()
        }

        val all = SourceStore.sources(this)
        val cur = SourceStore.current(this)
        // 当前源排最前；站方关了搜索的先放一边，等实在搜不到再硬试
        val ordered = (listOf(cur) + all).distinctBy { it.api }
        val canSearch = ordered.filterNot { it.name.contains("(无搜索)") }
        val closed = ordered.filter { it.name.contains("(无搜索)") }

        if (ordered.isEmpty()) {
            searching = false
            progress.visibility = View.GONE
            hint.text = "一个片源都没有，去设置里加几个吧"
            hint.visibility = View.VISIBLE
            return
        }

        val filterAdult = SourceStore.filterAdult(this)

        Bg.io.execute {
            var total = 0

            // ── 第 1 轮：所有能搜的源一起问，当前源排最前
            if (canSearch.isNotEmpty()) {
                setTip("第 1 轮：正在问 ${canSearch.size} 个片源…")
                total = runRound(canSearch, wd, "第 1 轮", filterAdult)
            }

            // ── 第 2 轮：一个都没搜到 → 自动换个干净点的关键词重来
            if (total == 0) {
                for (v in variants(wd)) {
                    if (total > 0) break
                    setTip("第 1 轮没搜到，自动换成「$v」再搜一遍…")
                    total = runRound(canSearch, v, "第 2 轮（「$v」）", filterAdult)
                }
            }

            // ── 第 3 轮：还是空 → 连站方关了搜索的源也硬试一遍
            if (total == 0 && closed.isNotEmpty()) {
                setTip("还没有，连 ${closed.size} 个关了搜索的源也硬试一遍…")
                runRound(closed, wd, "第 3 轮", filterAdult)
            }

            val n = synchronized(lock) { acc.size }
            ui {
                searching = false
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
        targets: List<Source>,
        wd: String,
        label: String,
        filterAdult: Boolean
    ): Int {
        if (targets.isEmpty()) return synchronized(lock) { acc.size }
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        targets.forEach { s ->
            pool.execute {
                val got = runCatching { VodApi.search(s.api, wd, s.name).list }
                    .getOrDefault(emptyList())
                synchronized(lock) {
                    got.forEach lit@ { v ->
                        if (filterAdult && SourceStore.isAdultCategory(v.typeName)) return@lit
                        val k = keyOf(v.name)
                        if (k.isEmpty() || !seen.add(k)) return@lit
                        acc.add(v)
                    }
                }
                val idx = done.incrementAndGet()
                val snap = synchronized(lock) { ArrayList(acc) }
                ui {
                    adapter.submit(snap, false)
                    tip.text = if (snap.isEmpty())
                        "$label · 已试 $idx/${targets.size} 个源 · 这个源没有"
                    else
                        "$label · 已试 $idx/${targets.size} 个源 · 已找到 ${snap.size} 个"
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(70, TimeUnit.SECONDS)
        return synchronized(lock) { acc.size }
    }
}
