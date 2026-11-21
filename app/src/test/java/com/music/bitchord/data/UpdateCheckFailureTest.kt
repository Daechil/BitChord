package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckFailureTest {
    @Test
    fun rateLimitIsToldApart() {
        assertEquals(AppUpdateChecker.FailReason.RATE_LIMITED, AppUpdateChecker.failureFor(403, "0"))
        assertEquals(AppUpdateChecker.FailReason.RATE_LIMITED, AppUpdateChecker.failureFor(429, null))
        assertEquals(AppUpdateChecker.FailReason.HTTP, AppUpdateChecker.failureFor(403, "12"))
    }

    @Test
    fun missingReleaseAndOtherErrors() {
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASE, AppUpdateChecker.failureFor(404, null))
        assertEquals(AppUpdateChecker.FailReason.HTTP, AppUpdateChecker.failureFor(502, null))
    }
}
