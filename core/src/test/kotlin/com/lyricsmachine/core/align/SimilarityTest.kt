package com.lyricsmachine.core.align

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimilarityTest {
    @Test fun `equal is 1`() = assertEquals(1.0, Similarity.score("sayang", "sayang"))
    @Test fun `one typo passes threshold`() = assertTrue(Similarity.score("lantern", "lanturn") >= 0.75)
    @Test fun `different words fail`() = assertTrue(Similarity.score("river", "bukit") < 0.75)
    @Test fun `empty scores 0`() {
        assertEquals(0.0, Similarity.score("", "abc"))
        assertEquals(0.0, Similarity.score("abc", ""))
        assertEquals(0.0, Similarity.score("", ""))
    }
}
