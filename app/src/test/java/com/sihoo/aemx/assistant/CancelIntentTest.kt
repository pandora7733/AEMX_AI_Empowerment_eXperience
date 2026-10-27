package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PlannedStep
import com.sihoo.aemx.pipeline.StepStatus
import com.sihoo.aemx.pipeline.model.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 캡슐의 중단 버튼이 보내는 의도와, 진행 문장. */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelIntentTest {

    private fun TestScopeStore(scope: CoroutineScope) = AssistantStore(scope)

    @Test
    fun `진행 중이면 현재 명령의 runId 로 중단 의도를 보낸다`() = runTest {
        val store = TestScopeStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.cancelRequests.collect { received += it } }

        store.post(PipelineEvent.RunStarted("r1", 1, "유튜브 열어줘"))
        advanceUntilIdle()
        store.requestCancellation()

        assertEquals(listOf("r1"), received)
    }

    @Test
    fun `대기 중이거나 끝났거나 이미 요청했으면 보내지 않는다`() = runTest {
        val store = TestScopeStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.cancelRequests.collect { received += it } }

        store.requestCancellation() // 대기 중
        store.post(PipelineEvent.RunStarted("r1", 1, "x"))
        store.post(PipelineEvent.CancelRequested("r1", 2))
        advanceUntilIdle()
        store.requestCancellation() // 이미 중단 요청됨
        store.post(PipelineEvent.Cancelled("r1", 3, emptySet()))
        advanceUntilIdle()
        store.requestCancellation() // 끝남

        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun `하는 중에 보이는 문장을 만든다`() {
        assertEquals("유튜브를 여는 중…", StepLabelFormatter.progressText(ActionType.OPEN_APP, "유튜브", "유튜브"))
        assertEquals("'고양이' 검색 중…", StepLabelFormatter.progressText(ActionType.SEARCH, "고양이", "유튜브"))
        assertEquals("글자 입력 중…", StepLabelFormatter.progressText(ActionType.INPUT_TEXT, "검색창", "유튜브"))
        assertEquals("배재준에게 카톡 보내는 중…", StepLabelFormatter.progressText(ActionType.SEND_MESSAGE, "배재준", "카카오톡"))
    }

    @Test
    fun `계획을 받으면 단계에 진행 문장이 붙는다`() {
        val state = AssistantReducer.reduce(
            AssistantReducer.reduce(AssistantUiState(), PipelineEvent.RunStarted("r1", 1, "x")),
            PipelineEvent.PlanValidated("r1", 2, 1, listOf(PlannedStep("p1-s0", ActionType.SEARCH, "고양이", "유튜브")))
        )
        assertEquals("'고양이' 검색 중…", state.steps.single().progressText)
        assertEquals(StepStatus.Pending, state.steps.single().status)
    }
}
