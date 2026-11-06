package com.ganga.gallery

import androidx.exifinterface.media.ExifInterface as X
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotateTest {
    @Test fun clockwiseCycle() {
        assertEquals(X.ORIENTATION_ROTATE_90, nextClockwise(X.ORIENTATION_UNDEFINED)) // the bug: unset must still turn
        assertEquals(X.ORIENTATION_ROTATE_90, nextClockwise(X.ORIENTATION_NORMAL))
        assertEquals(X.ORIENTATION_ROTATE_180, nextClockwise(X.ORIENTATION_ROTATE_90))
        assertEquals(X.ORIENTATION_ROTATE_270, nextClockwise(X.ORIENTATION_ROTATE_180))
        assertEquals(X.ORIENTATION_NORMAL, nextClockwise(X.ORIENTATION_ROTATE_270))
        assertNull(nextClockwise(X.ORIENTATION_FLIP_HORIZONTAL))
    }
}
