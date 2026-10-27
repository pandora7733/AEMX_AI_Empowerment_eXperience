package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PlannedStep
import com.sihoo.aemx.pipeline.StepStatus
import com.sihoo.aemx.pipeline.model.ActionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantStoreTest {

    @Test
    fun `들어온 순서대로 상태가 만들어진다`() = runTest {
        val store = AssistantStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        store.post(PipelineEvent.RunStarted("r1", 1, "유튜브 열어줘"))
        store.post(PipelineEvent.PlanValidated("r1", 2, 1, listOf(PlannedStep("p1-s0", ActionType.OPEN_APP, "유튜브", "유튜브"))))
        store.post(PipelineEvent.StepChanged("r1", 3, "p1-s0", StepStatus.InProgress))
        store.post(PipelineEvent.StepChanged("r1", 4, "p1-s0", StepStatus.Completed))
        store.post(PipelineEvent.ResultVerified("r1", 5, "앞 화면이 기대한 앱임"))
        advanceUntilIdle()

        assertEquals(AssistantPhase.Success, store.state.value.phase)
        assertEquals(5L, store.state.value.sequence)
    }

    @Test
    fun `뒤섞여 들어온 오래된 사건은 무시된다`() = runTest {
        val store = AssistantStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        store.post(PipelineEvent.RunStarted("r1", 10, "첫 명령"))
        store.post(PipelineEvent.Failed("r1", 12, "나중 사건"))
        store.post(PipelineEvent.NeedsUser("r1", 11, "더 일찍 났지만 늦게 도착"))
        advanceUntilIdle()

        assertEquals(AssistantPhase.Error, store.state.value.phase)
        assertEquals(12L, store.state.value.sequence)
    }
}
