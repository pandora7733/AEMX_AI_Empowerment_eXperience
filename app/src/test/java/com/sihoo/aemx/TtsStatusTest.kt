package com.sihoo.aemx

import com.sihoo.aemx.AndroidTtsController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsStatusTest {

    @Test
    fun `엔진이 없으면 설치하라고 알린다`() {
        assertEquals("엔진 없음 (기기에 TTS 엔진을 설치하거나 켜야 함)", TtsStatus.label(State.INIT_FAILED, null))
    }

    @Test
    fun `한국어가 안 되면 엔진 이름과 함께 음성 데이터를 받으라고 알린다`() {
        assertEquals(
            "한국어 음성 없음 (com.example.tts) (한국어 음성 데이터를 받아야 함)",
            TtsStatus.label(State.KOREAN_UNAVAILABLE, "com.example.tts"),
        )
    }

    @Test
    fun `준비됨과 말하는 중과 끝남은 같은 준비됨으로 보인다`() {
        val ready = TtsStatus.label(State.READY, "com.example.tts")
        assertEquals("준비됨 (com.example.tts)", ready)
        assertEquals(ready, TtsStatus.label(State.SPEAKING, "com.example.tts"))
        assertEquals(ready, TtsStatus.label(State.DONE, "com.example.tts"))
    }

    @Test
    fun `아직 쓰지 않았으면 그렇게 보인다`() {
        assertEquals("아직 쓰지 않음", TtsStatus.label(null, null))
    }

    @Test
    fun `보이는 뜻이 바뀔 때만 바뀌었다고 한다`() {
        TtsStatus.engine = null
        TtsStatus.record(State.INITIALIZING)
        assertTrue(TtsStatus.record(State.READY))
        // 말하는 중과 끝남은 준비됨과 같은 뜻이라 로그를 다시 남기지 않는다.
        assertFalse(TtsStatus.record(State.SPEAKING))
        assertFalse(TtsStatus.record(State.DONE))
        assertTrue(TtsStatus.record(State.SPEAK_FAILED))
    }

    @Test
    fun `빈 문장은 엔진 상태를 바꾸지 않는다`() {
        TtsStatus.engine = null
        TtsStatus.record(State.INIT_FAILED)
        assertFalse(TtsStatus.record(State.EMPTY_TEXT))
        assertEquals(State.INIT_FAILED, TtsStatus.state)
    }
}
