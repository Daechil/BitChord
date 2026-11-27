package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KO: which disc's hint is due, and a picture of the bubbles written to build/ for a look. */
class LyricsHintsTest {

    @Test
    fun `hints come in order and only for discs on screen`() {
        val reading = LyricsHints.token().also { LyricsHints.register(it, LyricsHint.READING) }
        val translate = LyricsHints.token().also { LyricsHints.register(it, LyricsHint.TRANSLATE) }
        try {
            assertEquals(translate, LyricsHints.due(emptySet()))
            assertEquals(reading, LyricsHints.due(setOf("translate")))
            // ふ is not on screen, so with both others seen nothing is due.
            assertNull(LyricsHints.due(setOf("translate", "reading")))
        } finally {
            LyricsHints.unregister(reading)
            LyricsHints.unregister(translate)
        }
    }

    @Test
    fun render() {
        val scene = runCatching { ImageComposeScene(width = 760, height = 420) }.getOrNull() ?: return
        scene.use {
            it.setContent {
                Column(
                    Modifier.background(Color(0xFF3A2E4A)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    listOf(
                        "누르면 번역 · 길게 누르면 Claude로 다시 번역해요",
                        "누르면 발음 · 길게 누르면 Claude 발음을 받아요",
                        "누르면 한자 위에 읽는 법을 달아요",
                    ).forEach { text ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HintBubble(text = text, onClick = {})
                            Row {
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("ふ", color = Color.White, fontSize = 16.sp) }
                            }
                        }
                    }
                }
            }
            val png = it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File("build/lyrics-hints-render.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            assertTrue(png.isNotEmpty())
        }
    }
}
