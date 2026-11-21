package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreGuardTest {
    @Test
    fun noTokensIsAFirstRun() =
        assertEquals(RestoreGuard.Outcome.FIRST_RUN, RestoreGuard.outcome(null, null))

    @Test
    fun matchingTokensAreTheSameInstall() =
        assertEquals(RestoreGuard.Outcome.SAME_INSTALL, RestoreGuard.outcome("a", "a"))

    @Test
    fun aTokenOnlyInTheSettingsCameFromABackup() =
        assertEquals(RestoreGuard.Outcome.RESTORED, RestoreGuard.outcome("a", null))

    @Test
    fun differentTokensCameFromABackup() =
        assertEquals(RestoreGuard.Outcome.RESTORED, RestoreGuard.outcome("a", "b"))

    @Test
    fun aTokenOnlyOutsideTheSettingsIsAFirstRun() =
        assertEquals(RestoreGuard.Outcome.FIRST_RUN, RestoreGuard.outcome(null, "b"))
}
