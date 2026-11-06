package com.ganga.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRuleTest {
    @Test fun editsLandInAllowedFolders() {
        assertEquals("DCIM/Camera/", writableDir(false, "DCIM/Camera/"))
        assertEquals("Pictures/Edited/", writableDir(false, "Download/"))
        assertEquals("Pictures/Edited/", writableDir(false, "Android/media/com.whatsapp/x/"))
        assertEquals("Movies/Clips/", writableDir(true, "Movies/Clips/"))
        assertEquals("Pictures/Edited/", writableDir(false, "Movies/Clips/")) // images can't live in Movies/
    }
}
