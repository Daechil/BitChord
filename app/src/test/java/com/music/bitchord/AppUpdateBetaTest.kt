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

    @Test
    fun `a stable release's notes stand alone, the betas before it left out`() {
        val releases = Json.parseToJsonElement(
            """[
              {"tag_name":"v1.10-ko.91","draft":false,"body":"stable summary"},
              {"tag_name":"v1.10-ko.90-beta","draft":false,"body":"beta 90"},
              {"tag_name":"v1.10-ko.89-beta","draft":false,"body":"beta 89"}
            ]"""
        ) as JsonArray
        // From an older beta straight to the stable: its summary, not the betas again.
        assertEquals("stable summary", AppUpdateChecker.notesBetween(releases, "1.10-ko.85-beta", "1.10-ko.91", includeBeta = true))
        // Between betas, every skipped one still shows.
        val betas = AppUpdateChecker.notesBetween(releases, "1.10-ko.88-beta", "1.10-ko.90-beta", includeBeta = true).orEmpty()
        assertTrue("beta 89" in betas && "beta 90" in betas)
    }
}
