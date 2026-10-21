package com.lyricsmachine.app.speech

import com.lyricsmachine.core.speech.HeardWord

/** Parses the native `"t0Ms|t1Ms|p|text"` word records; kept free of JNI so it is unit-testable. */
internal object WhisperWords {
    fun parse(records: Array<String>): List<HeardWord> = records.mapNotNull(::parseOne)

    fun parseOne(record: String): HeardWord? {
        val parts = record.split('|', limit = 4)
        if (parts.size != 4) return null
        val t0 = parts[0].toLongOrNull() ?: return null
        val t1 = parts[1].toLongOrNull() ?: return null
        val p = parts[2].toFloatOrNull() ?: return null
        val text = parts[3].trim()
        if (isSpecial(text)) return null
        return HeardWord(text, t0, maxOf(t0, t1), p.coerceIn(0f, 1f))
    }

    fun isSpecial(text: String): Boolean {
        if (text.isEmpty()) return true
        val f = text.first()
        val l = text.last()
        return (f == '[' && l == ']') || (f == '(' && l == ')') || (f == '<' && l == '>') ||
            (f == '*' && l == '*' && text.length > 1)
    }
}
