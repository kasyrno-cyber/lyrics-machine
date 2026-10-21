package com.lyricsmachine.core.lyrics

/** A group of lyric lines shown together. [lineIndices] are the non-spacer [LyricLine.index] values, in order. */
data class Verse(val index: Int, val label: String?, val lineIndices: List<Int>)

object Verses {
    /** Group size used when a song has no spacer lines at all. */
    const val FALLBACK_LINES = 4

    /**
     * Splits [lyrics] into verses. Any spacer line ends the current verse; a labelled spacer becomes the label
     * of the verse that follows. A song with no spacers and more than [FALLBACK_LINES] lyric lines is split into
     * consecutive groups of [FALLBACK_LINES].
     */
    fun of(lyrics: Lyrics): List<Verse> {
        val lines = lyrics.lines
        val lyricIdx = lines.filter { !it.isSpacer }.map { it.index }
        if (lyricIdx.isEmpty()) return emptyList()
        if (lines.none { it.isSpacer }) {
            if (lyricIdx.size <= FALLBACK_LINES) return listOf(Verse(0, null, lyricIdx))
            return lyricIdx.chunked(FALLBACK_LINES).mapIndexed { i, g -> Verse(i, null, g) }
        }
        val out = ArrayList<Verse>()
        var current = ArrayList<Int>()
        var pendingLabel: String? = null
        fun flush() {
            if (current.isNotEmpty()) {
                out += Verse(out.size, pendingLabel, current)
                current = ArrayList()
                pendingLabel = null
            }
        }
        for (line in lines) {
            if (line.isSpacer) {
                flush()
                if (line.label != null) pendingLabel = line.label
            } else {
                current += line.index
            }
        }
        flush()
        return out
    }

    /**
     * Index of the verse containing lyric line [lineIndex]; for a spacer line, the next verse. Null when the
     * line is out of range or no verse follows a spacer.
     */
    fun verseOfLine(verses: List<Verse>, lineIndex: Int): Int? {
        if (lineIndex < 0) return null
        verses.firstOrNull { lineIndex in it.lineIndices }?.let { return it.index }
        return verses.firstOrNull { it.lineIndices.first() > lineIndex }?.index
    }
}
