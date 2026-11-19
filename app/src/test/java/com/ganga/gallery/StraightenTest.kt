package com.ganga.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightenTest {
    @Test fun coverScale() {
        assertEquals(1f, coverScale(400f, 300f, 0f), 1e-4f) // no turn, no zoom
        // a turned photo must still cover its frame: both edges need the zoom, the worse one wins
        val k = coverScale(400f, 300f, 10f)
        assertTrue(k * 400f >= 400f * 0.9848f + 300f * 0.1736f - 0.5f)
        assertEquals(coverScale(400f, 300f, 10f), coverScale(400f, 300f, -10f), 1e-6f) // same both ways
    }
}
