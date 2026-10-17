package com.songshu.sstv

import com.songshu.sstv.data.VideoOrder
import com.songshu.sstv.data.Vod
import org.junit.Assert.*
import org.junit.Test

class VideoOrderTest {
    @Test fun mixedChineseAndArabicSeasonsAreSortedNumericallyAndNewBatchesResort() {
        val seasons = listOf("第十一季","第2季","第二十季","第一季","第三季","第10季")
        val movies = seasons.mapIndexed { i, season -> Vod(id="$i",name="恶搞之家$season", year="${2025-i}") }
        val sorted = VideoOrder.sort(movies,"恶搞之家")
        assertEquals(listOf(1L,2L,3L,10L,11L,20L),sorted.map { VideoOrder.seasonNumber(it.name) })
        assertEquals(listOf(1L,2L,3L,4L,10L,11L,20L), VideoOrder.sort(movies+Vod(name="恶搞之家第四季"),"恶搞之家").map { VideoOrder.seasonNumber(it.name) })
        assertEquals(sorted.reversed(),VideoOrder.sort(movies,"恶搞之家",true))
    }
    @Test fun nonSeasonResultsUseRelevanceThenOldestReleaseWithUnknownDatesLast() {
        val movies = listOf(Vod(name="新星",year="2024"),Vod(name="星",year="2025"),Vod(name="星球",year="2023"),Vod(name="星际",year="2020"),Vod(name="星辰"))
        assertEquals(listOf("星","星际","星球","星辰","新星"), VideoOrder.sort(movies,"星").map { it.name })
    }
    @Test fun chineseNumeralsAndEpisodeNamesUseNaturalOrder() {
        assertEquals(21,VideoOrder.integer("二十一")); assertEquals(102,VideoOrder.integer("一百零二")); assertEquals(12,VideoOrder.integer("十二"))
        assertEquals(listOf("第2集","第十集","第十一集"),listOf("第十一集","第2集","第十集").sortedBy { VideoOrder.natural(it) })
    }
}
