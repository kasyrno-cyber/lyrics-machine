package com.lyricsmachine.core.speech

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PerformanceGuardTest {
    @Test fun `performance guard trips on median over budget`() {
        val g = PerformanceGuard(2000)
        g.record(2500); g.record(2600); g.record(1000)
        assertTrue(g.overBudget)
    }

    @Test fun `not over budget until window filled`() {
        val g = PerformanceGuard(2000)
        g.record(9000); g.record(9000)
        assertFalse(g.overBudget)
    }

    @Test fun `single outlier does not trip`() {
        val g = PerformanceGuard(2000)
        g.record(500); g.record(9000); g.record(600)
        assertFalse(g.overBudget)
    }

    @Test fun `only last window samples count and even window uses mean`() {
        val g = PerformanceGuard(2000, window = 3)
        g.record(9000); g.record(9000); g.record(100); g.record(100); g.record(100)
        assertFalse(g.overBudget)
        val e = PerformanceGuard(2000, window = 2)
        e.record(1000); e.record(3500) // mean 2250
        assertTrue(e.overBudget)
        e.record(1000) // last two 3500,1000 -> 2250
        assertTrue(e.overBudget)
        e.record(1000) // 1000,1000
        assertFalse(e.overBudget)
    }
}
