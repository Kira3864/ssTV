package com.laoyang.jingying.data

/** 一集 */
data class Episode(val name: String, val url: String)

/** 一条播放线路（MacCMS 的 vod_play_from 里的一项） */
data class PlayLine(val name: String, val episodes: List<Episode>)

/** 影片 */
data class Vod(
    val id: String = "",
    val name: String = "",
    val pic: String = "",
    val remark: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val actor: String = "",
    val director: String = "",
    val content: String = "",
    val lines: List<PlayLine> = emptyList(),
    val sourceApi: String = "",
    val sourceName: String = ""
) {
    val meta: String
        get() = listOf(year, area, typeName).filter { it.isNotBlank() }.joinToString(" · ")

    val firstEpisode: Episode?
        get() = lines.firstOrNull()?.episodes?.firstOrNull()

    val episodeCount: Int
        get() = lines.firstOrNull()?.episodes?.size ?: 0
}

/**
 * 一个分类。
 *
 * MacCMS 的 `ac=list` 返回的分类是**树形**的（实测 30/30 个源都带 `type_pid`）：
 *   顶层 type_pid=0        「电影 / 连续剧 / 综艺 / 动漫」
 *   子层 type_pid=<父id>   「动作片 / 喜剧片…」或「国产剧 / 韩剧 / 美剧…」
 * 于是「类型」和「地区」这两个维度都能靠它表达：
 *   电影 → 动作片/喜剧片/科幻片（类型）
 *   连续剧 → 国产剧/香港剧/韩国剧/欧美剧（地区）
 */
data class VodClass(val id: String, val name: String, val pid: String = "0") {
    val isRoot: Boolean get() = pid.isBlank() || pid == "0"
}

data class VodPage(val list: List<Vod>, val page: Int, val pageCount: Int, val total: Int)

/** MacCMS 数据结构解析工具 */
object Mc {

    private val D = "$$$"

    /**
     * 解析 vod_play_from + vod_play_url。
     * 规则：线路之间用 $$$ 分隔；每路内剧集用 # 分隔；每条剧集为「名称$地址」。
     */
    fun parseLines(playFrom: String, playUrl: String): List<PlayLine> {
        if (playUrl.isBlank()) return emptyList()
        val froms = if (playFrom.isBlank()) emptyList() else playFrom.split(D)
        val urls = playUrl.split(D)
        val out = ArrayList<PlayLine>()
        for (i in urls.indices) {
            val nm = froms.getOrNull(i)?.trim().takeUnless { it.isNullOrBlank() } ?: "线路${i + 1}"
            val eps = ArrayList<Episode>()
            urls[i].split("#").forEach { seg ->
                val s = seg.trim()
                if (s.isEmpty()) return@forEach
                val p = s.indexOf('$')
                if (p < 0) {
                    if (s.startsWith("http")) eps.add(Episode("第${eps.size + 1}集", s))
                } else {
                    val n = s.substring(0, p).trim()
                    val u = s.substring(p + 1).trim()
                    if (u.startsWith("http")) {
                        eps.add(Episode(n.ifBlank { "第${eps.size + 1}集" }, u))
                    }
                }
            }
            if (eps.isNotEmpty()) out.add(PlayLine(nm, eps))
        }
        return out
    }

    /** 去掉简介里的 HTML 标签 */
    fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&").trim()

    /** 判断播放地址是不是 HLS */
    fun isHls(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8")
    }

    /** 文件名安全化 */
    fun safeName(s: String): String {
        val t = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        return if (t.length > 60) t.substring(0, 60) else t
    }
}
