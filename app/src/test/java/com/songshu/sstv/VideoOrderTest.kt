package com.songshu.sstv

import com.songshu.sstv.data.VideoOrder
import com.songshu.sstv.data.Vod
import org.junit.Assert.*
import org.junit.Test

class VideoOrderTest {
    @Test fun unnumberedTitleAndNumberedSeasonsUseTheSameNumericComparisonType() {
        val items = listOf(Vod(name="绝命毒师第5季"),Vod(name="绝命毒师"),Vod(name="绝命毒师第一季"),Vod(name="绝命毒师第2季"))
        val expected = listOf("绝命毒师","绝命毒师第一季","绝命毒师第2季","绝命毒师第5季")
        assertEquals(expected,VideoOrder.sort(items,"绝命毒师").map { it.name })
        assertEquals(expected.reversed(),VideoOrder.sort(items,"绝命毒师",true).map { it.name })
        for(size in 1..items.size) assertEquals(size,VideoOrder.sort(items.take(size),"绝命毒师").size)
    }
    @Test fun unnumberedSameNameVersionsDoNotBreakChronologicalOrderAcrossOtherTitles() {
        val movies = listOf(Vod(id="new", name="星际",year="2025"),Vod(id="old",name="星际",year="2020"),
            Vod(id="middle",name="星球",year="2023"),Vod(id="exact",name="星",year="2026"))
        assertEquals(listOf("exact","old","middle","new"),VideoOrder.sort(movies,"星").map { it.id })
    }
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
