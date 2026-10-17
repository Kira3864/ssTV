package com.songshu.sstv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.data.VodClass
import com.songshu.sstv.ui.VodAdapter
import java.util.Calendar

/**
 * 首页：三级筛选 + 影片网格。
 *
 * 筛选维度对应 MacCMS 接口的两套能力（2026-09-25 逐个实测过）：
 *
 *   类型 / 地区 —— 走**分类树**（`t=` 参数），30/30 个源都带 `type_pid`，100% 有效：
 *       电影  → 动作片 / 喜剧片 / 科幻片 / 恐怖片 …        （类型）
 *       连续剧 → 国产剧 / 香港剧 / 韩国剧 / 欧美剧 / 泰剧 … （地区）
 *       动漫  → 国产动漫 / 日韩动漫 / 欧美动漫 …           （地区）
 *   时间      —— 走 `year=` 参数，单年 `2024` 和区间 `2015-2019` 实测有效。
 *       注意：`yr=` 是无效的（被服务端忽略）；开区间（`2019-`）各源行为不一致，没用。
 *       这个参数**只有约一半的源支持**，不支持的源会忽略它、照常返回全部内容。
 *
 * 一个接口限制：父分类节点自身通常没有内容
 * （实测「电影」这个节点在非凡/电影天堂/360 上直接查都是 0 条，内容全挂在子类上），
 * 而多值 `t=6,7,8` 会被服务端当整数取第一个（实测确认），所以**做不了「全部电影」**。
 * 因此选中一级分类后会自动落到它的第一个子类，二级横条里再左右挑。
 */
