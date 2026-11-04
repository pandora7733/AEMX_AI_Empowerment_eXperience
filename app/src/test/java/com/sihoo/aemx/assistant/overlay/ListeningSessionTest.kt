package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 새 듣기가 시작될 때 이전 말의 글자를 버리는 규칙. 호출어 뒤 첫 인식 전에는 "듣고 있어요…"가 보여야 한다. */
class ListeningSessionTest {

    private fun state(runId: String, phase: AssistantPhase, transcript: String = "") =
        AssistantUiState(runId = runId, sequence = 1, phase = phase, transcript = transcript)

    @Test fun `명령이 바뀐 듣기는 새 듣기다`() {
        val previous = state("a", AssistantPhase.Recognizing, "아 MX?")
        val next = state("b", AssistantPhase.Listening)

        assertTrue(LiveCaption.startsNewListening(previous, next))
    }

    @Test fun `듣기 아닌 상태에서 듣기로 들어가면 새 듣기다`() {
        assertTrue(LiveCaption.startsNewListening(AssistantUiState(), state("b", AssistantPhase.Listening)))
    }

    @Test fun `같은 듣기 안의 중간 결과는 새 듣기가 아니다`() {
        val listening = state("a", AssistantPhase.Listening)
        val recognizing = state("a", AssistantPhase.Recognizing, "날씨")

        assertFalse(LiveCaption.startsNewListening(listening, recognizing))
        assertFalse(LiveCaption.startsNewListening(recognizing, state("a", AssistantPhase.Recognizing, "날씨 알려")))
    }

    @Test fun `이전 말을 버리면 새 듣기의 빈 말은 듣고 있어요 한 줄이 된다`() {
        val throttle = LiveCaption.Throttle()
        throttle.offer("아 MX?", now = 1_000)
        assertEquals("아 MX?", LiveCaption.display(throttle.shown))

        // 새 듣기 시작: 상태가 듣기 사이의 Idle 을 거치지 않고 바뀌었다고 가정한다.
        throttle.reset()
        assertNull(throttle.offer("", now = 5_000))

        val text = CapsuleTextFactory.from(
            state("b", AssistantPhase.Listening),
            LiveCaption.display(throttle.shown),
        )
        assertEquals(CapsuleTextFactory.LISTENING, text.title)
        assertEquals(VoiceState.Listening, text.voice)
    }
}
