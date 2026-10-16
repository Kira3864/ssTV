package com.laoyang.jingying

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtSize(b: Long): String {
    if (b <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < u.size - 1) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "${b} B" else String.format(Locale.CHINA, "%.1f %s", v, u[i])
}

fun fmtTime(t: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(t))

fun fmtDur(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, ss)
    else String.format(Locale.CHINA, "%02d:%02d", m, ss)
}
