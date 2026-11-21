package com.music.bitchord.playback.smart

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUnpackTest {
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private fun dir(): File = Files.createTempDirectory("models").toFile()

    @Test
    fun unpacksAModelThatIsNotThere() {
        val dir = dir()
        val file = ModelUnpack.ensure(dir, "m.onnx", { ByteArrayInputStream(bytes) }, expected = null, notOlderThan = 0L)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(dir, "m.onnx.tmp").exists())
    }

    @Test
    fun replacesATruncatedCopy() {
        val dir = dir()
        File(dir, "m.onnx").writeBytes(bytes.copyOf(100))
        val file = ModelUnpack.ensure(dir, "m.onnx", { ByteArrayInputStream(bytes) }, expected = bytes.size.toLong(), notOlderThan = 0L)
        assertEquals(bytes.size.toLong(), file.length())
    }

    @Test
    fun replacesACopyFromBeforeTheUpdate() {
        val dir = dir()
        val old = File(dir, "m.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(1_000L) }
        ModelUnpack.ensure(dir, "m.onnx", { ByteArrayInputStream(bytes) }, expected = null, notOlderThan = 5_000L)
        assertArrayEquals(bytes, old.readBytes())
    }

    @Test
    fun keepsACurrentCopy() {
        val dir = dir()
        File(dir, "m.onnx").writeBytes(bytes)
        var opened = false
        ModelUnpack.ensure(dir, "m.onnx", { opened = true; ByteArrayInputStream(bytes) }, expected = null, notOlderThan = 0L)
        assertFalse(opened)
    }

    @Test
    fun aCopyCutShortLeavesNothingBehind() {
        val dir = dir()
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 1000) 7 else throw IOException("killed")
        }
        val thrown = runCatching {
            ModelUnpack.ensure(dir, "m.onnx", { failing }, expected = null, notOlderThan = 0L)
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertFalse(File(dir, "m.onnx").exists())
        assertFalse(File(dir, "m.onnx.tmp").exists())
    }

    @Test
    fun aWrongSizeIsRefused() {
        val dir = dir()
        val thrown = runCatching {
            ModelUnpack.ensure(dir, "m.onnx", { ByteArrayInputStream(bytes) }, expected = 99L, notOlderThan = 0L)
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertFalse(File(dir, "m.onnx").exists())
    }
}
