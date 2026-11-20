package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HangulReadingTest {
    private fun ja(romanized: String, original: String = "日本語") = HangulReading.convert(romanized, original, "ja")
    private fun zh(romanized: String, original: String = "中文") = HangulReading.convert(romanized, original, "zh-CN")

    @Test
    fun japaneseBasics() {
        assertEquals("아이시테루", ja("Aishiteru"))
        assertEquals("키미 노 나 와", ja("Kimi no na wa"))
        assertEquals("신지츠", ja("shinjitsu"))
        assertEquals("스키", ja("suki"))
        assertEquals("후유", ja("fuyu"))
        assertEquals("와타시", ja("watashi"))
    }

    @Test
    fun japaneseMoraicNAndDoubledConsonants() {
        assertEquals("킷토", ja("kitto"))
        assertEquals("즛토", ja("zutto"))
        assertEquals("콘니치와", ja("konnichiwa"))
        assertEquals("콘야", ja("kon'ya"))
        assertEquals("맛치", ja("matchi"))
        assertEquals("심분", ja("shimbun"))
        assertEquals("혼토", ja("hontō"))
    }

    @Test
    fun japanesePalatalAndLongVowels() {
        assertEquals("토쿄", ja("Tōkyō"))
        assertEquals("쇼조", ja("shōjo"))
        assertEquals("류", ja("ryū"))
        assertEquals("차", ja("cha"))
        assertEquals("츠키", ja("tsuki"))
    }

    @Test
    fun latinWordsInTheOriginalAreKept() {
        assertEquals("Baby 키미 가 스키", HangulReading.convert("Baby kimi ga suki", "Baby 君が好き", "ja"))
        assertEquals("I love you 아이시테루", HangulReading.convert("I love you aishiteru", "I love you 愛してる", "ja"))
    }

    @Test
    fun wordsThatDoNotParseAreKept() {
        assertEquals("Ooh", ja("Ooh"))
        assertEquals("Oh, 이에!", ja("Oh, ie!"))
    }

    @Test
    fun mandarinBasics() {
        assertEquals("워 아이 니", zh("Wǒ ài nǐ"))
        assertEquals("니 하오", zh("Nǐ hǎo"))
        assertEquals("펑유", zh("Péngyǒu"))
        assertEquals("중궈", zh("Zhōngguó"))
        assertEquals("베이징", zh("Běijīng"))
        assertEquals("셰셰", zh("xièxie"))
        assertEquals("자", zh("jiā"))
        assertEquals("샹", zh("xiǎng"))
        assertEquals("웨", zh("yuè"))
        assertEquals("스", zh("shì"))
        assertEquals("뉘", zh("nǚ"))
        assertEquals("쥐안", zh("juān"))
        assertEquals("창어", zh("Cháng'é"))
    }

    @Test
    fun otherLanguagesAreNotRead() {
        assertNull(HangulReading.convert("annyeong", "안녕", "ko"))
        assertNull(HangulReading.convert("privet", "привет", "ru"))
        assertNull(HangulReading.convert("hello", "hello", null))
    }
}
