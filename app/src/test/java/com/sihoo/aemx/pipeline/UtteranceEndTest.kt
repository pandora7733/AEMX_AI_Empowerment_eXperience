package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.stt.UtteranceEnd
import com.sihoo.aemx.pipeline.stt.classifyUtteranceEnd
import com.sihoo.aemx.pipeline.stt.formatUtteranceEndLine
import com.sihoo.aemx.pipeline.stt.trailingQuietFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceEndTest {
    @Test
    fun `문이 닫혀서 끝나면 VAD 무음이다`() {
        assertEquals(UtteranceEnd.VAD_SILENCE, classifyUtteranceEnd(viaGate = true, durationMs = 3_000, maxLengthMs = 20_000))
        // 문이 닫혀 끝난 문장이 아주 길어도 이유는 VAD 다.
        assertEquals(UtteranceEnd.VAD_SILENCE, classifyUtteranceEnd(viaGate = true, durationMs = 25_000, maxLengthMs = 20_000))
    }

    @Test
    fun `인식기 끝점은 길이로 무음 규칙과 최대 길이 규칙을 가른다`() {
        assertEquals(UtteranceEnd.ENDPOINT_SILENCE, classifyUtteranceEnd(viaGate = false, durationMs = 4_000, maxLengthMs = 20_000))
        assertEquals(UtteranceEnd.ENDPOINT_MAX_LENGTH, classifyUtteranceEnd(viaGate = false, durationMs = 20_000, maxLengthMs = 20_000))
    }

    @Test
    fun `끝에서부터 이어진 조용한 프레임을 센다`() {
        assertEquals(0, trailingQuietFrames(emptyList()))
        assertEquals(0, trailingQuietFrames(listOf(false, true)))
        assertEquals(3, trailingQuietFrames(listOf(true, false, true, false, false, false)))
        assertEquals(2, trailingQuietFrames(listOf(false, false)))
    }

    @Test
    fun `측정 줄은 문장 끝으로 시작하고 이유와 길이를 담는다`() {
        val line = formatUtteranceEndLine(
            startMs = 1_000, endMs = 4_500, reason = UtteranceEnd.VAD_SILENCE, trailingQuietMs = 2_500,
            lastPartial = "안녕", finalText = "안녕하세요", showText = true,
        )

        assertTrue(line.startsWith("문장 끝:"))
        assertTrue(line.contains("길이=3500ms"))
        assertTrue(line.contains("이유=VAD 무음"))
        assertTrue(line.contains("끝무음=2500ms"))
        assertTrue(line.contains("마지막중간=\"안녕\""))
        assertTrue(line.contains("최종=\"안녕하세요\""))
    }

    @Test
    fun `디버그가 아니면 말한 내용은 빼고 글자 수만 남긴다`() {
        val line = formatUtteranceEndLine(
            startMs = 0, endMs = 1_000, reason = UtteranceEnd.ENDPOINT_SILENCE, trailingQuietMs = 2_000,
            lastPartial = "비밀번호", finalText = "비밀번호는", showText = false,
        )

        assertFalse(line.contains("비밀번호"))
        assertTrue(line.contains("마지막중간=(4자)"))
        assertTrue(line.contains("최종=(5자)"))
    }
}
