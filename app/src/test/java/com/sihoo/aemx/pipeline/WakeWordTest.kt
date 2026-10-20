package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.stt.EnergyVad
import com.sihoo.aemx.pipeline.stt.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun `호출어와 명령을 한 번에 말하면 명령만 남긴다`() {
        assertEquals("유튜브에서 고양이 영상 틀어줘", WakeWordMatcher.match("하이 에이맥스 유튜브에서 고양이 영상 틀어줘"))
        assertEquals("유튜브에서 고양이 영상 틀어줘", WakeWordMatcher.match("하이 에이맥스, 유튜브에서 고양이 영상 틀어줘"))
    }

    @Test
    fun `호출어만 말하면 빈 문자열이다`() {
        assertEquals("", WakeWordMatcher.match("하이 에이맥스"))
    }

    @Test
    fun `STT 가 띄어쓰기와 표기를 다르게 적어도 잡는다`() {
        assertEquals("날씨 알려줘", WakeWordMatcher.match("하이에이맥스 날씨 알려줘"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("하이 에이 맥스 날씨 알려줘"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("하이 에이멕스 날씨 알려줘"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("헤이 에이맥스 날씨 알려줘"))
    }

    @Test
    fun `실기기에서 실제로 나온 오인식도 잡는다`() {
        // 2026-10-19 S25+ 에서 "하이 에이맥스" 를 말했을 때 STT 가 적은 결과들.
        listOf(
            "하이 ax?", "아 예이맥스.", "아 이 a맥스?", "하이 a믹스?", "아 에이맥스?", "하이 에이맥스", "아이맥스?",
        ).forEach { heard ->
            assertEquals(heard, "", WakeWordMatcher.match(heard))
        }
    }

    @Test
    fun `오인식된 호출어 뒤의 명령도 살린다`() {
        assertEquals("날씨 알려줘", WakeWordMatcher.match("아 에이맥스 날씨 알려줘"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("하이 ax, 날씨 알려줘"))
    }

    @Test
    fun `맥스를 영단어로 적어도 잡는다`() {
        // 2026-10-20 실기기에서 "하이 에이맥스" 를 "아이 MAX?" 로 적었다.
        assertEquals("", WakeWordMatcher.match("아이 MAX?"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("아이 max 날씨 알려줘"))
        assertEquals("유튜브 틀어줘", WakeWordMatcher.match("하이 Max, 유튜브 틀어줘"))
        assertEquals("", WakeWordMatcher.match("a max"))
        assertEquals("", WakeWordMatcher.match("에이 max"))
        assertEquals("날씨 알려줘", WakeWordMatcher.match("AMAX 날씨 알려줘"))
        assertEquals("", WakeWordMatcher.match("하이 맥스"))
    }

    @Test
    fun `인사 없이 맥스만 있으면 호출로 보지 않는다`() {
        assertNull(WakeWordMatcher.match("맥스 틀어줘"))
        assertNull(WakeWordMatcher.match("max 틀어줘"))
    }

    @Test
    fun `호출어가 없으면 null 이다`() {
        assertNull(WakeWordMatcher.match("유튜브에서 고양이 영상 틀어줘"))
        assertNull(WakeWordMatcher.match("하이 그랜저"))
        assertNull(WakeWordMatcher.match("에?"))
    }

    @Test
    fun `이름이 문장 중간에 나오면 호출로 보지 않는다`() {
        assertNull(WakeWordMatcher.match("어제 하이 에이맥스 얘기했잖아"))
    }

    @Test
    fun `소리가 크면 말하는 중이고 멎은 뒤에도 잠시 열려 있다`() {
        val vad = EnergyVad(threshold = 0.1, hangoverFrames = 2)
        val loud = ShortArray(160) { 10_000 }
        val quiet = ShortArray(160)

        assertTrue(vad.accept(loud, loud.size))
        assertTrue(vad.accept(quiet, quiet.size))
        assertFalse(vad.accept(quiet, quiet.size))
    }
}
