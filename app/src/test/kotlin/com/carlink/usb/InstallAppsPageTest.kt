package com.carlink.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which "install unknown apps" page is worth an Allow button (the Mac's AAOS 13 emulator, 10 Nov). */
class InstallAppsPageTest {
    @Test
    fun `nothing answering, or only Android Automotive's placeholder, is no page`() {
        assertFalse(InstallAppsPage.isReal(null))
        assertFalse(InstallAppsPage.isReal("com.android.car.frameworkpackagestubs"))
    }

    @Test
    fun `a settings app that answers is a page`() {
        assertTrue(InstallAppsPage.isReal("com.android.car.settings"))
    }
}
