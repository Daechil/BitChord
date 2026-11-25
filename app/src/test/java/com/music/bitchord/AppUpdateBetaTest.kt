package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateBetaTest {
    @Test
    fun `a later stable build passes an earlier beta, and a later beta passes a stable one`() {
        assertTrue(AppUpdateChecker.newer("1.9.1-ko.72", "1.9.1-ko.70-beta"))
        assertTrue(AppUpdateChecker.newer("1.9.1-ko.70-beta", "1.9.1-ko.68"))
        assertFalse(AppUpdateChecker.newer("1.9.1-ko.68", "1.9.1-ko.70-beta"))
        assertFalse(AppUpdateChecker.newer("1.9.1-ko.70-beta", "1.9.1-ko.70-beta"))
        // A new upstream version passes every build of the last one.
        assertTrue(AppUpdateChecker.newer("1.9.2-ko.80", "1.9.1-ko.99-beta"))
    }

    @Test
    fun `the newest of the recent releases is picked, drafts left out`() {
        val releases = Json.parseToJsonElement(
            """[
              {"tag_name":"v1.9.1-ko.71-beta","draft":true},
              {"tag_name":"v1.9.1-ko.70-beta","prerelease":true,"draft":false},
              {"tag_name":"v1.9.1-ko.69","prerelease":false,"draft":false},
              {"tag_name":"v1.9.1-ko.68","prerelease":false,"draft":false},
              {"name":"no tag"}
            ]""",
        ) as JsonArray
        assertEquals("v1.9.1-ko.70-beta", AppUpdateChecker.newestOf(releases)?.get("tag_name")?.jsonPrimitive?.content)
        assertEquals(null, AppUpdateChecker.newestOf(JsonArray(emptyList())))
    }
}
