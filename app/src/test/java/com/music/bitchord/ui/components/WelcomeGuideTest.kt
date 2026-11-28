package com.music.bitchord.ui.components

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WelcomeGuideTest {
    @Test
    fun `a new install gets the guide, someone updating does not`() {
        // Not through onboarding yet: new, whatever the install time says.
        assertEquals(WelcomeGuide.PENDING, WelcomeGuide.firstState(onboarded = false, freshInstall = false))
        assertEquals(WelcomeGuide.PENDING, WelcomeGuide.firstState(onboarded = false, freshInstall = true))
        assertEquals(WelcomeGuide.PENDING, WelcomeGuide.firstState(onboarded = true, freshInstall = true))
        // Onboarded and updated: has been using the app.
        assertEquals(WelcomeGuide.DONE, WelcomeGuide.firstState(onboarded = true, freshInstall = false))
    }

    @Test
    fun `it waits for onboarding and is shown once`() {
        assertFalse(WelcomeGuide.shouldShow(WelcomeGuide.PENDING, onboarded = false))
        assertTrue(WelcomeGuide.shouldShow(WelcomeGuide.PENDING, onboarded = true))
        assertFalse(WelcomeGuide.shouldShow(WelcomeGuide.DONE, onboarded = true))
        assertFalse(WelcomeGuide.shouldShow(WelcomeGuide.UNDECIDED, onboarded = true))
    }

    /**
     * Every place the guide sends someone is written with the labels the screens show, in
     * both languages: a renamed row fails here instead of leaving the guide pointing nowhere.
     */
    @Test
    fun `the guide's paths name the labels the screens show`() {
        val paths = mapOf(
            "guide_where_lyrics_sources" to listOf("settings", "appearance", "lyrics_sources"),
            "guide_where_ko_claude" to listOf("settings", "ko_features", "ko_features_claude"),
            "guide_where_ahead_menu" to listOf("translate_ahead"),
            "guide_where_ko_cost" to listOf("settings", "ko_features", "ko_group_cost"),
            "guide_where_ko_playback" to listOf("settings", "ko_features", "ko_features_playback"),
            "guide_where_ko_queue" to listOf("settings", "ko_features", "ko_group_queue"),
            "guide_where_spotify" to listOf("settings", "account_integrations", "spotify"),
            "guide_where_party" to listOf("settings", "listen_together"),
            "guide_where_ko" to listOf("settings", "ko_features"),
            "guide_where_whats_new" to listOf("settings", "miscellaneous", "whats_new_row"),
            "guide_where_replay" to listOf("settings", "miscellaneous", "guide_replay_row"),
            "guide_skipped_notice" to listOf("settings", "miscellaneous", "guide_replay_row"),
        )
        for (locale in listOf("values", "values-ko")) {
            val strings = strings(locale)
            for ((path, labels) in paths) {
                val text = strings.getValue(path)
                for (label in labels) {
                    val shown = strings.getValue(label)
                    assertTrue("$locale/$path \"$text\" should name \"$shown\" ($label)", text.contains(shown))
                }
            }
        }
    }

    @Test
    fun `every page and row has its words in both languages`() {
        for (locale in listOf("values", "values-ko")) {
            val strings = strings(locale)
            assertTrue(locale, strings.keys.count { it.startsWith("guide_") } >= 50)
        }
        assertEquals(7, WelcomeGuide.pages.size)
        assertTrue(WelcomeGuide.pages.drop(1).all { it.rows.isNotEmpty() })
    }

    private fun strings(locale: String): Map<String, String> {
        val root = File(".").canonicalFile
        val res = if (File(root, "app/src/main/res").exists()) File(root, "app/src/main/res") else File(root, "src/main/res")
        val out = HashMap<String, String>()
        File(res, locale).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }
}
