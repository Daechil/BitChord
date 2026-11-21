package com.music.bitchord.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecordTest {
    @Test
    fun aKeyIsRedacted() {
        val text = CrashRecord.scrub("401 for key sk-ant-api03-abcdefGHIJ_12345-xyz")!!
        assertFalse(text.contains("sk-ant"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun lyricTextIsWithheld() {
        assertEquals("<withheld>", CrashRecord.scrub("bad line: 君の名は"))
        assertEquals("<withheld>", CrashRecord.scrub("가사 줄"))
    }

    @Test
    fun aPlainMessageIsKeptShort() {
        assertEquals("timeout", CrashRecord.scrub("timeout"))
        assertEquals(160, CrashRecord.scrub("x ".repeat(200))!!.length)
        assertNull(CrashRecord.scrub("  "))
    }

    @Test
    fun aParseErrorKeepsNoMessage() {
        val error = kotlinx.serialization.SerializationException("Unexpected JSON token: {\"lyrics\": \"secret words\"}")
        val text = CrashRecord.describe(error)
        assertTrue(text.startsWith("kotlinx.serialization.SerializationException"))
        assertFalse(text.contains("secret words"))
    }

    @Test
    fun onlyBadEndingsAreReported() {
        assertEquals(CrashRecord.Reason.CRASH, CrashRecord.reasonOf(ApplicationExitInfo.REASON_CRASH, 100))
        assertEquals(CrashRecord.Reason.ANR, CrashRecord.reasonOf(ApplicationExitInfo.REASON_ANR, 100))
        assertNull(CrashRecord.reasonOf(ApplicationExitInfo.REASON_USER_REQUESTED, 100))
        assertNull(CrashRecord.reasonOf(ApplicationExitInfo.REASON_EXIT_SELF, 100))
        // Memory: only while playing in the foreground, not a cached app tidied away.
        assertEquals(
            CrashRecord.Reason.LOW_MEMORY,
            CrashRecord.reasonOf(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
        )
        assertNull(CrashRecord.reasonOf(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
}
