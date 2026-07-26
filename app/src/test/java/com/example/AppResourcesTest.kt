package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppResourcesTest {

    @Test
    fun `app name is KU Volleyball`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("KU Volleyball", context.getString(R.string.app_name))
    }

    /**
     * The volleyball icon's seams are elliptical-arc ("A") path commands, which
     * Android's PathParser must accept — a malformed or unsupported pathData
     * throws at inflation rather than merely drawing wrong, so inflating each
     * layer here catches it in CI instead of on a phone.
     */
    @Test
    fun `launcher icon layers inflate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layers = listOf(
            R.drawable.ic_launcher_foreground,
            R.drawable.ic_launcher_background,
            R.drawable.ic_launcher_monochrome
        )
        for (id in layers) {
            val drawable = context.getDrawable(id)
            assertNotNull("drawable $id failed to inflate", drawable)
            assertTrue("drawable $id has no intrinsic size", drawable!!.intrinsicWidth > 0)
        }
        // Legacy raster icons for API 24-25, which predate adaptive icons.
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher))
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
    }
}
