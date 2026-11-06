package com.ganga.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class CutTest {
    @Test fun splitDeleteRestore() {
        var e = VEdit().split(0.3f).split(0.6f)
        assertEquals(listOf(0f to 0.3f, 0.3f to 0.6f, 0.6f to 1f), e.parts())
        e = e.togglePart(0.45f) // delete middle
        assertEquals(listOf(0f to 0.3f, 0.6f to 1f), e.kept())
        assertEquals(e, e.split(0.3001f)) // too close to an existing split
        val halves = e.split(0.5f) // splitting a deleted part keeps both halves deleted
        assertEquals(listOf(0f to 0.3f, 0.6f to 1f), halves.kept())
        assertEquals(listOf(0f to 0.3f, 0.5f to 0.6f, 0.6f to 1f), halves.togglePart(0.55f).kept())
        // trim clips kept parts
        assertEquals(listOf(0.1f to 0.3f, 0.6f to 0.9f), e.copy(start = 0.1f, end = 0.9f).kept())
    }
}
