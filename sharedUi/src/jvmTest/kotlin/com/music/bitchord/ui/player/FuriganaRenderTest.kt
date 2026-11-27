package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * KO: draws a few lyric lines with furigana off screen, through the same
 * [rubyRoomStyle], [measureRuby] and [drawRuby] the panel uses, and writes the
 * picture to build/ for a look. Skipped where Skia has no native library.
 */
class FuriganaRenderTest {
    private val samples = listOf(
        "抜錨を見て" to "{抜錨|ばつびょう}を{見|み}て",
        "本気で運命を信じてる" to "{本気|まじ}で{運命|うんめい}を{信|しん}じてる",
        "夜に駆ける 君と二人で 遠くの街まで歩いた" to "{夜|よる}に{駆|か}ける {君|きみ}と{二人|ふたり}で {遠|とお}くの{街|まち}まで{歩|ある}いた",
    )

    @Test
    fun render() {
        val scene = runCatching { ImageComposeScene(width = 900, height = 760) }.getOrNull() ?: return
        scene.use {
            it.setContent {
                Column(Modifier.background(Color(0xFF202030)).padding(24.dp).width(420.dp)) {
                    val base = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    samples.forEach { (line, markup) ->
                        val marks = rubyMarks(line, markup)!!
                        val style = rubyRoomStyle(base)
                        val measurer = rememberTextMeasurer()
                        val readings = remember { measureRuby(measurer, marks, style) }
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            text = line,
                            style = style,
                            onTextLayout = { result -> layout = result },
                            modifier = Modifier.padding(bottom = 12.dp).drawWithContent {
                                drawContent()
                                val measured = layout ?: return@drawWithContent
                                drawRuby(measured, marks, readings, Offset.Zero, Color.White, style.fontSize.toPx())
                            },
                        )
                    }
                }
            }
            val image = it.render()
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File("build/furigana-render.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            assertTrue(png.size > 1000)
        }
    }
}
