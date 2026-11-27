package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KO: the Korean strings speak one way — "~해요", as the KO screens always
 * have. Upstream's Korean came in "~합니다", and the two side by side read as
 * two apps; an upstream merge that brings a "~합니다" string back fails here,
 * naming it, rather than slipping into the app.
 */
class KoreanToneTest {

    private val files = listOf(
        "src/main/res/values-ko/strings.xml",
        "src/main/res/values-ko/strings_ko.xml",
        "../sharedUi/src/commonMain/composeResources/values-ko/strings.xml",
    )

    /** A sentence ending in the formal style: "합니다", "있습니까?", "하십시오". */
    private val formal = Regex("[가-힣](?:니다|습니까|십시오)(?=[.?!\\s<]|$)")

    @Test
    fun `korean strings use the 해요 style`() {
        val offenders = files.flatMap { path ->
            val text = File(path).readText()
            Regex("""<(?:string|item)[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .map { it.groupValues[1] }
                .filter { formal.containsMatchIn(it) }
                .toList()
        }
        assertTrue("formal Korean strings: ${offenders.take(10)}", offenders.isEmpty())
    }
}
