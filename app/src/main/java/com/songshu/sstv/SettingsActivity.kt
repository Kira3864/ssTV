package com.songshu.sstv

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.songshu.sstv.cache.CacheMgr
import com.songshu.sstv.cache.DlStore
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.DlOverlay
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : ThemedActivity() {

    private lateinit var sourceBox: LinearLayout
    private lateinit var sourceCount: TextView
    private lateinit var cacheInfo: TextView
    private lateinit var swAdult: SwitchCompat
    private lateinit var swCache: SwitchCompat
    private lateinit var btnOverlay: TextView

    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<SwitchCompat>(R.id.swRestorePlayback).apply {
            isChecked = PlaybackPreferences.restoreLast(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> PlaybackPreferences.setRestoreLast(this@SettingsActivity, checked) }
        }
        renderAppearance()
        sourceBox = findViewById(R.id.sourceBox)
        sourceCount = findViewById(R.id.sourceCount)
        cacheInfo = findViewById(R.id.cacheInfo)
        swAdult = findViewById(R.id.swFilterAdult)
        swCache = findViewById(R.id.swAutoCache)
        btnOverlay = findViewById(R.id.btnOverlay)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddSource).setOnClickListener { addSource() }
        findViewById<View>(R.id.btnImportTvbox).setOnClickListener { importTvbox() }
        findViewById<View>(R.id.btnRestore).setOnClickListener { restoreDefaults() }
        findViewById<View>(R.id.btnTestAll).setOnClickListener { testAllSources() }
        findViewById<View>(R.id.btnClearCache).setOnClickListener {
            Dlg.confirm(this, "清空在线缓存", "只清「边看边缓存」的临时片段，已下载的整集不受影响。") {
                Bg.io.execute {
                    CacheMgr.clear(this)
                    showResult { refreshCacheInfo() }
                }
            }
        }

        // 桌面悬浮窗：这个开关的「所有权」在系统手里，我们能做的只是把人送过去
        btnOverlay.setOnClickListener {
            if (DlOverlay.canDraw(this)) {
                Dlg.tip(
                    this,
                    getString(R.string.overlay_already_title),
                    getString(R.string.overlay_already_body)
                )
            } else {
                Dlg.confirm(
                    this,
                    getString(R.string.overlay_perm_tip_title),
                    getString(R.string.overlay_perm_tip_body)
                ) { DlOverlay.openPerm(this) }
            }
        }

        swAdult.isChecked = SourceStore.filterAdult(this)
        swCache.isChecked = SourceStore.autoCache(this)
        swAdult.setOnCheckedChangeListener { _, v -> SourceStore.setFilterAdult(this, v) }
        swCache.setOnCheckedChangeListener { _, v -> SourceStore.setAutoCache(this, v) }

        // 版本号从包里读，不再硬编码 —— 之前写死的 "松鼠TV v1.1" 挂了 6 个版本没跟上
        @Suppress("DEPRECATION")
        val vn = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        findViewById<TextView>(R.id.about).text = buildString {
            append("${getString(R.string.app_name)} $vn\n\n")
            append("纯净播放器：没有广告 SDK、没有积分邀请、不上传任何数据。")
            append("所有内容都来自你自己配置的第三方接口，本 App 不提供、不存储、不代理任何影片。\n\n")
            append("搜索是「多片源聚合」的：一次并发搜所有可用源，按片源分组，响应快的排在前面，")
            append("每条结果后面标着它来自哪个源。\n\n")
            append("存储说明：缓存的视频放在 App 私有目录，卸载 App 会一起删除，重要内容请自行导出。")
        }
    }

    override fun onResume() {
        super.onResume()
        renderSources()
        refreshCacheInfo()
        // 从系统授权页回来时状态可能就变了，每次回来都重看一眼
        refreshOverlay()
    }

    private fun refreshOverlay() {
        btnOverlay.text = getString(
            if (DlOverlay.canDraw(this)) R.string.overlay_on else R.string.overlay_off
        )
    }

    private fun refreshCacheInfo() {
        Bg.io.execute {
            val online = CacheMgr.size(this)
            val dl = DlStore.totalSize(this)
            showResult {
                cacheInfo.text = "边看边缓存：${fmtSize(online)}\n已下载整集：${fmtSize(dl)}"
            }
        }
    }

    // ---------------- 片源 ----------------

    private fun renderSources() {
        sourceBox.removeAllViews()
        val all = SourceStore.sources(this)
        val cur = SourceStore.current(this)
        val searchable = all.count { !it.name.contains("(无搜索)") }
        sourceCount.text = "共 ${all.size} 个片源 · $searchable 个支持搜索（带「(无搜索)」的只能浏览分类）"
        all.forEach { s ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(10))
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card)
            }
            val clp = LinearLayout.LayoutParams(-1, -2)
            clp.setMargins(dp(16), dp(8), dp(16), 0)
            card.layoutParams = clp

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nm = TextView(this).apply {
                text = if (s.api == cur.api) "${s.name}   ✓ 当前" else s.name
                setTextColor(this@SettingsActivity.themeColor(R.attr.appText))
                textSize = 15f
            }
            head.addView(nm, LinearLayout.LayoutParams(0, -2, 1f))
            val del = TextView(this).apply {
                text = "删除"
                textSize = 13f
                setTextColor(this@SettingsActivity.themeColor(R.attr.appAccent))
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { delSource(s) }
            }
            head.addView(del)
            card.addView(head)

            card.addView(TextView(this).apply {
                text = s.api
                textSize = 11.5f
                setPadding(0, dp(4), 0, 0)
                setTextColor(this@SettingsActivity.themeColor(R.attr.appTextMuted))
            })

            val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val olp = LinearLayout.LayoutParams(-1, -2)
            olp.topMargin = dp(10)
            ops.layoutParams = olp
            if (s.api != cur.api) {
                ops.addView(smallBtn("设为当前") {
                    SourceStore.setCurrent(this, s)
                    renderSources()
                })
            }
            ops.addView(smallBtn("编辑") { editSource(s) })
            ops.addView(smallBtn("测一下") { testSource(s) })
            card.addView(ops)

            sourceBox.addView(card)
        }
    }

    private fun smallBtn(label: String, on: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(this@SettingsActivity.themeColor(R.attr.appText))
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_btn_ghost)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { on() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        return tv
    }

    private fun addSource() {
        Dlg.input(
            this, "添加片源",
            listOf("名称，例如：某某资源", "接口地址，例如：https://xxx.com/api.php/provide/vod")
        ) { v ->
            val nm = v.getOrElse(0) { "" }.ifBlank { "新片源" }
            val api = VodApi.normalize(v.getOrElse(1) { "" })
            if (api.isBlank() || api == "https://") {
                Dlg.tip(this, "没填地址", "接口地址不能为空。")
                return@input
            }
            if (!validApi(api)) return@input
            val list = SourceStore.sources(this)
            if (list.any { it.api == api }) {
                Dlg.tip(this, "片源已存在", "这个接口已经在列表中。")
                return@input
            }
            list.add(Source(nm, api))
            SourceStore.save(this, list)
            renderSources()
        }
    }

    private fun editSource(s: Source) {
        Dlg.input(
            this, "编辑片源",
            listOf("名称", "接口地址"),
            listOf(s.name, s.api)
        ) { v ->
            val nm = v.getOrElse(0) { "" }.ifBlank { s.name }
            val api = VodApi.normalize(v.getOrElse(1) { "" }).ifBlank { s.api }
            if (!validApi(api)) return@input
            val cur = SourceStore.current(this)
            val list = SourceStore.sources(this)
            if (list.any { it.api == api && it != s }) {
                Dlg.tip(this, "片源已存在", "这个接口已经在列表中。")
                return@input
            }
            val i = list.indexOfFirst { it.name == s.name && it.api == s.api }
            if (i < 0) return@input
            list[i] = Source(nm, api)
            SourceStore.save(this, list)
            if (cur.api == s.api) SourceStore.setCurrent(this, list[i])
            renderSources()
        }
    }

    private fun delSource(s: Source) {
        Dlg.confirm(this, "删除片源", "删掉「${s.name}」？") {
            val list = SourceStore.sources(this)
            if (list.size <= 1) {
                Dlg.tip(this, "保留一个片源", "请先添加其他片源，再删除最后一个。")
                return@confirm
            }
            val cur = SourceStore.current(this)
            list.removeAll { it.name == s.name && it.api == s.api }
            SourceStore.save(this, list)
            if (cur.api == s.api && list.isNotEmpty()) SourceStore.setCurrent(this, list[0])
            renderSources()
        }
    }

    private fun testSource(s: Source) {
        Dlg.tip(this, "测一下", "正在请求「${s.name}」…")
        Bg.io.execute {
            val r = runCatching { VodApi.classes(s.api) }
            val n = r.getOrNull()?.size ?: 0
            showResult {
                if (n > 0) {
                    Dlg.tip(this, "通了", "「${s.name}」返回了 $n 个分类，可以用。")
                } else {
                    Dlg.tip(
                        this, "没通",
                        "「${s.name}」没返回数据：${r.exceptionOrNull()?.message ?: "空响应"}\n\n" +
                                "常见原因：域名挂了、被墙、接口路径不对、或者它需要授权。"
                    )
                }
            }
        }
    }

    /** 一键检测所有片源（并发跑，8 秒超时） */
    private fun testAllSources() {
        val all = SourceStore.sources(this)
        if (all.isEmpty()) return
        Dlg.confirm(this, "一键检测", "并发请求 ${all.size} 个片源，大概几秒到十几秒。开始？") {
            Dlg.tip(this, "检测中", "正在测 ${all.size} 个片源，稍等…")
            Bg.io.execute {
                val okList = java.util.Collections.synchronizedList(ArrayList<String>())
                val badList = java.util.Collections.synchronizedList(ArrayList<String>())
                val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                all.forEach { s ->
                    pool.execute {
                        val ok = runCatching { VodApi.classes(s.api).isNotEmpty() }
                            .getOrDefault(false)
                        if (ok) okList.add(s.name) else badList.add(s.name)
                    }
                }
                pool.shutdown()
                pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS)
                val okN = okList.size
                val badNow = ArrayList(badList)
                showResult {
                    val msg = buildString {
                        append("可用 $okN / ${all.size} 个。\n")
                        if (badNow.isEmpty()) {
                            append("\n全部正常，随便用。")
                        } else {
                            append("\n下面这些现在连不上（域名挂了或被墙，过阵子可能自己好）：\n")
                            badNow.forEach { append("· $it\n") }
                            append("\n在列表里把它们删掉，或者手动添加别的源都可以。")
                        }
                    }
                    Dlg.tip(this, "检测完成", msg.trim())
                }
            }
        }
    }

    /** 恢复出厂预置片源 */
    private fun restoreDefaults() {
        val n = SourceStore.DEFAULT.size
        Dlg.confirm(
            this, "恢复预置片源",
            "会把片源列表换成出厂预置的 $n 个（都实测能连上），" +
                    "你自己手动添加的会被清掉。要继续吗？"
        ) {
            SourceStore.restoreDefaults(this)
            renderSources()
            Dlg.tip(
                this, "已恢复",
                "现在共 ${SourceStore.sources(this).size} 个片源，默认用「${SourceStore.current(this).name}」。"
            )
        }
    }

    /** 从 TVBox / 影视仓 配置里挑出「可直接用的标准接口」 */
    private fun importTvbox() {
        Dlg.input(this, "从配置链接导入", listOf("粘贴 TVBox 配置地址（0.json 那种）")) { v ->
            val url = v.getOrElse(0) { "" }.trim()
            if (url.isBlank()) return@input
            Dlg.tip(
                this, "说明",
                "只会导入配置里 type=1 的标准接口。\n\n" +
                        "type=3 的那种（csp_ 开头）需要 TVBox 自带的脚本引擎才能跑，" +
                        "本 App 不做，所以会被跳过 —— 这不是漏了，是跑不了。"
            )
            Bg.io.execute {
                val txt = runCatching { VodApi.get(url, 20000) }.getOrNull()
                val found = ArrayList<Source>()
                if (txt != null) {
                    runCatching {
                        val arr = JSONObject(txt).optJSONArray("sites") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val t = o.optInt("type", -1)
                            val raw = o.optString("api").trim()
                            val nm = o.optString("name").trim()
                            if (t == 1 && raw.startsWith("http")) {
                                found.add(Source(nm.ifBlank { "源${found.size + 1}" }, VodApi.normalize(raw)))
                            }
                        }
                    }
                }
                showResult {
                    if (found.isEmpty()) {
                        Dlg.tip(this, "没导到东西", "这个配置里没有 type=1 的可用接口，或者链接打不开。")
                    } else {
                        val list = SourceStore.sources(this)
                        var added = 0
                        found.forEach { s ->
                            if (list.none { it.api == s.api }) {
                                list.add(s)
                                added++
                            }
                        }
                        SourceStore.save(this, list)
                        renderSources()
                        Dlg.tip(this, "导入完成", "新增 $added 个片源。挨个点「测一下」，能通的才会好用。")
                    }
                }
            }
        }
    }

    private fun validApi(api: String): Boolean {
        val valid = runCatching {
            val url = java.net.URI(api)
            url.scheme in listOf("http", "https") && !url.host.isNullOrBlank()
        }.getOrDefault(false)
        if (!valid) Dlg.tip(this, "地址不正确", "请输入有效的 HTTP 或 HTTPS 接口地址。")
        return valid
    }

    private fun renderAppearance() {
        val box = findViewById<LinearLayout>(R.id.themeOptions)
        val current = ThemeStore.current(this)
        ThemeStore.styles.forEach { style ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(style.background)
                    cornerRadius = dp(style.radius.toInt()).toFloat()
                    setStroke(dp(if (current.id == style.id) 2 else 1),
                        if (current.id == style.id) style.accent else style.surface)
                }
                contentDescription = "${style.label}，${style.description}，${if (current.id == style.id) "已选中" else "点按应用"}"
                isSelected = current.id == style.id
            }
            card.addView(TextView(this).apply {
                text = "${style.label}    ${if (current.id == style.id) "✓ 使用中" else "↗"}"
                setTextColor(style.ink)
                textSize = 17f
                typeface = android.graphics.Typeface.create(style.font, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = style.description
                textSize = 12f
                setTextColor(style.ink)
                alpha = .8f
                setPadding(0, dp(6), 0, dp(12))
            })
            val sample = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(style.accent, style.surface, style.ink).forEach { color ->
                sample.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = dp(style.radius.toInt().coerceAtMost(10)).toFloat()
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(8)).apply { rightMargin = dp(6) })
            }
            card.addView(sample)
            card.setOnClickListener {
                if (current.id != style.id) {
                    ThemeStore.select(this, style)
                    recreate()
                }
            }
            com.songshu.sstv.ui.Motion.interactive(card)
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        findViewById<SwitchCompat>(R.id.swReduceMotion).apply {
            isChecked = ThemeStore.reducedMotion(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> ThemeStore.setReducedMotion(this@SettingsActivity, checked) }
        }
    }

    private fun dp(v: Int) = (v * d).toInt()
}
