package com.convoscribe.enginecheck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [NpuAvailability] over real (Robolectric) SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class NpuAvailabilityTest {

    private lateinit var availability: NpuAvailability

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("npu-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        availability = NpuAvailability(prefs)
    }

    @Test
    fun freshDevice_isNeitherVerifiedNorBlocked() {
        assertFalse(availability.isVerified())
        assertNull(availability.blockedReason())
    }

    @Test
    fun verifiedThenFailed_endsBlockedAndUnverified() {
        availability.markVerified()
        assertTrue(availability.isVerified())

        availability.markFailed("exit 255")
        assertFalse(availability.isVerified())
        assertEquals("exit 255", availability.blockedReason())

        availability.markVerified()
        assertTrue(availability.isVerified())
        assertNull(availability.blockedReason())
    }
}
