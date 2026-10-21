package com.lyricsmachine.core.playback

import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.lyrics.Lyrics

/** Line timings derived from LRC `[mm:ss.xx]` tags, for songs that have no analysis. */
object LrcTimings {
    const val DEFAULT_LAST_LINE_MS = 4000L

    fun fromLyrics(lyrics: Lyrics, endMs: Long? = null): List<LineTiming> {
        val timed = lyrics.lines.filter { !it.isSpacer && it.lrcStartMs != null }
        return timed.mapIndexed { i, line ->
            val start = line.lrcStartMs!!
            val end = timed.getOrNull(i + 1)?.lrcStartMs ?: endMs ?: (start + DEFAULT_LAST_LINE_MS)
            LineTiming(line.index, start, maxOf(end, start))
        }
    }
}
