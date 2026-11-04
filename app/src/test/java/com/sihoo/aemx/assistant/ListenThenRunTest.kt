package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 호출어 듣기가 끝나고 바로 명령이 시작되는 순서. 명령이 화면에 남아야 "…하는 중" 캡슐이 이어진다.
 * 듣기 끝(ListeningEnded)에 화면이 잠깐 비어도 뒤의 RunStarted 는 받아야 한다.
 */
class ListenThenRunTest {

    private fun reduce(state: AssistantUiState, event: PipelineEvent) = AssistantReducer.reduce(state, event)

    @Test fun `듣기가 끝난 뒤의 명령 시작은 번호가 크면 받는다`() {
        var state = AssistantUiState()
        state = reduce(state, PipelineEvent.ListeningStarted("v1", 1))
        state = reduce(state, PipelineEvent.Recognizing("v1", 2, "하긴 에이맥스"))
        state = reduce(state, PipelineEvent.ListeningEnded("v1", 3))
        assertEquals(AssistantPhase.Idle, state.phase)

        state = reduce(state, PipelineEvent.RunStarted("r1", 4, "유튜브에서 고양이 틀어줘"))

        assertEquals(AssistantPhase.Understanding, state.phase)
        assertEquals("r1", state.runId)
    }

    @Test fun `명령 번호가 듣기 끝 번호보다 작으면 버려진다`() {
        // 화면은 번호가 이미 본 것보다 작은 사건을 버린다. 번호 순서가 어긋나면 명령이 아예 안 보인다.
        var state = AssistantUiState()
        state = reduce(state, PipelineEvent.ListeningStarted("v1", 1))
        state = reduce(state, PipelineEvent.ListeningEnded("v1", 5))

        state = reduce(state, PipelineEvent.RunStarted("r1", 4, "유튜브에서 고양이 틀어줘"))

        assertEquals(AssistantPhase.Idle, state.phase)
    }
}
