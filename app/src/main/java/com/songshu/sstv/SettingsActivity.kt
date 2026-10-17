package com.songshu.sstv

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.songshu.sstv.cache.CacheMgr
import com.songshu.sstv.cache.DlStore
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.VodApi
import com.songshu.sstv.ui.SourceManagerDialog
import com.songshu.sstv.ui.SourceAction
import com.songshu.sstv.ui.ThemePicker
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.DlOverlay
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : ThemedActivity() {

    private var sourceManager: SourceManagerDialog? = null
    private var themeDialog: Dialog? = null
    private lateinit var cacheInfo: TextView
    private lateinit var swAdult: SwitchCompat
    private lateinit var swCache: SwitchCompat


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<SwitchCompat>(R.id.swRestorePlayback).apply {
            isChecked = PlaybackPreferences.restoreLast(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> PlaybackPreferences.setRestoreLast(this@SettingsActivity, checked) }
        }
        findViewById<TextView>(R.id.themeValue).text = ThemeStore.current(this).label
        findViewById<SwitchCompat>(R.id.swReduceMotion).apply {
            isChecked = ThemeStore.reducedMotion(this@SettingsActivity)
            setOnCheckedChangeListener { _, value -> ThemeStore.setReducedMotion(this@SettingsActivity, value) }
        }
        cacheInfo = findViewById(R.id.cacheInfo)
        swAdult = findViewById(R.id.swFilterAdult)
        swCache = findViewById(R.id.swAutoCache)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnManageSources).setOnClickListener { showSourceManager() }
        findViewById<View>(R.id.btnTheme).setOnClickListener { showAppearance() }
        findViewById<View>(R.id.btnDownloads).setOnClickListener { startActivity(Intent(this, CacheActivity::class.java)) }
        findViewById<View>(R.id.btnClearCache).setOnClickListener {
            Dlg.confirm(this, "清理在线播放缓存", "已缓存的视频不会删除。") {
                Bg.io.execute {
                    CacheMgr.clear(this)
                    showResult { refreshCacheInfo() }
                }
            }
        }

        findViewById<View>(R.id.btnOverlay).setOnClickListener { DlOverlay.openPerm(this) }

        swAdult.isChecked = SourceStore.filterAdult(this)
        swCache.isChecked = SourceStore.autoCache(this)
        swAdult.setOnCheckedChangeListener { _, v -> SourceStore.setFilterAdult(this, v) }
        swCache.setOnCheckedChangeListener { _, v -> SourceStore.setAutoCache(this, v) }

        @Suppress("DEPRECATION")
        val vn = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        findViewById<TextView>(R.id.about).text = vn.ifBlank { "—" }
        if (intent.getBooleanExtra("manageSources", false)) showSourceManager()

    }

    override fun onResume() {
        super.onResume()
        renderSources()
        refreshCacheInfo()
        // 从系统授权页回来时状态可能就变了，每次回来都重看一眼
        refreshOverlay()
    }

    private fun refreshOverlay() {
        findViewById<TextView>(R.id.overlayValue).text = if (DlOverlay.canDraw(this)) "已开启" else "去授权"
    }

    private fun refreshCacheInfo() {
        Bg.io.execute {
            val online = CacheMgr.size(this)
            val dl = DlStore.totalSize(this)
            showResult {
                cacheInfo.text = fmtSize(online)
                findViewById<TextView>(R.id.downloadSize).text = fmtSize(dl)
            }
        }
    }

    // ---------------- 片源 ----------------

    private fun renderSources() {
        findViewById<TextView>(R.id.sourceSummary).text = "${SourceStore.sources(this).size} 个"
        sourceManager?.refresh()
    }

    private fun showSourceManager() {
        if (sourceManager?.isShowing == true) return
        val dialog = SourceManagerDialog(this) { command, source ->
            when (command) {
                SourceAction.SELECT -> source?.let { SourceStore.setCurrent(this, it); renderSources() }
                SourceAction.EDIT -> source?.let { editSource(it) }
                SourceAction.TEST -> source?.let { testSource(it) }
                SourceAction.DELETE -> source?.let { delSource(it) }
                SourceAction.ADD -> addSource()
                SourceAction.IMPORT -> importTvbox()
                SourceAction.TEST_ALL -> testAllSources()
                SourceAction.RESTORE -> restoreDefaults()
            }
        }
        sourceManager = dialog
        dialog.setOnDismissListener { sourceManager = null }
        dialog.show()
    }

    private fun showAppearance() {
        themeDialog = ThemePicker.show(this) { style ->
            if (ThemeStore.current(this).id != style.id) {
                ThemeStore.select(this, style); recreate()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sourceManager?.resize(); themeDialog?.dismiss()
    }

    override fun onDestroy() {
        sourceManager?.dismiss(); themeDialog?.dismiss()
        super.onDestroy()
    }

    private fun addSource() {
        Dlg.input(
            this, "添加片源",
            listOf("片源名称", "接口地址")
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
                                "请检查接口地址或稍后重试。"
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
                            append("\n全部可用。")
                        } else {
                            append("\n暂不可用：\n")
                            badNow.forEach { append("· $it\n") }
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
            "替换为 $n 个预置片源，并移除手动添加的片源？"
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
                        Dlg.tip(this, "没导到东西", "配置中没有可导入的标准接口，或链接无法访问。")
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
                        Dlg.tip(this, "导入完成", "新增 $added 个片源。")
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

}
