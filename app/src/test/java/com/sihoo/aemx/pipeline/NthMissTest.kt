package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NthMissTest {

    /** N번째를 찾지 못한 실행 결과만 돌려주는 실행모듈. */
    private class MissingNth : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            performed += command
            return ExecutionResult.TargetNotFound("5번째 항목을 찾지 못했습니다.")
        }
    }

    private val plan = """{"app":"유튜브","steps":[{"action":"play_first","target":"5번째 영상","value":"5"}]}"""

    @Test
    fun `N번째 영상이 없으면 재분석하지 않고 안내로 끝낸다`() = runTest {
        val planner = FakePlanGenerator(listOf(plan, plan))
        val executor = MissingNth()
        val observer = FakeScreenObserver(ScreenSnapshot(packageName = "com.example.fake.youtube", className = "Results", texts = listOf("결과")))
        val orchestrator = PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = observer,
            appResolver = FakeAppResolver(),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
        )

        orchestrator.submit("유튜브에서 다섯 번째 영상 틀어줘")
        advanceUntilIdle()

        assertEquals("재분석을 요청하면 안 된다", 1, planner.requests.size)
        assertEquals(1, executor.performed.size)
        val state = orchestrator.state.value
        assertTrue("상태가 $state 였다", state is PipelineState.Failed)
        state as PipelineState.Failed
        assertEquals("5번째 영상을 찾지 못했어요", state.reason)
        assertTrue("사용자에게 소리로 알려야 한다", state.speakReason)
    }

    @Test
    fun `유튜브 뮤직의 N번째는 노래라고 부른다`() {
        assertEquals("5번째 노래를 찾지 못했어요", PipelineOrchestrator.nthNotFoundMessage(5, "유튜브 뮤직"))
        assertEquals("2번째 영상을 찾지 못했어요", PipelineOrchestrator.nthNotFoundMessage(2, "유튜브"))
    }
}
