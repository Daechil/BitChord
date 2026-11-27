package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KO: the strings this fork adds live in strings_ko.xml, apart from upstream's
 * strings.xml (see tools/ko_strings.py). These keep the split sound.
 */
class KoStringsTest {

    private val res = File("src/main/res")
    private val name = Regex("""<(?:string|plurals|string-array)\s+name="([^"]+)"""")

    private fun names(path: String): Set<String> = name.findAll(File(res, path).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `no string is defined in both files`() {
        for (dir in listOf("values", "values-ko")) {
            val both = names("$dir/strings.xml") intersect names("$dir/strings_ko.xml")
            // Android would refuse to build ("Duplicate resources"); this names them. Usually an
            // upstream merge that brought a string of the same name: rename the KO one.
            assertTrue("$dir: defined in both strings.xml and strings_ko.xml: $both", both.isEmpty())
        }
    }

    @Test
    fun `every KO string has its Korean and its English`() {
        val english = names("values/strings_ko.xml")
        val korean = names("values-ko/strings_ko.xml")
        // Without Korean the Korean screen shows English; without English the build fails elsewhere.
        assertTrue("no Korean for: ${english - korean}", (english - korean).isEmpty())
        assertTrue("no English for: ${korean - english}", (korean - english).isEmpty())
    }
}
