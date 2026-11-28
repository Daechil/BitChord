package com.music.bitchord

import com.music.bitchord.ui.Josa
import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test
    fun `particles follow the sound before them`() {
        assertEquals("높음으로 설정", Josa.fix("높음(으)로 설정"))
        assertEquals("보통으로 설정", Josa.fix("보통(으)로 설정"))
        assertEquals("서울로 가요", Josa.fix("서울(으)로 가요"))      // ㄹ takes 로
        assertEquals("최고로 설정", Josa.fix("최고(으)로 설정"))
        assertEquals("Galaxy S24로 복원", Josa.fix("Galaxy S24(으)로 복원"))
        assertEquals("Pixel 8로", Josa.fix("Pixel 8(으)로"))           // 팔: ㄹ
        assertEquals("“록”과 일치", Josa.fix("“록”와(과) 일치"))         // past the quote
        assertEquals("“팝”과", Josa.fix("“팝”와(과)"))
        assertEquals("“재즈”와", Josa.fix("“재즈”와(과)"))
        assertEquals("갤럭시가 돼요", Josa.fix("갤럭시이(가) 돼요"))
        assertEquals("휴대폰이 돼요", Josa.fix("휴대폰이(가) 돼요"))
        assertEquals("음악은", Josa.fix("음악은(는)"))
    }

    @Test
    fun `a latin word keeps the bracketed form`() {
        assertEquals("FLAC(으)로 설정", Josa.fix("FLAC(으)로 설정"))
        assertEquals("Qobuz은(는)", Josa.fix("Qobuz은(는)"))
    }
}
