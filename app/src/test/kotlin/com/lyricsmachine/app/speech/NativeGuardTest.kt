package com.lyricsmachine.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeGuardTest {
    private fun expectUnavailable(error: Throwable) {
        try {
            loadOrThrowIllegalState<Unit> { throw error }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("speech engine unavailable", e.message)
            assertSame(error, e.cause)
        }
    }

    @Test fun `missing native library becomes an illegal state exception`() =
        expectUnavailable(UnsatisfiedLinkError("no lyricsjni"))

    @Test fun `failed static initialiser becomes an illegal state exception`() =
        expectUnavailable(ExceptionInInitializerError(RuntimeException("init")))

    @Test fun `class that cannot be defined becomes an illegal state exception`() =
        expectUnavailable(NoClassDefFoundError("WhisperNative"))

    @Test fun `ordinary exceptions pass through unchanged`() {
        val original = IllegalArgumentException("bad input")
        try {
            loadOrThrowIllegalState<Unit> { throw original }
            fail("expected the original exception")
        } catch (e: IllegalArgumentException) {
            assertSame(original, e)
        }
    }

    @Test fun `result is returned when nothing fails`() {
        assertTrue(loadOrThrowIllegalState { 42 } == 42)
    }
}
