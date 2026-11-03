package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEventsTest {

    private fun listening(runId: String = "v1", seq: Long = 1) = PipelineEvent.ListeningStarted(runId, seq)

    @Test
    fun `호출어를 들으면 듣는 중이 된다`() {
        val state = AssistantReducer.reduce(AssistantUiState(), listening())
        assertEquals(AssistantPhase.Listening, state.phase)
        assertEquals("v1", state.runId)
    }

    @Test
    fun `인식 중간 결과는 인식 단계로 바꾸고 글을 넣는다`() {
        var state = AssistantReducer.reduce(AssistantUiState(), listening())
        state = AssistantReducer.reduce(state, PipelineEvent.Recognizing("v1", 2, "유튜브에서"))
        assertEquals(AssistantPhase.Recognizing, state.phase)
        assertEquals("유튜브에서", state.transcript)
    }

    @Test
    fun `실행 중에는 호출어로 듣기 캡슐을 덮지 않는다`() {
        val running = AssistantUiState(runId = "r1", sequence = 5, phase = AssistantPhase.Executing)
        val state = AssistantReducer.reduce(running, listening(seq = 6))
        assertEquals(running, state.copy(sequence = running.sequence))
        assertEquals(AssistantPhase.Executing, state.phase)
    }

    @Test
    fun `듣기가 끝나면 화면을 치운다`() {
        var state = AssistantReducer.reduce(AssistantUiState(), listening())
        state = AssistantReducer.reduce(state, PipelineEvent.ListeningEnded("v1", 3))
        assertEquals(AssistantPhase.Idle, state.phase)
    }

    @Test
    fun `다른 듣기 세션의 끝 신호는 버린다`() {
        val state = AssistantReducer.reduce(AssistantUiState(), listening())
        val after = AssistantReducer.reduce(state, PipelineEvent.ListeningEnded("other", 9))
        assertEquals(AssistantPhase.Listening, after.phase)
    }

    @Test
    fun `명령이 시작되면 듣기 캡슐이 이해 단계로 넘어간다`() {
        val heard = AssistantReducer.reduce(AssistantUiState(), listening())
        val run = AssistantReducer.reduce(heard, PipelineEvent.RunStarted("c1", 4, "고양이 영상 틀어줘"))
        assertEquals(AssistantPhase.Understanding, run.phase)
    }
}
