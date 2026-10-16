package com.laoyang.jingying.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Source(val name: String, val api: String)

/**
 * 片源与偏好设置。
 * 净影本身不提供任何内容，只对接用户填写的 MacCMS 标准 JSON 接口。
 *
 * 预置源全部经过**真机同规则实测**（禁代理直连、按 App 的请求方式逐个验证
 * 分类 / 搜索 / 播放地址三个环节），不含任何成人内容源。
 */
object SourceStore {

    private const val PREF = "jingying"
    private const val K_SRC = "sources"
    private const val K_CUR = "current"
    private const val K_ADULT = "filter_adult"
    private const val K_AUTOCACHE = "auto_cache"
    private const val K_PRESET_VER = "preset_ver"

    /**
     * 预置源版本号。每次改动 DEFAULT 就 +1：
     * 老用户升级后会自动把**新增的**源补进他的列表，他自己加的源不会被动。
     */
    private const val PRESET_VER = 2

    /**
     * 出厂预置源。
     * 带「(无搜索)」后缀的，是该资源站**主动关闭了搜索接口**（返回"暂不支持搜索"），
     * 只能靠首页分类浏览，聚合搜索时不会有结果 —— 不是 App 的问题。
     */
    val DEFAULT: List<Source> = listOf(
        // —— 搜索 / 分类 / 播放地址 三项全通 ——
        Source("暴风资源", "https://bfzyapi.com/api.php/provide/vod"),
        Source("非凡资源", "https://api.ffzyapi.com/api.php/provide/vod"),
        Source("量子影视", "https://cj.lziapi.com/api.php/provide/vod"),
        Source("红牛资源", "https://www.hongniuzy2.com/api.php/provide/vod"),
        Source("虎牙资源", "https://www.huyaapi.com/api.php/provide/vod/at/json"),
        Source("豪华资源", "https://hhzyapi.com/api.php/provide/vod"),
        Source("极速资源", "https://jszyapi.com/api.php/provide/vod"),
        Source("金鹰点播", "https://jinyingzy.com/api.php/provide/vod"),
        Source("金鹰资源", "https://jyzyapi.com/provide/vod/from/jinyingyun/at/json"),
        Source("速播资源", "https://subocaiji.com/api.php/provide/vod"),
        Source("无尽影视", "https://api.wujinapi.com/api.php/provide/vod"),
        Source("最大资源", "https://api.zuidapi.com/api.php/provide/vod"),
        Source("猫眼资源", "https://api.maoyanapi.top/api.php/provide/vod"),
        Source("飘零资源", "https://p2100.net/api.php/provide/vod"),
        Source("魔都资源", "https://www.mdzyapi.com/api.php/provide/vod"),
        Source("魔都动漫", "https://caiji.moduapi.cc/api.php/provide/vod"),
        Source("爱奇艺资源", "https://iqiyizyapi.com/api.php/provide/vod"),
        Source("电影天堂", "https://caiji.dyttzyapi.com/api.php/provide/vod"),
        Source("360资源", "https://360zy.com/api.php/provide/vod"),
        Source("新浪资源", "https://api.xinlangapi.com/xinlangapi.php/provide/vod"),
        Source("非凡影视", "https://cj.ffzyapi.com/api.php/provide/vod"),
        // —— 「最新更新」可用，但这些站关闭了搜索接口 ——
        Source("天涯影视(无搜索)", "https://tyyszy.com/api.php/provide/vod"),
        Source("快车资源(无搜索)", "https://caiji.kuaichezy.org/api.php/provide/vod"),
        Source("闪电资源(无搜索)", "https://xsd.sdzyapi.com/api.php/provide/vod"),
        Source("鸭鸭资源(无搜索)", "https://cj.yayazy.net/api.php/provide/vod"),
        Source("索尼资源(无搜索)", "https://suoniapi.com/api.php/provide/vod"),
        Source("樱花资源(无搜索)", "https://m3u8.apiyhzy.com/api.php/provide/vod"),
        Source("无尽资源(无搜索)", "https://api.wujinapi.me/api.php/provide/vod"),
        Source("茅台资源(无搜索)", "https://caiji.maotaizy.cc/api.php/provide/vod"),
        Source("豆瓣资源(无搜索)", "https://caiji.dbzy5.com/api.php/provide/vod")
    )

    /** 分类名命中就认定为成人/福利内容 */
    private val ADULT_WORDS = listOf(
        "理论", "伦理", "福利", "里番", "成人", "性感", "写真", "午夜",
        "激情", "自拍", "主播", "三级", "无码", "情色", "巨乳", "美乳",
        "福利片", "福利姬", "麻豆", "黑料", "里番剧"
    )

    /** "AV" 这类短英文词必须按独立词匹配，否则会误伤 Travel / Brave 之类 */
    private val ADULT_RE = Regex("(^|[^A-Za-z])av([^A-Za-z]|$)", RegexOption.IGNORE_CASE)

    fun isAdultCategory(name: String): Boolean =
        ADULT_WORDS.any { name.contains(it) } || ADULT_RE.containsMatchIn(name)

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun sources(c: Context): MutableList<Source> {
        val sp = sp(c)
        val raw = sp.getString(K_SRC, null)
        val out = ArrayList<Source>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val n = o.optString("name").trim()
                    val u = o.optString("api").trim()
                    if (n.isNotEmpty() && u.isNotEmpty()) out.add(Source(n, u))
                }
            }
        }
        // 升级后把新增的预置源补进来（用户自己加的源不动）
        if (sp.getInt(K_PRESET_VER, 0) < PRESET_VER) {
            DEFAULT.forEach { d ->
                if (out.none { it.api == d.api }) out.add(d)
            }
            sp.edit().putInt(K_PRESET_VER, PRESET_VER).apply()
            if (out.isNotEmpty()) save(c, out)
        }
        if (out.isEmpty()) out.addAll(DEFAULT)
        return out
    }

    fun save(c: Context, list: List<Source>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("api", it.api)) }
        sp(c).edit().putString(K_SRC, a.toString()).apply()
    }

    /** 恢复出厂片源：清掉现有列表，换成预置的（用户自己加的会被清掉，需二次确认） */
    fun restoreDefaults(c: Context) {
        save(c, DEFAULT)
        sp(c).edit()
            .putInt(K_PRESET_VER, PRESET_VER)
            .putString(K_CUR, DEFAULT.first().name)
            .apply()
    }

    fun current(c: Context): Source {
        val cur = sp(c).getString(K_CUR, null)
        val all = sources(c)
        return all.firstOrNull { it.name == cur } ?: all.first()
    }

    fun setCurrent(c: Context, s: Source) {
        sp(c).edit().putString(K_CUR, s.name).apply()
    }

    fun filterAdult(c: Context): Boolean = sp(c).getBoolean(K_ADULT, true)

    fun setFilterAdult(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_ADULT, v).apply()
    }

    fun autoCache(c: Context): Boolean = sp(c).getBoolean(K_AUTOCACHE, true)

    fun setAutoCache(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_AUTOCACHE, v).apply()
    }
}
