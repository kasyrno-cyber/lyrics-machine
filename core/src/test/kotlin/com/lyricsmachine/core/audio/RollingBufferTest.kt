package com.lyricsmachine.core.audio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class RollingBufferTest {
    @Test fun `rolling buffer keeps only latest samples`() {
        val b = RollingBuffer(4)
        b.append(floatArrayOf(1f, 2f, 3f))
        b.append(floatArrayOf(4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), b.latest())
    }

    @Test fun `returns fewer than capacity before full`() {
        val b = RollingBuffer(5)
        assertEquals(0, b.latest().size)
        b.append(floatArrayOf(1f, 2f))
        assertArrayEquals(floatArrayOf(1f, 2f), b.latest())
    }

    @Test fun `oversized chunk keeps tail`() {
        val b = RollingBuffer(3)
        b.append(floatArrayOf(9f))
        b.append(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), b.latest())
    }

    @Test fun `latest is a copy and wraparound is ordered`() {
        val b = RollingBuffer(3)
        repeat(7) { b.append(floatArrayOf(it.toFloat())) }
        val l = b.latest()
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), l)
        l[0] = 99f
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), b.latest())
    }

    @Test fun `capacity must be positive`() {
        assertThrows<IllegalArgumentException> { RollingBuffer(0) }
    }
}
