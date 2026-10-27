package com.sihoo.aemx.pipeline

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantReducer
import com.sihoo.aemx.assistant.AssistantUiState
import com.sihoo.aemx.pipeline.fake.FakeActionExecutor
import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ExecutionResult
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오케스트레이터가 내보내는 [PipelineEvent] 의 순서와 내용을 가상 시간으로 검증한다.
 * 화면 쪽 규칙은 AssistantReducerTest, 여기서는 사건을 내는 쪽만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineEventsTest {

    private val loginPlan =
        """{"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}, {"action": "tap", "target": "로그인"}]}"""

    private fun snapshot(vararg texts: String, pkg: String = "com.example.fake.aladin") =
        ScreenSnapshot(packageName = pkg, className = "Screen", texts = texts.toList())

    /** 명령을 받을 때마다 대본의 화면으로 넘어가는 실행모듈. [idle] 로 서비스가 멈췄는지 조절한다. */
    private class ScriptedExecutor(
        private val observer: FakeScreenObserver,
        private val screensAfterEach: List<ScreenSnapshot?>,
        var idle: Boolean = true
    ) : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()
        override fun isReady() = true
        override fun isIdle() = idle

        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            val next = screensAfterEach.getOrNull(performed.size)
            performed += command
            if (next != null) observer.advanceTo(next)
            return ExecutionResult.Performed()
        }
    }

    /** 추론이 도중에 멈출 수 없는 LLM(네이티브 호출)을 흉내 낸다. 취소돼도 [latencyMs] 가 지나야 돌아온다. */
    private class StubbornPlanner(private val raw: String, private val latencyMs: Long) : PlanGenerator {
        override fun isReady() = true

        override suspend fun generate(request: PlanRequest): PlanResult =
            withContext(NonCancellable) {
                delay(latencyMs)
                val parsed = ActionPlanParser.parse(raw) as ParseResult.Success
                PlanResult.Success(parsed.plan, raw, parsed.warnings)
            }
    }

    private fun TestScope.build(
        planner: PlanGenerator,
        executor: ActionExecutor,
        observer: FakeScreenObserver,
        config: PipelineConfig = PipelineConfig(quietPeriodMs = 10)
    ) = PipelineOrchestrator(
        planner = planner,
        executor = executor,
        screenObserver = observer,
        appResolver = FakeAppResolver(),
        scope = this,
        config = config,
        worker = UnconfinedTestDispatcher(testScheduler)
    )

    /** 오케스트레이터의 사건을 모은다. 구독이 submit 보다 먼저여야 해서 즉시 시작한다. */
    private fun TestScope.collect(orchestrator: PipelineOrchestrator): MutableList<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            orchestrator.events.collect { events += it }
        }
        return events
    }

    private fun List<PipelineEvent>.names() = map { it::class.java.simpleName }

    private fun List<PipelineEvent>.toState(): AssistantUiState =
        fold(AssistantUiState()) { state, event -> AssistantReducer.reduce(state, event) }

    // ------------------------------------------------------------ 정상 흐름

    @Test
    fun `성공하면 계획과 단계 변화가 순서대로 나오고 결과로 끝난다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈", pkg = "com.android.launcher"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("알라딘", "로그인"), snapshot("아이디", "비밀번호")))
        val orchestrator = build(FakePlanGenerator(listOf(loginPlan)), executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "RunStarted", "PlanValidated",
                "StepChanged", "StepChanged", "StepChanged", "StepChanged",
                "ResultVerified"
            ),
            events.names()
        )
        // 한 명령의 사건은 같은 runId 를 쓰고 번호가 계속 커진다.
        assertEquals(1, events.map { it.runId }.toSet().size)
        assertEquals(events.map { it.sequence }.sorted(), events.map { it.sequence })
        assertEquals(events.size, events.map { it.sequence }.toSet().size)

        val plan = events[1] as PipelineEvent.PlanValidated
        assertEquals(listOf("p1-s0", "p1-s1"), plan.steps.map { it.stepId })
        assertEquals(
            listOf(
                StepStatus.InProgress, StepStatus.Completed, StepStatus.InProgress, StepStatus.Completed
            ),
            events.filterIsInstance<PipelineEvent.StepChanged>().map { it.status }
        )

        // 화면 상태로 접으면 증거가 있는 성공이다(탭 단계가 화면이 바뀐 것을 확인했다).
        val state = events.toState()
        assertEquals(AssistantPhase.Success, state.phase)
        assertTrue(state.mayShowSuccess)
        assertNotNull((events.last() as PipelineEvent.ResultVerified).evidence)
    }

    @Test
    fun `기대 조건이 없는 마지막 단계는 증거 없이 끝나 전달이 된다`() = runTest {
        val plan = """{"app":"유튜브","steps":[{"action":"wait","value":"1s"}]}"""
        val observer = FakeScreenObserver(snapshot("쇼츠"))
        val orchestrator = build(
            FakePlanGenerator(listOf(plan)), ScriptedExecutor(observer, emptyList()), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("1초 기다려")
        advanceUntilIdle()

        assertNull((events.last() as PipelineEvent.ResultVerified).evidence)
        assertEquals(AssistantPhase.Delivered, events.toState().phase)
    }

    @Test
    fun `메시지 전송 확인은 실행모듈의 증거로 올라온다`() = runTest {
        val plan = """{"app":"카카오톡","steps":[{"action":"send_message","target":"배재준","value":"안녕"}]}"""
        val observer = FakeScreenObserver(snapshot("카톡", pkg = "com.kakao.talk"))
        val executor = FakeActionExecutor(
            resultFor = { ExecutionResult.Performed("보내기를 누르고 공유 화면이 닫힌 것을 확인함") }
        )
        val orchestrator = build(FakePlanGenerator(listOf(plan)), executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("배재준한테 안녕 보내줘")
        advanceUntilIdle()

        val planned = events.filterIsInstance<PipelineEvent.PlanValidated>().single()
        assertEquals("배재준", planned.steps.single().target)
        assertEquals("보내기를 누르고 공유 화면이 닫힌 것을 확인함", (events.last() as PipelineEvent.ResultVerified).evidence)
        val state = events.toState()
        assertEquals(AssistantPhase.Success, state.phase)
        assertEquals(listOf("배재준에게 카톡 보내기"), state.steps.map { it.label })
        // 보낼 내용은 계획 사건 어디에도 없다(사용자가 한 말은 RunStarted 에만 있다).
        assertFalse(events.filterNot { it is PipelineEvent.RunStarted }.joinToString { it.toString() }.contains("안녕"))
    }

    // ------------------------------------------------------------ 재분석과 실패

    @Test
    fun `재분석하면 새 계획 번호가 붙고 끝낸 단계는 그대로다`() = runTest {
        val observer = FakeScreenObserver(snapshot("알라딘", "홈"))
        val executor = ScriptedExecutor(observer, listOf(null, snapshot("아이디", "비밀번호")))
        val planner = FakePlanGenerator(
            listOf(
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""",
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "회원 로그인"}]}"""
            )
        )
        val orchestrator = build(planner, executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "RunStarted", "PlanValidated",
                "StepChanged", "StepChanged", // p1-s0 시작, 실패
                "Replanning", "PlanValidated",
                "StepChanged", "StepChanged", // p2-s0 시작, 완료
                "ResultVerified"
            ),
            events.names()
        )
        val failed = events.filterIsInstance<PipelineEvent.StepChanged>().first { it.status == StepStatus.Failed }
        assertEquals("p1-s0", failed.stepId)
        assertTrue(failed.reason!!.contains("바뀌지 않았다"))
        assertEquals(1, (events.single { it is PipelineEvent.Replanning } as PipelineEvent.Replanning).replanCount)

        val plans = events.filterIsInstance<PipelineEvent.PlanValidated>()
        assertEquals(listOf(1, 2), plans.map { it.planNumber })
        assertEquals(listOf("p2-s0"), plans[1].steps.map { it.stepId })
        assertEquals(AssistantPhase.Success, events.toState().phase)
    }

    @Test
    fun `되돌릴 수 없는 실패는 실패 사건으로 끝난다`() = runTest {
        val plan = """{"app":"쿠팡","steps":[{"action":"open_app","target":"쿠팡"}]}"""
        val observer = FakeScreenObserver(snapshot("홈", pkg = "com.android.launcher"))
        val orchestrator = build(
            FakePlanGenerator(listOf(plan)), ScriptedExecutor(observer, emptyList()), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("쿠팡 열어줘")
        advanceUntilIdle()

        assertEquals(listOf("RunStarted", "PlanValidated", "StepChanged", "StepChanged", "Failed"), events.names())
        assertTrue((events.last() as PipelineEvent.Failed).message.contains("쿠팡"))
        assertEquals(AssistantPhase.Error, events.toState().phase)
    }

    @Test
    fun `접근성 서비스가 꺼져 있으면 실패 사건이 나온다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val orchestrator = build(
            FakePlanGenerator(listOf(loginPlan)), FakeActionExecutor(ready = false), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertEquals(listOf("RunStarted", "Failed"), events.names())
    }

    @Test
    fun `사람이 해야 하면 사건이 나온다`() = runTest {
        val observer = FakeScreenObserver(snapshot("알라딘"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("보안문자를 입력하세요")))
        val orchestrator = build(
            FakePlanGenerator(listOf("""{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""")),
            executor, observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertTrue(events.last() is PipelineEvent.NeedsUser)
        assertEquals(AssistantPhase.NeedsUser, events.toState().phase)
    }

    // ------------------------------------------------------------ 중단

    @Test
    fun `추론 중에 중단하면 돌아온 계획을 버리고 실행하지 않은 채 취소됨을 낸다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("알라딘")))
        val orchestrator = build(StubbornPlanner(loginPlan, latencyMs = 30_000), executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceTimeBy(1_000)
        orchestrator.requestCancel()
        advanceTimeBy(1_000)

        // 추론이 아직 안 돌아왔다: 중단 요청만 알렸고 취소됨은 아니다.
        assertEquals(listOf("RunStarted", "CancelRequested"), events.names())
        assertEquals(AssistantPhase.Understanding, events.toState().phase)
        assertTrue(events.toState().cancellationRequested)

        advanceUntilIdle()

        assertEquals(listOf("RunStarted", "CancelRequested", "Cancelled"), events.names())
        assertTrue("추론이 돌아와도 실행하면 안 된다", executor.performed.isEmpty())
        assertEquals(AssistantPhase.Cancelled, events.toState().phase)
        assertEquals(emptySet<String>(), (events.last() as PipelineEvent.Cancelled).completedStepIds)
    }

    @Test
    fun `서비스가 멈추기 전에는 취소됨을 내지 않는다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, emptyList(), idle = false)
        val orchestrator = build(StubbornPlanner(loginPlan, latencyMs = 1_000), executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceTimeBy(100)
        orchestrator.requestCancel()
        advanceTimeBy(3_000) // 추론은 돌아왔지만 서비스는 아직 돌고 있다.

        assertEquals(listOf("RunStarted", "CancelRequested"), events.names())

        executor.idle = true
        advanceUntilIdle()

        assertEquals(listOf("RunStarted", "CancelRequested", "Cancelled"), events.names())
    }

    @Test
    fun `정해진 시간 안에 서비스가 멈추지 않으면 중단을 확인하지 못했다고 알린다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, emptyList(), idle = false)
        val orchestrator = build(
            StubbornPlanner(loginPlan, latencyMs = 100), executor, observer,
            PipelineConfig(quietPeriodMs = 10, cancelConfirmTimeoutMs = 2_000)
        )
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceTimeBy(50)
        orchestrator.requestCancel()
        advanceUntilIdle()

        assertEquals(listOf("RunStarted", "CancelRequested", "Failed"), events.names())
        assertEquals("중단을 확인하지 못했어요", (events.last() as PipelineEvent.Failed).message)
        assertEquals(AssistantPhase.Error, events.toState().phase)
    }

    @Test
    fun `실행 중 중단하면 끝낸 단계를 알리고 그 뒤 단계는 실행하지 않는다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈", pkg = "com.android.launcher"))
        val plan = """{"app":"유튜브","steps":[{"action":"wait","value":"1s"},{"action":"wait","value":"10s"},{"action":"scroll","target":"down"}]}"""
        val executor = ScriptedExecutor(observer, emptyList())
        val orchestrator = build(FakePlanGenerator(listOf(plan)), executor, observer)
        val events = collect(orchestrator)

        orchestrator.submit("기다렸다가 내려줘")
        advanceTimeBy(2_000) // 첫 대기는 끝났고 두 번째 대기 중
        orchestrator.requestCancel()
        advanceUntilIdle()

        assertTrue(executor.performed.isEmpty())
        val cancelled = events.last() as PipelineEvent.Cancelled
        assertEquals(setOf("p1-s0"), cancelled.completedStepIds)
        // 중단을 요청한 뒤에는 중단 관련이 아닌 사건이 나오지 않는다.
        val afterRequest = events.dropWhile { it !is PipelineEvent.CancelRequested }.names()
        assertEquals(listOf("CancelRequested", "Cancelled"), afterRequest)
        assertEquals(AssistantPhase.Cancelled, events.toState().phase)
    }

    @Test
    fun `기존 cancel 은 상태를 바로 Idle 로 돌리되 취소됨은 확인 뒤에 낸다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val orchestrator = build(
            StubbornPlanner(loginPlan, latencyMs = 5_000), ScriptedExecutor(observer, emptyList()), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceTimeBy(100)
        orchestrator.cancel()

        assertTrue(orchestrator.state.value is PipelineState.Idle)
        advanceTimeBy(1_000)
        assertEquals(listOf("RunStarted", "CancelRequested"), events.names())

        advanceUntilIdle()
        assertEquals(listOf("RunStarted", "CancelRequested", "Cancelled"), events.names())
    }

    @Test
    fun `이미 끝난 명령을 중단해도 사건이 나오지 않는다`() = runTest {
        val observer = FakeScreenObserver(snapshot("쇼츠"))
        val orchestrator = build(
            FakePlanGenerator(listOf("""{"app":"유튜브","steps":[{"action":"wait","value":"1s"}]}""")),
            ScriptedExecutor(observer, emptyList()), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("1초 기다려")
        advanceUntilIdle()
        val before = events.size
        orchestrator.requestCancel()
        orchestrator.cancel()
        advanceUntilIdle()

        assertEquals(before, events.size)
    }

    @Test
    fun `명령마다 runId 가 다르고 번호는 이어서 커진다`() = runTest {
        val observer = FakeScreenObserver(snapshot("쇼츠"))
        val plan = """{"app":"유튜브","steps":[{"action":"wait","value":"1s"}]}"""
        val orchestrator = build(
            FakePlanGenerator(listOf(plan, plan)), ScriptedExecutor(observer, emptyList()), observer
        )
        val events = collect(orchestrator)

        orchestrator.submit("첫 명령")
        advanceUntilIdle()
        orchestrator.submit("두 번째 명령")
        advanceUntilIdle()

        val starts = events.filterIsInstance<PipelineEvent.RunStarted>()
        assertEquals(2, starts.size)
        assertTrue(starts[0].runId != starts[1].runId)
        assertTrue(starts[1].sequence > events.last { it.runId == starts[0].runId }.sequence)
    }
}
