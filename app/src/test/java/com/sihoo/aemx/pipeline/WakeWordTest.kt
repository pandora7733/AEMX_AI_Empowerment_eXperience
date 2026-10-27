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
    fun `IMAX 로 적어도 잡는다`() {
        // 2026-10-20 실기기에서 "하이 에이맥스, 날씨 알려줘" 를 "IMAX? 날씨 알려줘?" 로 적었다.
        // 뒤쪽 구분자(?)도 명령 양끝에서 같이 떼어진다.
        assertEquals("날씨 알려줘", WakeWordMatcher.match("IMAX? 날씨 알려줘?"))
        assertEquals("", WakeWordMatcher.match("IMAX?"))
    }

    @Test
    fun `IMAX 영화처럼 호출이 아닌 말도 깨운다`() {
        // 알려진 부작용이다. "imax" 를 "아이맥스" 로 바꾸면 skip 1 에서 "이맥스" 에 맞아서,
        // 문장 앞에 나온 IMAX 는 실제 영화 얘기여도 호출로 본다. 오인식 복구와 맞바꾼 것이다.
        assertEquals("영화 예매해줘", WakeWordMatcher.match("IMAX 영화 예매해줘"))
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

    private fun frame(amplitude: Int) = ShortArray(1600) { amplitude.toShort() }

    @Test
    fun `적응형 문턱은 주변 소음을 따라 오르되 상한을 넘지 않는다`() {
        val vad = EnergyVad(threshold = 0.025, hangoverFrames = 2, noiseMultiplier = 1.8, maxThreshold = 0.05)
        assertEquals(0.025, vad.currentThreshold, 1e-9)

        // 바닥 소음 0.02 (32768 * 0.02 = 655). 문턱 0.025 아래라 문은 닫혀 있다.
        repeat(20) { assertFalse(vad.accept(frame(655), 1600)) }
        assertEquals(0.036, vad.currentThreshold, 0.001)
        // 0.03 소음은 이제 말소리가 아니다.
        assertFalse(vad.accept(frame(983), 1600))

        // 소음이 아주 커져도 문턱은 0.05 에서 멈춘다.
        repeat(60) { vad.accept(frame(3000), 1600) }
        assertEquals(0.05, vad.currentThreshold, 1e-9)
        // 실제 명령 크기(0.1)는 항상 잡힌다.
        assertTrue(vad.accept(frame(3277), 1600))
    }

    @Test
    fun `적응형을 끄면 문턱은 고정이다`() {
        val vad = EnergyVad(threshold = 0.1, hangoverFrames = 2)
        repeat(30) { vad.accept(frame(1000), 1600) }
        assertEquals(0.1, vad.currentThreshold, 1e-9)
    }
}
