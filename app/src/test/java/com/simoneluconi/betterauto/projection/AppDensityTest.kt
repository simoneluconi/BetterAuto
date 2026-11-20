package com.simoneluconi.betterauto.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDensityTest {
    private fun shortSideDp(width: Int, height: Int, launcherDensity: Float): Float =
        minOf(width, height) * 160f / AppDensity.dpi(width, height, launcherDensity)

    @Test
    fun usualCarScreensGiveTheAppAPhoneHeldSideways() {
        // The picture beside the bar on 1920x1080 (density 1.5), 1280x720 (1.0), a wide 1920x720 (1.0), a portrait
        // 1080x1920 (1.5) and a big 2560x1600 (2.0)
        for ((w, h, d) in listOf(Triple(1602, 885, 1.5f), Triple(1068, 590, 1f), Triple(1740, 590, 1f), Triple(988, 1580, 1.5f), Triple(2220, 1200, 2f))) {
            assertEquals("${w}x$h", AppDensity.TARGET_SHORT_DP, shortSideDp(w, h, d), 3f)
        }
    }

    @Test
    fun aSmallPictureKeepsTheTextLegible() {
        // 400 px high at the launcher's 160 dpi: not squeezed to the target, the launcher's density at the least
        assertEquals(160, AppDensity.dpi(700, 400, 1f))
    }

    @Test
    fun aHugePictureDoesNotBlowTheAppUp() {
        // 1400 px high at 160 dpi would be 487 dpi for the target: 1.5 of the launcher's at most
        assertEquals(240, AppDensity.dpi(2400, 1400, 1f))
    }

    @Test
    fun theDriversChoiceGoesOnTop() {
        val auto = AppDensity.dpi(1602, 885, 1.5f)
        assertEquals(auto * 1.15f, AppDensity.dpi(1602, 885, 1.5f, scale = 1.15f).toFloat(), 1f)
    }
}
