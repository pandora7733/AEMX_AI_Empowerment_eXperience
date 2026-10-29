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
class AppMissingTest {

    private val notInstalled = "앱이 설치되어 있지 않거나 실행 Activity를 찾을 수 없습니다."

    private class FailingOpen(private val message: String) : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            performed += command
            return ExecutionResult.Failed(message)
        }
    }

    private class RecordingOk : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            performed += command
            return ExecutionResult.Performed()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.orchestrator(
        planner: FakePlanGenerator,
        executor: ActionExecutor,
        observer: FakeScreenObserver,
        logSink: (String) -> Unit = {},
    ) = PipelineOrchestrator(
        planner = planner,
        executor = executor,
        screenObserver = observer,
        appResolver = FakeAppResolver(),
        scope = this,
        config = PipelineConfig(quietPeriodMs = 10),
        worker = UnconfinedTestDispatcher(testScheduler),
        logSink = logSink,
    )

    // ------------------------------------------------------------ 판정과 안내 문장

    @Test
    fun `설치되지 않음과 실행 화면 없음은 앱 없음으로 본다`() {
        assertTrue(AppAvailability.isNotInstalled(notInstalled))
        assertTrue(AppAvailability.isNotInstalled("설치되지 않았거나 찾을 수 없는 앱입니다: 알라딘"))
    }

    @Test
    fun `다른 실패는 앱 없음으로 보지 않는다`() {
        assertFalse(AppAvailability.isNotInstalled("화면 조작 중 오류가 발생했습니다."))
        assertFalse(AppAvailability.isNotInstalled("대상을 찾지 못했다"))
        assertFalse(AppAvailability.isNotInstalled("클라우드 응답 오류 (HTTP 429)"))
    }

    @Test
    fun `안내 문장은 앱 이름과 설치되지 않았다는 말을 담고 다시 앱 없음으로 판정된다`() {
        val message = AppAvailability.userMessage("교보문고")

        assertEquals("교보문고 앱이 설치되어 있지 않아요", message)
        assertTrue(AppAvailability.isNotInstalled(message))
    }

    // ------------------------------------------------------------ 오케스트레이터 동작

    @Test
    fun `앱이 없으면 재분석하지 않고 설치되지 않았다고 알리며 끝낸다`() = runTest {
        val plan = """{"app":"알라딘","steps":[{"action":"open_app","target":"알라딘"},{"action":"tap","target":"검색창"}]}"""
        val planner = FakePlanGenerator(listOf(plan, plan))
        val executor = FailingOpen(notInstalled)
        val observer = FakeScreenObserver(ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈")))

        val orchestrator = orchestrator(planner, executor, observer)
        orchestrator.submit("알라딘 열어줘")
        advanceUntilIdle()

        assertEquals("재분석을 요청하지 않아야 한다", 1, planner.requests.size)
        val state = orchestrator.state.value
        assertTrue("상태가 $state 였다", state is PipelineState.Failed)
        state as PipelineState.Failed
        assertEquals("알라딘 앱이 설치되어 있지 않아요", state.reason)
        assertTrue("사용자에게 소리로 알려야 한다", state.speakReason)
        assertEquals(1, executor.performed.size)
    }

    @Test
    fun `다른 열기 실패는 기존처럼 재분석으로 간다`() = runTest {
        val plan = """{"app":"알라딘","steps":[{"action":"open_app","target":"알라딘"}]}"""
        val planner = FakePlanGenerator(listOf(plan, plan))
        val executor = FailingOpen("앱 화면이 열리지 않았습니다. 앱이 종료되었는지 확인해 주세요.")
        val observer = FakeScreenObserver(ScreenSnapshot(packageName = "com.android.launcher", className = "Home", texts = listOf("홈")))

        orchestrator(planner, executor, observer).submit("알라딘 열어줘")
        advanceUntilIdle()

        assertTrue("재분석을 요청해야 한다", planner.requests.size >= 2)
    }

    @Test
    fun `logcat 로 보내는 판단 기록은 보내기 내용과 발화를 길이만 남긴다`() = runTest {
        val plan = """{"app":"알라딘","steps":[{"action":"open_app","target":"알라딘"}]}"""
        val planner = FakePlanGenerator(listOf(plan))
        val executor = RecordingOk()
        val observer = FakeScreenObserver(ScreenSnapshot(packageName = "com.example.fake.aladin", className = "Home", texts = listOf("홈")))
        val logs = mutableListOf<String>()

        orchestrator(planner, executor, observer, logSink = { logs += it }).submit("배재준한테 안녕이라고 보내줘")
        advanceUntilIdle()

        assertTrue("판단 기록이 logcat 으로 나가야 한다", logs.isNotEmpty())
        assertTrue("발화가 그대로 나가면 안 된다", logs.none { it.contains("배재준") })
        assertTrue("발화는 길이만 남아야 한다", logs.any { it.startsWith("[INFO] ***(") })
        assertTrue("계획 JSON 은 기존 마스킹을 거쳐야 한다", logs.any { it.contains("\"app\"") })
    }
}
