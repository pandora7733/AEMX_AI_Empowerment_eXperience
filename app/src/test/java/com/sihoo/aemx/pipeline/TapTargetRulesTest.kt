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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TapTargetRulesTest {

    @Test
    fun `몇 번째 항목이나 위치 말은 위치 설명이다`() {
        listOf("첫 번째 결과", "2번째 항목", "세 번째 동영상", "가장 상단 항목", "맨 위 항목", "가장 아래 버튼").forEach {
            assertTrue(it, TapTargetRules.isPositional(it))
        }
    }

    @Test
    fun `화면 글자로 찾을 수 있는 대상은 위치 설명이 아니다`() {
        listOf("검색창", "확인", "구독 버튼", "결제하기", "동영상 재생").forEach {
            assertFalse(it, TapTargetRules.isPositional(it))
        }
    }

    @Test
    fun `위치 설명 탭은 재분석 없이 몇 번째 항목을 누르지 못한다고 끝낸다`() = runTest {
        val plan = """{"app":"네이버","steps":[{"action":"tap","target":"첫 번째 결과"}]}"""
        val planner = FakePlanGenerator(listOf(plan, plan))
        val executor = object : ActionExecutor {
            val performed = mutableListOf<ActionExecutor.Command>()
            override fun isReady() = true
            override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
                performed += command
                return ExecutionResult.Performed()
            }
        }
        val observer = FakeScreenObserver(ScreenSnapshot(packageName = "com.nhn.android.search", className = "Results", texts = listOf("결과")))
        val orchestrator = PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = observer,
            appResolver = FakeAppResolver(),
            scope = this,
            config = PipelineConfig(quietPeriodMs = 10),
            worker = UnconfinedTestDispatcher(testScheduler),
        )

        orchestrator.submit("네이버에서 날씨 검색해서 첫 번째 결과 눌러")
        advanceUntilIdle()

        assertEquals("재분석을 요청하면 안 된다", 1, planner.requests.size)
        assertTrue("누르지 않아야 한다", executor.performed.isEmpty())
        val state = orchestrator.state.value
        assertTrue("상태가 $state 였다", state is PipelineState.Failed)
        state as PipelineState.Failed
        assertEquals(TapTargetRules.POSITIONAL_MESSAGE, state.reason)
        assertTrue(state.speakReason)
    }
}
