package com.lyricsmachine.core.lyrics

/** A single lyric word: [display] as written, [token] normalised for matching (never empty). */
data class LyricWord(val display: String, val token: String)

data class LyricLine(
    val index: Int,
    val words: List<LyricWord>,
    val isSpacer: Boolean,
    val lrcStartMs: Long?,
)

/** Reference to a word: [wordIndex] is the position within `lines[lineIndex].words`. */
data class TokenRef(val lineIndex: Int, val wordIndex: Int, val token: String)

data class Lyrics(val lines: List<LyricLine>) {
    /** All non-spacer words in order. Computed once. */
    val tokens: List<TokenRef> by lazy {
        buildList {
            for (line in lines) {
                if (line.isSpacer) continue
                line.words.forEachIndexed { wi, w -> add(TokenRef(line.index, wi, w.token)) }
            }
        }
    }

    private val firstTokenByLine: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        tokens.forEachIndexed { i, t -> m.putIfAbsent(t.lineIndex, i) }
        m
    }

    /** Line index of the token at [tokenIndex]; throws [IndexOutOfBoundsException] if out of range. */
    fun lineOfToken(tokenIndex: Int): Int = tokens[tokenIndex].lineIndex

    /** Index into [tokens] of the first token of [lineIndex]; null for spacer / token-less lines. */
    fun firstTokenOfLine(lineIndex: Int): Int? = firstTokenByLine[lineIndex]
}
