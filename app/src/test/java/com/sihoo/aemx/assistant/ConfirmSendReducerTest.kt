package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmSendReducerTest {

    private val running = AssistantUiState(runId = "r1", sequence = 1, phase = AssistantPhase.Executing)

    @Test
    fun `보내기 전 확인은 받는 사람과 내용을 담고 확인 단계가 된다`() {
        val state = AssistantReducer.reduce(running, PipelineEvent.ConfirmSend("r1", 2, "p1-s0", "배재준", "테스트"))
        assertEquals(AssistantPhase.ConfirmingAction, state.phase)
        assertEquals("배재준", state.confirmRecipient)
        assertEquals("테스트", state.confirmContent)
        assertTrue(state.expanded)
    }

    @Test
    fun `중단을 요청한 뒤에는 확인 화면을 띄우지 않는다`() {
        val cancelling = running.copy(cancellationRequested = true)
        val state = AssistantReducer.reduce(cancelling, PipelineEvent.ConfirmSend("r1", 2, "p1-s0", "배재준", "테스트"))
        assertEquals(AssistantPhase.Executing, state.phase)
        assertNull(state.confirmRecipient)
    }

    @Test
    fun `다른 명령의 확인 요청은 버린다`() {
        val state = AssistantReducer.reduce(running, PipelineEvent.ConfirmSend("other", 2, "p1-s0", "배재준", "테스트"))
        assertEquals(AssistantPhase.Executing, state.phase)
    }
}