class MainActivity : ThemedActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var adapter: VodAdapter
    private lateinit var catRow: LinearLayout
    private lateinit var subRow: LinearLayout
    private lateinit var yearRow: LinearLayout
    private lateinit var subScroll: HorizontalScrollView
    private lateinit var yearScroll: HorizontalScrollView
    private lateinit var progress: View
    private lateinit var hint: TextView
    private lateinit var btnMore: TextView
    private lateinit var sourceLabel: TextView

    /** 一级分类（已过滤成人） */
    private var roots: List<VodClass> = emptyList()

    /** 一级分类 id → 它的子类 */
    private val kids = HashMap<String, List<VodClass>>()

    /** 年份选项：传给接口的值 → 显示文案 */
    private var years: List<Pair<String, String>> = emptyList()

    /** 当前一级分类 id；空串 = 「最新更新」（不带 t 参数）；null = 还没初始化 */
    private var curRoot: String? = null

    /** 当前二级分类 id。一级本身是叶子分类（没有子类）时为空 */
    private var curSub: String = ""

    /** 当前年份筛选值，空 = 不限年份 */
    private var curYear: String = ""

    private var page = 1
    private var pageCount = 1
    private var loading = false
    private var loadedFor = ""
    private var classRequest = 0
    private var pageRequest = 0
    private var classJob: java.util.concurrent.Future<*>? = null
    private var pageJob: java.util.concurrent.Future<*>? = null
    private var restoredFilter: Bundle? = null

    private val src: Source get() = SourceStore.current(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredFilter = savedInstanceState
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        catRow = findViewById(R.id.catRow)
        subRow = findViewById(R.id.subRow)
        yearRow = findViewById(R.id.yearRow)
        subScroll = findViewById(R.id.subScroll)
        yearScroll = findViewById(R.id.yearScroll)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        btnMore = findViewById(R.id.btnMore)
        sourceLabel = findViewById(R.id.sourceLabel)

        adapter = VodAdapter(dp(150)) { openDetail(it) }
        applySpan()
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setItemViewCacheSize(8)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !loading && page < pageCount &&
                    (grid.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= adapter.itemCount - 9) {
                    loadPage(page + 1, true)
                }
            }
        })

        findViewById<View>(R.id.filterToggle).setOnClickListener {
            val panel = findViewById<View>(R.id.filterPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateFilterLabel()
        }
        findViewById<View>(R.id.btnViewMode).setOnClickListener { display.menu(it) { applySpan() } }
        sourceLabel.setOnClickListener { pickSource() }
        hint.setOnClickListener { if (curRoot == null) loadClasses() else loadPage(1, false) }
        btnMore.setOnClickListener {
            if (page < pageCount) loadPage(page + 1, append = true)
        }
    }

    override fun onResume() {
        super.onResume()
        val s = src
        sourceLabel.text = "${s.name} ▾"
        val identity = "${s.api}|${s.name}|${SourceStore.filterAdult(this)}"
        if (loadedFor != identity) {
            loadedFor = identity
            loadClasses()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySpan()
    }

    private val display by lazy { com.songshu.sstv.ui.DisplayPrefs(this, "home") }
    private fun applySpan() {
        val w = resources.configuration.screenWidthDp
        val span = if (display.grid) display.columns else 1
        adapter.listMode = !display.grid
        grid.layoutManager = GridLayoutManager(this, span)
        val padDp = 20
        val itemDp = (w - padDp) / span
        adapter.setPicHeight(((itemDp - 12) * 1.42f * resources.displayMetrics.density).toInt())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pickSource() {
        val all = SourceStore.sources(this)
        com.songshu.sstv.ui.SourcePicker.show(this, all, src.api) { selected ->
            SourceStore.setCurrent(this, selected)
            sourceLabel.text = "${selected.name} ▾"
            loadedFor = "${selected.api}|${selected.name}|${SourceStore.filterAdult(this)}"
            loadClasses()
        }
    }

    private fun updateFilterLabel() {
        val values = listOf(roots.firstOrNull { it.id == curRoot }?.name ?: "最新更新",
            kids[curRoot].orEmpty().firstOrNull { it.id == curSub }?.name.orEmpty(),
            if (curYear.isBlank()) "" else yearText()).filter { it.isNotBlank() }
        val open = findViewById<View>(R.id.filterPanel).visibility == View.VISIBLE
        findViewById<TextView>(R.id.filterToggle).text = values.joinToString(" · ") + if (open) "    收起 ▴" else "    筛选 ▾"
    }

    // ───────────────────────── 分类加载 ─────────────────────────

    private fun loadClasses() {
        classJob?.cancel(true)
        pageJob?.cancel(true)
        val request = ++classRequest
        ++pageRequest
        loading = false
        curRoot = null
        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        btnMore.visibility = View.GONE
        catRow.removeAllViews()
        subRow.removeAllViews()
        yearRow.removeAllViews()
        subScroll.visibility = View.GONE
        yearScroll.visibility = View.GONE
        adapter.clear()
        roots = emptyList()
        kids.clear()
        val s = src
        classJob = Bg.io.submit {
            val r = runCatching { VodApi.classes(s.api) }
            showResult {
                if (request != classRequest) return@showResult
                progress.visibility = View.GONE
                val all = r.getOrNull()
                if (all.isNullOrEmpty()) {
                    hint.text = "这个源连不上或已失效，去设置里换一个"
                    hint.visibility = View.VISIBLE
                    return@showResult
                }
                // 成人分类在这一层就滤掉（「理论片」「伦理片」「里番动漫」等都会被命中）
                val ok = if (SourceStore.filterAdult(this)) {
                    all.filter { !SourceStore.isAdultCategory(it.name) }
                } else all

                roots = ok.filter { it.isRoot }
                // 子类按父 id 归组；父分类自己不在列表里的（数据不规范）也算叶子
                val byPid = ok.filter { !it.isRoot }.groupBy { it.pid }
                kids.clear()
                roots.forEach { root ->
                    val c = byPid[root.id].orEmpty()
                    if (c.isNotEmpty()) kids[root.id] = c
                }

                buildYearOptions()
                // 默认落到第一个真实大类（「电影片」），而不是「最新更新」。
                // 原因：「最新更新」没有子分类，第二排会整行隐藏，
                // 一打开只剩一排会让人以为筛选功能根本没做（v1.6 就是这么被误会的）。
                val saved = restoredFilter?.takeIf { it.getString("sourceApi") == s.api }
                val selectedRoot = saved?.getString("root")
                curRoot = if (selectedRoot == "" || roots.any { it.id == selectedRoot }) selectedRoot else roots.firstOrNull()?.id ?: ""
                curSub = saved?.getString("sub").orEmpty()
                curYear = saved?.getString("year").orEmpty().takeIf { y -> years.any { it.first == y } }.orEmpty()
                restoredFilter = null
                buildRootRow()
                buildSubRow()
                buildYearRow()
                page = 1
                loadPage(1, append = false)
            }
        }
    }

    /** 一级：最新更新 / 电影 / 连续剧 / 综艺 / 动漫 … */
    private fun buildRootRow() {
        catRow.removeAllViews()
        val items = ArrayList<VodClass>()
        items.add(VodClass("", "最新更新"))
        items.addAll(roots)
        items.forEachIndexed { i, c ->
            val tv = chip(c.name, small = false)
            tv.setOnClickListener {
                if (curRoot != c.id) {
                    curRoot = c.id
                    curSub = ""            // 换大类，二级重置（buildSubRow 会选好默认子类）
                    page = 1
                    highlight(catRow, i)
                    buildSubRow()
                    loadPage(1, append = false)
                }
            }
            catRow.addView(tv)
        }
        // 高亮跟着 curRoot 走（默认是第一个真实大类，不再是写死的第 0 项）
        val idx = items.indexOfFirst { it.id == curRoot }
        highlight(catRow, if (idx >= 0) idx else 0)
    }

    /**
     * 二级：类型 / 地区。
     * 选「最新更新」或该一级没有子类时整行隐藏。
     */
    private fun buildSubRow() {
        subRow.removeAllViews()
        subScroll.scrollTo(0, 0)
        val root = curRoot ?: ""
        val cs = kids[root].orEmpty()
        if (root.isBlank() || cs.isEmpty()) {
            subScroll.visibility = View.GONE
            curSub = ""               // 一级本身是叶子分类，直接用它的 id 查
            return
        }
        subScroll.visibility = View.VISIBLE

        // 父分类节点自身通常没内容（实测 0 条），默认落到第一个子类
        if (cs.none { it.id == curSub }) curSub = cs.first().id
        cs.forEachIndexed { i, c ->
            val tv = chip(c.name, small = true)
            tv.setOnClickListener {
                if (curSub != c.id) {
                    curSub = c.id
                    page = 1
                    highlight(subRow, i)
                    loadPage(1, append = false)
                }
            }
            subRow.addView(tv)
        }
        highlight(subRow, cs.indexOfFirst { it.id == curSub })
    }

    /**
     * 年份选项。列表按当前年份算，不用手写死：
     *   不限年份 / 今年起往前 7 个单年 / 再往前 5 年一个区间 / 更早
     * 单片单年、区间两种写法都实测有效（`2015-2019` 能让服务端按区间筛）。
     */
    private fun buildYearOptions() {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        val out = ArrayList<Pair<String, String>>()
        out.add("" to "不限年份")
        for (y in now downTo now - 6) out.add(y.toString() to y.toString())
        out.add("${now - 11}-${now - 7}" to "${now - 11}-${now - 7}")
        out.add("1990-${now - 12}" to "更早")
        years = out
    }

    private fun buildYearRow() {
        yearRow.removeAllViews()
        yearScroll.scrollTo(0, 0)
        years.forEachIndexed { i, p ->
            val tv = chip(p.second, small = true)
            tv.setOnClickListener {
                if (curYear != p.first) {
                    curYear = p.first
                    page = 1
                    highlight(yearRow, i)
                    loadPage(1, append = false)
                }
            }
            yearRow.addView(tv)
        }
        // ⚠️ v1.6 的真 bug：loadClasses() 里把 yearScroll 设成了 GONE，
        // 而这里从来没把它设回 VISIBLE —— 所以「年份」整行永远不显示。
        yearScroll.visibility = View.VISIBLE
        highlight(yearRow, years.indexOfFirst { it.first == curYear })
    }

    private fun chip(text: String, small: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = if (small) 12f else 13f
        tv.setPadding(dp(if (small) 12 else 14), dp(if (small) 6 else 7),
            dp(if (small) 12 else 14), dp(if (small) 6 else 7))
        tv.gravity = android.view.Gravity.CENTER
        tv.includeFontPadding = false
        val lp = LinearLayout.LayoutParams(-2, dp(34))
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        com.songshu.sstv.ui.Motion.interactive(tv)
        tv.background = getDrawable(R.drawable.bg_chip)
        tv.setTextColor(themeColor(R.attr.appTextMuted))
        return tv
    }

    private fun highlight(row: LinearLayout, index: Int) {
        for (k in 0 until row.childCount) {
            val tv = row.getChildAt(k) as TextView
            if (k == index) {
                tv.setTextColor(themeColor(R.attr.appOnAccent))
                tv.background = getDrawable(R.drawable.bg_btn_primary)
            } else {
                tv.setTextColor(themeColor(R.attr.appTextMuted))
                com.songshu.sstv.ui.Motion.interactive(tv)
        tv.background = getDrawable(R.drawable.bg_chip)
            }
        }
    }

    /** 当前的年份文案，用于提示语 */
    private fun yearText(): String =
        years.firstOrNull { it.first == curYear }?.second ?: curYear

    // ───────────────────────── 列表加载 ─────────────────────────

    private fun loadPage(p: Int, append: Boolean) {
        updateFilterLabel()
        if (curRoot == null || (append && loading)) return
        pageJob?.cancel(true)
        val request = ++pageRequest
        loading = true
        btnMore.visibility = View.GONE
        if (!append) {
            progress.visibility = View.VISIBLE
            hint.visibility = View.GONE
            adapter.clear()
        }
        val s = src
        // 有子类就用子类；一级本身是叶子就用它自己；「最新更新」不带 t
        val type = if (curSub.isNotBlank()) curSub else (curRoot ?: "")
        val year = curYear
        pageJob = Bg.io.submit {
            val r = runCatching { VodApi.listByType(s.api, type, p, s.name, year) }
            showResult {
                if (request != pageRequest) return@showResult
                loading = false
                progress.visibility = View.GONE
                val pg = r.getOrNull()
                if (pg == null) {
                    if (adapter.dataCount == 0) {
                        hint.text = getString(R.string.error_net) + "\n轻触重试"
                        hint.visibility = View.VISIBLE
                    }
                    btnMore.visibility = if (append) View.VISIBLE else View.GONE
                    return@showResult
                }
                page = pg.page
                pageCount = pg.pageCount
                val items = if (SourceStore.filterAdult(this)) {
                    pg.list.filter { !SourceStore.isAdultCategory(it.typeName) }
                } else pg.list
                adapter.submit(items, append)
                if (adapter.dataCount == 0) {
                    hint.text = when {
                        year.isNotBlank() ->
                            "「${yearText()}」这个年份在这条分类下没有内容。\n" +
                                    "也可能是这个源不支持按年份筛 —— 换个年份、或者换个源试试"
                        type.isNotBlank() -> "这个分类是空的，换一个分类看看"
                        else -> "这个源暂时没返回内容，右上角换一个片源试试"
                    }
                    hint.visibility = View.VISIBLE
                }
                btnMore.visibility = if (page < pageCount) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sourceApi", src.api)
        outState.putString("root", curRoot)
        outState.putString("sub", curSub)
        outState.putString("year", curYear)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        classJob?.cancel(true)
        pageJob?.cancel(true)
        super.onDestroy()
    }

    private fun openDetail(v: com.songshu.sstv.data.Vod) {
        if (v.id.isBlank()) return
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra("id", v.id)
                .putExtra("name", v.name)
                .putExtra("api", v.sourceApi)
                .putExtra("srcName", v.sourceName)
                .putExtra("pic", v.pic)
        )
    }
}
