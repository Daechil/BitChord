package com.music.bitchord.data

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    private val xmlDir: File by lazy {
        val root = File(".").canonicalFile
        listOf(File(root, "app/src/main/res/xml"), File(root, "src/main/res/xml")).first { it.exists() }
    }

    private fun excludes(file: String, section: String): Set<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, file))
        val parent = doc.getElementsByTagName(section).item(0) as Element
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
    }

    @Test
    fun everySectionLeavesOutTheSame() {
        val expected = BackupBudget.EXCLUDED.toSet()
        assertEquals(expected, excludes("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(expected, excludes("data_extraction_rules.xml", "device-transfer"))
        assertEquals(expected, excludes("backup_rules.xml", "full-backup-content"))
    }

    @Test
    fun secretsAndHeavyFilesAreLeftOut() {
        val excluded = BackupBudget.EXCLUDED.toSet()
        listOf("bitchord_secrets.xml", "bitchord_secrets_plain.xml", "bitchord_auth.xml").forEach {
            assertTrue(it, ("sharedpref" to it) in excluded)
        }
        listOf("downloads", "beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx", "smart_analysis").forEach {
            assertTrue(it, ("file" to it) in excluded)
        }
    }

    @Test
    fun theEstimateSkipsWhatIsLeftOut() {
        val data = Files.createTempDirectory("data").toFile()
        File(data, "files/downloads").mkdirs()
        File(data, "files/downloads/song.m4a").writeBytes(ByteArray(10_000))
        File(data, "files/lyrics_translation_kept_v1").mkdirs()
        File(data, "files/lyrics_translation_kept_v1/a.json").writeBytes(ByteArray(1000))
        File(data, "cache").mkdirs()
        File(data, "cache/big").writeBytes(ByteArray(50_000))
        File(data, "shared_prefs").mkdirs()
        File(data, "shared_prefs/bitchord_secrets.xml").writeBytes(ByteArray(3000))
        // 1000 bytes rounds up to two 512-byte blocks, plus a header.
        assertEquals(512L + 1024L, BackupBudget.estimate(data))
    }
}
