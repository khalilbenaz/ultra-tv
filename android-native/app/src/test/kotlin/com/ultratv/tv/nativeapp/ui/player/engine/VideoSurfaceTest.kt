package com.ultratv.tv.nativeapp.ui.player.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #7 : son sans image sur NVIDIA Shield → TextureView par défaut sur NVIDIA, réglable partout. */
class VideoSurfaceTest {
    @Test fun auto_textureSurNvidiaSeulement() {
        assertTrue(useTextureView("auto", "NVIDIA"))
        assertTrue(useTextureView("auto", "nvidia"))
        assertFalse(useTextureView("auto", "Xiaomi"))
    }
    @Test fun choixManuel_prioritaire() {
        assertFalse(useTextureView("surface", "NVIDIA"))
        assertTrue(useTextureView("texture", "Google"))
    }
}
