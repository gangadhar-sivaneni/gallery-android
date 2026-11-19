package com.ganga.gallery

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTest {
    // where content point q (layout coords) lands on screen for a layer scaled round centre
    private fun screen(q: Offset, centre: Offset, scale: Float, o: Offset) = centre + o + (q - centre) * scale

    @Test fun pinchKeepsTheFingerPointStill() {
        val centre = Offset(500f, 1000f)
        var scale = 1.5f; var o = Offset(-80f, 40f)
        val finger = Offset(820f, 300f) // off-centre, where the old code drifted
        val q = (finger - centre - o) / scale + centre // content under the finger
        val k = 2f
        o = zoomOffset(finger, centre, k, o); scale *= k
        val after = screen(q, centre, scale, o)
        assertEquals(finger.x, after.x, 0.01f)
        assertEquals(finger.y, after.y, 0.01f)
    }
}
