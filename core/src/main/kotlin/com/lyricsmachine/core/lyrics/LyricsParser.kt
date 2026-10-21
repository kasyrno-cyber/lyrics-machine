package com.lyricsmachine.core.lyrics

import java.text.Normalizer
import java.util.Locale

object LyricsParser {
    private val METADATA = Regex("""^\s*\[(?:ar|ti|al|by|offset|length)\s*:[^\]]*]\s*$""", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("""^\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
    /** Enhanced-LRC per-word tags, e.g. `<00:12.50>`, anywhere in a line. */
    private val WORD_TAG = Regex("""<\d+:\d{2}(?:[.:]\d{1,3})?>""")
    private val SPACER = Regex("""^\s*[\[(][^\])]*[\])]\s*$""")
    private val WHITESPACE = Regex("""\s+""")
    private val LINE_BREAK = Regex("""\r\n|\r|\n""")

    fun parse(raw: String): Lyrics {
        val lines = ArrayList<LyricLine>()
        // Trailing blank lines (e.g. from a final newline) carry no meaning; interior blanks stay as spacers.
        val rows = raw.split(LINE_BREAK).dropLastWhile { it.isBlank() }
        for (rawLine in rows) {
            if (METADATA.matches(rawLine)) continue
            // All leading line timestamps are stripped; only the first sets the start.
            var rest = rawLine.trim()
            var startMs: Long? = null
            while (true) {
                val ts = TIMESTAMP.find(rest) ?: break
                if (startMs == null) startMs = toMs(ts)
                rest = rest.substring(ts.value.length).trimStart()
            }
            val text = rest.replace(WORD_TAG, "").trim()
            val spacer = text.isBlank() || SPACER.matches(text)
            val words = if (spacer) emptyList() else toWords(text)
            val isSpacer = spacer || words.isEmpty()
            val label = if (isSpacer && text.isNotBlank()) text else null
            lines += LyricLine(lines.size, words, isSpacer, startMs, label)
        }
        return Lyrics(lines)
    }

    fun normalize(word: String): String {
        val nfkc = Normalizer.normalize(word, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp) // hyphens are not letters/digits, so dropped
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun toWords(text: String): List<LyricWord> =
        text.split(WHITESPACE).mapNotNull { w ->
            if (w.isEmpty()) return@mapNotNull null
            val token = normalize(w)
            if (token.isEmpty()) null else LyricWord(w, token)
        }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return (min * 60 + sec) * 1000 + fracMs
    }
}
