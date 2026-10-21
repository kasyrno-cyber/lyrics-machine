package com.lyricsmachine.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LangTest {
    @Test fun `mixed has no whisper code`() = assertNull(Lang.MIXED.whisperCode)
    @Test fun `malay maps to ms`() = assertEquals("ms", Lang.MS.whisperCode)
}
