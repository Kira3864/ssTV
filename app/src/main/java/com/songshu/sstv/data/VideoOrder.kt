package com.songshu.sstv.data

object VideoOrder {
    private val number = Regex("[0-9零〇一二两三四五六七八九十百千]+")
    private val season = Regex("第?\\s*([0-9零〇一二两三四五六七八九十百千]+)\\s*季|(?i)(?:season\\s*|\\bs)([0-9]+)")
    fun integer(text: String): Long {
        text.toLongOrNull()?.let { return it }
        val digits = "零一二三四五六七八九"
        var total = 0L; var digit = 0L
        if (text.none { it in "十百千" }) return text.map { if (it == '〇') '0' else if (it == '两') '2' else digits.indexOf(it).coerceAtLeast(0).digitToChar() }.joinToString("").toLongOrNull() ?: 0
        text.forEach { ch ->
            val unit = when (ch) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> 0 }
            if (unit > 0) { total += (if (digit == 0L) 1 else digit) * unit; digit = 0 } else digit = if (ch == '两') 2 else digits.indexOf(ch).coerceAtLeast(0).toLong()
        }
        return total + digit
    }
    fun natural(text: String) = number.replace(text.lowercase()) { integer(it.value).toString().padStart(12, '0') }
    fun base(text: String) = season.replace(text, "").replace(Regex("[\\s\\p{P}]+"), "").lowercase()
    fun seasonNumber(text: String): Long? = season.find(text)?.let { integer(it.groupValues[1].ifBlank { it.groupValues[2] }) }
    private fun relevance(title: String, query: String): Int = when {
        query.isBlank() || title == query -> 0
        title.startsWith(query) -> 1
        title.contains(query) -> 2
        else -> 100 + query.count { it !in title } * 10 + kotlin.math.abs(title.length - query.length)
    }
    private fun release(v: Vod): Long {
        val value = Regex("[0-9]+").findAll(v.released.ifBlank { v.year }).map { it.value }.joinToString("")
        return if (value.isBlank()) Long.MAX_VALUE else value.padEnd(14, '0').take(14).toLongOrNull() ?: Long.MAX_VALUE
    }
    fun sort(items: List<Vod>, query: String, descending: Boolean = false): List<Vod> {
        val q = base(query)
        val groups = items.groupBy { base(it.name) }.entries.sortedWith(compareBy<Map.Entry<String,List<Vod>>> { relevance(it.key, q) }
            .thenBy { it.value.minOfOrNull { v -> release(v) } ?: Long.MAX_VALUE }.thenBy { natural(it.key) })
        val result = groups.flatMap { (_, values) ->
            if (values.any { seasonNumber(it.name) != null }) values.sortedWith(compareBy<Vod> { seasonNumber(it.name) ?: 0 }.thenBy { release(it) }.thenBy { natural(it.name) }.thenBy { it.id })
            else values.sortedWith(compareBy<Vod> { release(it) }.thenBy { natural(it.name) }.thenBy { it.id })
        }
        return if (descending) result.reversed() else result
    }
}
