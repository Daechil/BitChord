package com.music.bitchord.playback.smart

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * A model's weights put on disk where ONNX Runtime can map them: written to a
 * temporary file and moved into place only once whole, so a process killed
 * mid-copy leaves nothing that looks like a model. Written again when the
 * app has been updated since — a model replaced under the same name — or
 * when the file is not the size it should be.
 */
internal object ModelUnpack {
    private val lock = Any()

    /**
     * The model [name] in [dir], unpacked from [open] when it is missing,
     * older than [notOlderThan] (the app's last update), or not [expected]
     * bytes long where that is known.
     */
    fun ensure(dir: File, name: String, open: () -> InputStream, expected: Long?, notOlderThan: Long): File =
        synchronized(lock) {
            val file = File(dir, name)
            val fresh = file.isFile && file.length() > 0L &&
                file.lastModified() >= notOlderThan &&
                (expected == null || file.length() == expected)
            if (fresh) return file
            dir.mkdirs()
            val temporary = File(dir, "$name.tmp")
            try {
                var written = 0L
                open().use { input ->
                    FileOutputStream(temporary).use { output ->
                        written = input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (written <= 0L || (expected != null && written != expected)) {
                    throw IOException("model $name unpacked to $written bytes")
                }
                if (!temporary.renameTo(file)) {
                    file.delete()
                    if (!temporary.renameTo(file)) throw IOException("model $name could not be moved into place")
                }
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
            file
        }
}
