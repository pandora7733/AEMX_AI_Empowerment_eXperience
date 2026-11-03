package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateTest {

    private fun state(phase: AssistantPhase, cancellationRequested: Boolean = false) =
        AssistantUiState(runId = "r1", phase = phase, cancellationRequested = cancellationRequested)

    @Test
    fun `듣는 중과 인식 단계만 파형 모양이고 나머지는 접힌 캡슐이다`() {
        assertEquals(VoiceState.Listening, CapsuleTextFactory.from(state(AssistantPhase.Listening)).voice)
        assertEquals(VoiceState.Recognizing, CapsuleTextFactory.from(state(AssistantPhase.Recognizing)).voice)
        for (phase in listOf(AssistantPhase.Understanding, AssistantPhase.Planning, AssistantPhase.Executing)) {
            assertEquals("$phase", VoiceState.None, CapsuleTextFactory.from(state(phase)).voice)
        }
    }

    @Test
    fun `중단을 누르면 파형과 광원을 바로 멈춘다`() {
        val text = CapsuleTextFactory.from(state(AssistantPhase.Listening, cancellationRequested = true))
        assertEquals(VoiceState.None, text.voice)
    }

    @Test
    fun `파형 막대의 배율은 lo 와 hi 사이에 있다`() {
        for (i in 0 until VoiceWaveGeometry.BAR_COUNT) {
            val (lo, hi) = VoiceWaveGeometry.range(i)
            assertTrue("bar $i lo<hi", lo < hi)
            for (k in 0..40) {
                val s = VoiceWaveGeometry.scale(i, k * 0.1)
                assertTrue("bar $i t=${k * 0.1} s=$s", s >= lo - 1e-4f && s <= hi + 1e-4f)
            }
        }
    }

    @Test
    fun `호흡 곡선은 주기 시작에서 0 이고 절반에서 1 이다`() {
        assertEquals(0f, VoiceWaveGeometry.breathe(0), 1e-4f)
        assertEquals(1f, VoiceWaveGeometry.breathe(CapsuleMotion.GLOW_BREATHE_MS / 2), 1e-4f)
    }

    @Test
    fun `움직임 시간은 디자인 motionMs 와 같다`() {
        assertEquals(260L, CapsuleMotion.ENTER_MS)
        assertEquals(180L, CapsuleMotion.RECOGNIZE_MS)
        assertEquals(1800L, CapsuleMotion.UNDERSTAND_PULSE_MS)
        assertEquals(0L, CapsuleMotion.CANCEL_MS)
        assertEquals(0L, CapsuleMotion.REDUCED_MS)
    }
}
