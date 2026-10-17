package com.songshu.sstv.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MacCMS（苹果CMS v10）标准 JSON 接口封装。
 * 这也是国内绝大多数「影视 JSON 接口」通用的规范：
 *   ac=list        取分类
 *   ac=videolist   分类浏览 / 关键词搜索
 *   ac=detail      按 ids 取详情（含播放地址）
 */
object VodApi {

    const val UA = "Mozilla/5.0 (Linux; Android 12; zh_CN) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 把用户填的一坨地址规整成可用的接口地址 */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        while (s.endsWith("/")) s = s.dropLast(1)
        // 已经指向具体接口的（含 .php、provide/vod、/vod/from/xx/at/json、apijson）就别再补默认路径
        val hasApi = s.contains(".php") || s.contains("provide/vod") ||
                s.contains("/vod") || s.contains("apijson")
        if (!hasApi) s = "$s/api.php/provide/vod"
        return s
    }

    private fun referer(url: String): String = runCatching {
        val u = URL(url)
        "${u.protocol}://${u.host}/"
    }.getOrDefault("")

    fun get(url: String, timeout: Int = 15000): String {
        var last: Exception? = null
        repeat(2) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                val r = referer(url)
                if (r.isNotEmpty()) conn.setRequestProperty("Referer", r)
                try {
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (body.isNotBlank()) return body
                    last = IllegalStateException("接口返回空内容 http=$code")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    fun classes(base: String): List<VodClass> {
        val arr = JSONObject(get("$base?ac=list")).optJSONArray("class") ?: return emptyList()
        val out = ArrayList<VodClass>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("type_id")?.toString()?.trim() ?: continue
            val nm = o.optString("type_name").trim()
            // type_pid 父分类 id，用来组两级分类树；没有这个字段的源按顶层算
            val pid = o.opt("type_pid")?.toString()?.trim().orEmpty()
            if (id.isNotEmpty() && nm.isNotEmpty()) {
                out.add(VodClass(id, nm, if (pid.isEmpty()) "0" else pid))
            }
        }
        return out
    }

    /**
     * 分类浏览。typeId 传空串表示「最新更新」—— 不带 t 参数，
     * 实测所有源都支持，而且比分类更靠谱（很多源的头几个分类本身是空的）。
     *
     * [year] 为空表示不限年份。写法实测结论（2026-09-25）：
     *   单年 `2024`、区间 `2015-2019` 都有效；
     *   **`yr=` 完全无效**（被忽略），必须用 `year=`；
     *   开区间 `2019-`、`-2010` 各源行为不一致（有的返回 0），不用。
     */
    fun listByType(
        base: String,
        typeId: String,
        page: Int,
        sourceName: String,
        year: String = ""
    ): VodPage {
        val sb = StringBuilder("$base?ac=videolist&pg=$page")
        if (typeId.isNotBlank()) {
            // 一级分类下面是子类，直接查一级本身很可能返回 0 条
            // （实测：非凡/电影天堂/360 的「电影」节点自身都是 0 条，内容全挂在子类上）。
            // 所以这里可以把多个 id 一起传，实现「全部」。
            sb.append("&t=").append(typeId)
        }
        if (year.isNotBlank()) sb.append("&year=").append(year)
        return parsePage(get(sb.toString()), base, sourceName)
    }

    /**
     * 搜索。绝大多数站认 `ac=videolist&wd=`；
     * 少数站只认 `ac=detail&wd=`（如魔都资源），所以两种都试一遍。
     * 站方主动关闭搜索时会回「暂不支持搜索」这种非 JSON —— 这时按「空结果」处理，不算失败。
     */
    fun search(base: String, wd: String, sourceName: String, page: Int = 1): VodPage {
        val q = URLEncoder.encode(wd, "UTF-8")
        var netErr: Exception? = null
        for (ac in listOf("videolist", "detail")) {
            val body = try {
                get("$base?ac=$ac&wd=$q&pg=$page")
            } catch (e: Exception) {
                netErr = e
                continue
            }
            val pg = try {
                parsePage(body, base, sourceName)
            } catch (e: Exception) {
                continue          // 返回的不是 JSON，换个写法再试
            }
            if (pg.list.isNotEmpty()) return pg
        }
        if (netErr != null) throw netErr
        return VodPage(emptyList(), 1, 1, 0)
    }

    fun detail(base: String, id: String, sourceName: String): Vod? {
        val arr = JSONObject(get("$base?ac=detail&ids=$id")).optJSONArray("list") ?: return null
        if (arr.length() == 0) return null
        return toVod(arr.optJSONObject(0) ?: return null, base, sourceName)
    }

    private fun parsePage(body: String, base: String, srcName: String): VodPage {
        val j = JSONObject(body)
        val arr = j.optJSONArray("list") ?: return VodPage(emptyList(), 1, 1, 0)
        val out = ArrayList<Vod>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(toVod(arr.getJSONObject(i), base, srcName)) }
        }
        return VodPage(out, j.optInt("page", 1), j.optInt("pagecount", 1), j.optInt("total", out.size))
    }

    private fun toVod(o: JSONObject, base: String, srcName: String) = Vod(
        id = o.opt("vod_id")?.toString()?.trim() ?: "",
        name = o.optString("vod_name").trim(),
        pic = fullPic(base, o.optString("vod_pic").trim()),
        remark = o.optString("vod_remarks").trim(),
        year = o.optString("vod_year").trim(),
        area = o.optString("vod_area").trim(),
        typeName = o.optString("type_name").trim(),
        actor = o.optString("vod_actor").trim(),
        director = o.optString("vod_director").trim(),
        content = Mc.stripHtml(o.optString("vod_content")),
        lines = Mc.parseLines(o.optString("vod_play_from"), o.optString("vod_play_url")),
        sourceApi = base,
        sourceName = srcName,
        released = o.optString("vod_pubdate").ifBlank { o.optString("vod_year") },
        updated = o.optString("vod_time"),
        heat = o.optLong("vod_hits_week").takeIf { it > 0 } ?: o.optLong("vod_hits_day").takeIf { it > 0 } ?: o.optLong("vod_hits"),
        totalEpisodes = o.optInt("vod_total").takeIf { it > 0 } ?: if (o.optInt("vod_isend") == 1 || Regex("完结|全集").containsMatchIn(o.optString("vod_remarks"))) o.optInt("vod_serial") else 0,
        completed = o.optInt("vod_isend") == 1
    )

    /** 封面图常是相对路径，补成绝对地址 */
    fun fullPic(api: String, pic: String): String {
        if (pic.isBlank() || pic.startsWith("http")) return pic
        return runCatching {
            val u = URL(api)
            val host = "${u.protocol}://${u.host}"
            if (pic.startsWith("/")) host + pic else "$host/$pic"
        }.getOrDefault(pic)
    }

    /**
     * 把地址里的非 ASCII 字符做百分号编码。
     *
     * 综艺类资源常带中文路径，例如
     *   https://x.com/video/shishangwanjia/20221102期/index.m3u8
     * 原样丢给 HttpURLConnection 可能直接连接失败。
     *
     * 只转义 >127 的字符，ASCII 全部原样保留 —— 这样**已经编码好的 %XX 不会被二次编码**。
     */
    fun encodeUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder(url.length + 16)
        for (ch in url) {
            if (ch.code < 128) {
                sb.append(ch)
            } else {
                runCatching { sb.append(URLEncoder.encode(ch.toString(), "UTF-8")) }
                    .onFailure { sb.append(ch) }
            }
        }
        return sb.toString()
    }
}
