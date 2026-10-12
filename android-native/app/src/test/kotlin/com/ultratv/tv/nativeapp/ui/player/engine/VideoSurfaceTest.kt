package com.ultratv.tv.nativeapp.ui.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Son sans image (NVIDIA Shield #7, Samsung A30s) : TextureView par défaut sur NVIDIA et smartphone, réglable partout. */
class VideoSurfaceTest {
    @Test fun auto_textureSurNvidiaEtSmartphone() {
        assertTrue(useTextureView("auto", touch = false, manufacturer = "NVIDIA"))
        assertTrue(useTextureView("auto", touch = true, manufacturer = "samsung"))
        assertFalse(useTextureView("auto", touch = false, manufacturer = "Xiaomi"))
    }
    @Test fun choixManuel_prioritaire() {
        assertFalse(useTextureView("surface", touch = true, manufacturer = "NVIDIA"))
        assertTrue(useTextureView("texture", touch = false, manufacturer = "Google"))
    }
    @Test fun imageNoire_detectee() {
        assertTrue(FrameProbe.isBlack(IntArray(32 * 18) { 0xFF000000.toInt() }))
        assertTrue(FrameProbe.isBlack(IntArray(10) { 0xFF0A0A0A.toInt() }))   // noir « vidéo » (16) sous le seuil
        assertFalse(FrameProbe.isBlack(IntArray(10) { if (it == 3) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
        assertEquals(255, FrameProbe.maxLuma(intArrayOf(0xFFFFFFFF.toInt())))
    }
}
