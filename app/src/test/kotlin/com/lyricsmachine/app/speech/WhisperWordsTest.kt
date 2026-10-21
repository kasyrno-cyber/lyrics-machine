package com.lyricsmachine.app.speech

import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.speech.HeardWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** JVM-only checks; the native whisper.cpp path itself cannot be unit-tested here (verified on device, Task 16). */
class WhisperWordsTest {
    @Test fun `parses word records`() {
        val words = WhisperWords.parse(arrayOf("120|480|0.91|hello", "480|900|0.5| world", "900|950|0.4|a|b"))
        assertEquals(
            listOf(HeardWord("hello", 120, 480, 0.91f), HeardWord("world", 480, 900, 0.5f), HeardWord("a|b", 900, 950, 0.4f)),
            words,
        )
    }

    @Test fun `skips special and bracketed tokens`() {
        val words = WhisperWords.parse(arrayOf("0|10|0.9|[_BEG_]", "0|10|0.9|[Music]", "0|10|0.9|(music)", "0|10|0.9| ", "10|20|0.8|sayang"))
        assertEquals(listOf(HeardWord("sayang", 10, 20, 0.8f)), words)
    }

    @Test fun `malformed records are dropped`() {
        assertNull(WhisperWords.parseOne("garbage"))
        assertNull(WhisperWords.parseOne("x|1|0.5|word"))
    }

    @Test fun `language codes map to whisper codes`() {
        assertEquals("ms", Lang.MS.whisperCode)
        assertEquals("id", Lang.ID.whisperCode)
        assertNull(Lang.MIXED.whisperCode)
    }

    @Test fun `close is idempotent and transcribe after close throws`() {
        val engine = WhisperCppEngine(File("missing.bin"), threads = 1)
        engine.close()
        engine.close()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.transcribe(FloatArray(16), Lang.EN) }
        }
    }
}
