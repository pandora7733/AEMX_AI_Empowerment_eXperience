package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 움직임의 값 계산(안드로이드 없이). 실제 애니메이션은 기기에서 본다. */
class OverlayMotionTest {

    @Test fun `단계 행은 60ms 뒤부터 50ms 간격으로 나타난다`() {
        assertEquals(60L, OverlayMotion.rowDelayMs(0))
        assertEquals(110L, OverlayMotion.rowDelayMs(1))
        assertEquals(210L, OverlayMotion.rowDelayMs(3))
    }

    @Test fun `펄스는 1에서 0점55까지 내려갔다가 주기가 끝나면 다시 1이다`() {
        val period = 1600L
        assertEquals(1f, OverlayMotion.pulseAlpha(0, period), 0.001f)
        assertEquals(0.55f, OverlayMotion.pulseAlpha(period / 2, period), 0.001f)
        assertEquals(1f, OverlayMotion.pulseAlpha(period, period), 0.001f)
        val middle = OverlayMotion.pulseAlpha(period / 4, period)
        assertTrue("중간 값은 범위 안이다", middle in 0.55f..1f)
    }

    @Test fun `펄스 값은 항상 0점55에서 1 사이다`() {
        var t = 0L
        while (t < 3_600L) {
            val alpha = OverlayMotion.pulseAlpha(t, CapsuleMotion.UNDERSTAND_PULSE_MS)
            assertTrue("t=$t 에서 $alpha", alpha >= 0.55f - 0.001f && alpha <= 1f + 0.001f)
            t += 50
        }
    }

    @Test fun `글자는 처음 한 글자부터 40ms 마다 하나씩 나타나고 끝까지 가면 전부 보인다`() {
        assertEquals(0, OverlayMotion.typedCount(0, 100))
        assertEquals(1, OverlayMotion.typedCount(5, 0))
        assertEquals(3, OverlayMotion.typedCount(5, 80))
        assertEquals(5, OverlayMotion.typedCount(5, 160))
        assertEquals(5, OverlayMotion.typedCount(5, 10_000))
    }

    @Test fun `성공 크기는 0점96 에서 1 로 자란다`() {
        assertEquals(0.96f, OverlayMotion.successScale(0f), 0.0001f)
        assertEquals(1f, OverlayMotion.successScale(1f), 0.0001f)
        assertEquals(0.98f, OverlayMotion.successScale(0.5f), 0.0001f)
        assertEquals(1f, OverlayMotion.successScale(2f), 0.0001f)
    }

    @Test fun `이해 단계에서만 글자가 맥박친다`() {
        val understanding = CapsuleTextFactory.from(AssistantUiState(phase = AssistantPhase.Understanding))
        val planning = CapsuleTextFactory.from(AssistantUiState(phase = AssistantPhase.Planning))
        val cancelling = CapsuleTextFactory.from(
            AssistantUiState(phase = AssistantPhase.Understanding, cancellationRequested = true),
        )

        assertTrue(understanding.pulse)
        assertFalse(planning.pulse)
        assertFalse("중단 중이면 맥박치지 않는다", cancelling.pulse)
    }
}
