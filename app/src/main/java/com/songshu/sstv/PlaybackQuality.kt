package com.songshu.sstv

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackQuality {
    data class Choice(val label: String,val height: Int,val bitrate: Int,val override: TrackSelectionOverride)
    fun choices(tracks: Tracks): List<Choice> {
        val all=tracks.groups.filter { it.type==C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val f=group.getTrackFormat(index)
                val height=if(f.height>0 && f.width>0) minOf(f.height,f.width) else f.height
                Choice(if(height>0) "${height}p" else "原画",height,f.bitrate,TrackSelectionOverride(group.mediaTrackGroup,index))
            }
        }
        return all.groupBy { it.height }.values.map { it.maxBy { c -> c.bitrate } }.sortedByDescending { it.height }
    }
}
