package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.fake.FakeActionExecutor
import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ExecutionResult
import com.sihoo.aemx.pipeline.port.PlanRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 파이프라인 전체 흐름 테스트.
 *
 * STT·LLM·실행모듈이 전부 가짜라 실기기 없이 돌아간다.
 * 여기서 검증하는 것은 "각 파트가 잘 동작하는가"가 아니라
 * **지휘자가 그것들을 올바른 순서로 엮고, 어긋났을 때 제대로 대응하는가**다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineOrchestratorTest {

    private val loginPlan =
        """{"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}, {"action": "tap", "target": "로그인"}]}"""

    private fun snapshot(vararg texts: String, pkg: String = "com.example.fake.aladin") =
        ScreenSnapshot(packageName = pkg, className = "Screen", texts = texts.toList())

    /** 실행할 때마다 정해진 화면으로 넘어가는 실행모듈. 실제 조작의 효과를 흉내 낸다. */
    private class ScriptedExecutor(
        private val observer: FakeScreenObserver,
        private val screensAfterEach: List<ScreenSnapshot?>
    ) : ActionExecutor {
        val performed = mutableListOf<ActionExecutor.Command>()

        override fun isReady() = true

        override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
            val next = screensAfterEach.getOrNull(performed.size)
            performed += command
            // null 이면 화면이 그대로다(=누른 게 아무 반응이 없었던 경우).
            if (next != null) observer.advanceTo(next)
            return ExecutionResult.Performed
        }
    }

    // ------------------------------------------------------------ 정상 흐름

    @Test
    fun `계획의 모든 step 을 실행하면 완료된다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈", pkg = "com.android.launcher"))
        val executor = ScriptedExecutor(
            observer,
            listOf(
                snapshot("알라딘", "로그인"),           // open_app 결과
                snapshot("아이디", "비밀번호")           // tap 결과
            )
        )
        val planner = FakePlanGenerator(listOf(loginPlan))
        val orchestrator = build(planner, executor, observer)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertTrue(
            "상태가 ${orchestrator.state.value} 였다",
            orchestrator.state.value is PipelineState.Finished
        )
        assertEquals(2, executor.performed.size)
        assertEquals(
            ActionExecutor.Command.LaunchApp("com.example.fake.aladin"),
            executor.performed[0]
        )
        assertEquals(ActionExecutor.Command.Tap("로그인"), executor.performed[1])
    }

    @Test
    fun `새 명령은 INITIAL 모드로 요청한다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("알라딘"), snapshot("아이디")))
        val planner = FakePlanGenerator(listOf(loginPlan))

        build(planner, executor, observer).submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertEquals(PlanRequest.Mode.INITIAL, planner.requests.first().mode)
    }

    // -------------------------------------------------------- 검색·영상 액션

    @Test
    fun `search 는 검색어를 target 에서 가져온다`() = runTest {
        // 검색어가 value 가 아니라 target 에 온다. 실행모듈이 그 형태로 받기 때문에
        // 여기서 잘못 읽으면 빈 검색어로 실행된다.
        val plan =
            """{"app":"유튜브","steps":[{"action":"search","target":"고양이 영상"},{"action":"play_first","target":"첫 번째 동영상"}]}"""

        val observer = FakeScreenObserver(snapshot("유튜브", "홈"))
        val executor = ScriptedExecutor(
            observer,
            listOf(snapshot("고양이 영상", "검색결과"), snapshot("재생 중", "고양이 영상"))
        )
        val orchestrator = build(FakePlanGenerator(listOf(plan)), executor, observer)

        orchestrator.submit("유튜브에서 고양이 영상 틀어줘")
        advanceUntilIdle()

        val search = executor.performed.filterIsInstance<ActionExecutor.Command.Search>().single()
        assertEquals("고양이 영상", search.query)
        assertEquals("유튜브", search.appName)
        assertTrue(
            "첫 영상 재생 명령이 이어져야 한다",
            executor.performed.any { it is ActionExecutor.Command.PlayFirst }
        )
    }

    @Test
    fun `next_video 는 대기 시간을 그대로 넘긴다`() = runTest {
        val plan =
            """{"app":"유튜브","steps":[{"action":"next_video","target":"다음 동영상","value":"10s"}]}"""

        val observer = FakeScreenObserver(snapshot("쇼츠", "첫 영상"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("쇼츠", "두 번째 영상")))
        val orchestrator = build(FakePlanGenerator(listOf(plan)), executor, observer)

        orchestrator.submit("10초 뒤 다음 영상")
        advanceUntilIdle()

        val next = executor.performed.filterIsInstance<ActionExecutor.Command.NextVideo>().single()
        assertEquals("10s", next.delay)
    }

    // ------------------------------------------------------------- 재분석

    @Test
    fun `화면이 안 바뀌면 재분석을 요청하고 새 계획으로 이어간다`() = runTest {
        val retryPlan =
            """{"app": "알라딘", "steps": [{"action": "tap", "target": "회원 로그인"}]}"""

        val observer = FakeScreenObserver(snapshot("알라딘", "홈"))
        val executor = ScriptedExecutor(
            observer,
            listOf(
                null,                               // 첫 tap: 아무 반응 없음
                snapshot("아이디", "비밀번호")        // 재분석 뒤의 tap: 성공
            )
        )
        val planner = FakePlanGenerator(
            listOf(
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""",
                retryPlan
            )
        )
        val orchestrator = build(planner, executor, observer)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is PipelineState.Finished)
        assertEquals(2, planner.requests.size)

        val replan = planner.requests[1]
        assertEquals(PlanRequest.Mode.REPLAN, replan.mode)
        assertEquals("알라딘에서 로그인하고 싶어", replan.utterance)
        // 재분석 요청에는 현재 화면과 지금까지의 기록이 함께 실려야 한다.
        assertTrue(replan.screen != null)
        assertEquals(1, replan.history.size)
        assertTrue(replan.reason!!.contains("바뀌지 않았다"))

        assertEquals(ActionExecutor.Command.Tap("회원 로그인"), executor.performed[1])
    }

    @Test
    fun `로그인 실패 팝업을 만나면 재분석한다`() = runTest {
        val observer = FakeScreenObserver(snapshot("아이디", "비밀번호"))
        val executor = ScriptedExecutor(
            observer,
            listOf(
                snapshot("비밀번호가 올바르지 않습니다", "확인"),   // 로그인 눌렀더니 실패 팝업
                snapshot("아이디", "비밀번호")                    // 확인 누르고 복귀
            )
        )
        val planner = FakePlanGenerator(
            listOf(
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""",
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "확인"}]}"""
            )
        )
        val orchestrator = build(planner, executor, observer)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertEquals(2, planner.requests.size)
        assertTrue(planner.requests[1].reason!!.contains("로그인이 실패"))
    }

    @Test
    fun `캡차 화면을 만나면 자동 진행을 멈추고 사람에게 넘긴다`() = runTest {
        val observer = FakeScreenObserver(snapshot("아이디", "비밀번호"))
        val executor = ScriptedExecutor(
            observer,
            listOf(snapshot("보안문자를 입력해 주세요", "확인"))
        )
        val planner = FakePlanGenerator(
            listOf("""{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""")
        )
        val orchestrator = build(planner, executor, observer)

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is PipelineState.NeedsUser)
        // 캡차는 재분석해서 뚫으려 들면 안 된다.
        assertEquals(1, planner.requests.size)
    }

    // ------------------------------------------------------- 무한루프 방지

    @Test
    fun `같은 화면에서 막히면 안전장치가 멈춘다`() = runTest {
        val stuckPlan =
            """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}"""

        val observer = FakeScreenObserver(snapshot("알라딘", "홈"))
        // 몇 번을 눌러도 화면이 그대로다.
        val executor = ScriptedExecutor(observer, emptyList())
        val planner = FakePlanGenerator(List(10) { stuckPlan })

        val orchestrator = build(
            planner, executor, observer,
            config = PipelineConfig(
                quietPeriodMs = 10,
                maxReplans = 10,
                sameScreenReplanLimit = 2
            )
        )

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("상태가 $state 였다", state is PipelineState.Failed)
        assertTrue((state as PipelineState.Failed).reason.contains("같은 화면"))
        // 재분석 상한(10)이 아니라 같은-화면 상한(2)에서 멈춰야 한다.
        assertTrue("재분석이 너무 많이 돌았다: ${planner.requests.size}", planner.requests.size <= 4)
    }

    @Test
    fun `재분석 횟수 상한을 넘으면 포기한다`() = runTest {
        // 매번 다른 화면으로 바뀌지만 기대는 계속 어긋나는 경우.
        val observer = FakeScreenObserver(snapshot("화면0"))
        val executor = ScriptedExecutor(
            observer,
            (1..10).map { snapshot("화면$it", "로그아웃") }   // 계속 ALREADY_LOGGED_IN 으로 걸린다
        )
        val planner = FakePlanGenerator(
            List(10) { """{"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}""" }
        )

        val orchestrator = build(
            planner, executor, observer,
            config = PipelineConfig(quietPeriodMs = 10, maxReplans = 2, sameScreenReplanLimit = 99)
        )

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("상태가 $state 였다", state is PipelineState.Failed)
        assertTrue((state as PipelineState.Failed).reason.contains("재분석"))
    }

    // --------------------------------------------------------- JSON 복구

    @Test
    fun `JSON 이 깨지면 형식만 다시 요청한다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("알라딘", "로그인")))
        val planner = FakePlanGenerator(
            listOf(
                "무슨 말씀인지 잘 모르겠어요.",                                   // JSON 없음
                """{"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}]}"""
            )
        )
        val orchestrator = build(planner, executor, observer)

        orchestrator.submit("알라딘 열어줘")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is PipelineState.Finished)
        assertEquals(2, planner.requests.size)
        assertEquals(PlanRequest.Mode.REPAIR_JSON, planner.requests[1].mode)
        assertTrue(planner.requests[1].previousRaw!!.contains("모르겠어요"))
    }

    @Test
    fun `복구 요청까지 실패하면 포기한다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val planner = FakePlanGenerator(listOf("설명만 합니다", "여전히 설명만 합니다"))

        val orchestrator = build(planner, FakeActionExecutor(), observer)
        orchestrator.submit("알라딘 열어줘")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is PipelineState.Failed)
    }

    // ------------------------------------------------------------ 선행 조건

    @Test
    fun `접근성 서비스가 꺼져 있으면 설정이 필요하다고 알린다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val orchestrator = build(
            FakePlanGenerator(listOf(loginPlan)),
            FakeActionExecutor(ready = false),
            observer
        )

        orchestrator.submit("알라딘에서 로그인하고 싶어")
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is PipelineState.NeedsSetup)
    }

    @Test
    fun `처리 중에는 새 명령을 받지 않는다`() = runTest {
        val observer = FakeScreenObserver(snapshot("홈"))
        val executor = ScriptedExecutor(observer, listOf(snapshot("알라딘")))
        val orchestrator = build(
            FakePlanGenerator(listOf(loginPlan), latencyMs = 1_000),
            executor,
            observer
        )

        assertTrue(orchestrator.submit("첫 명령"))
        assertTrue("처리 중에는 거절해야 한다", !orchestrator.submit("두 번째 명령"))
        advanceUntilIdle()
    }

    // ---------------------------------------------------------------- 도우미

    /**
     * 테스트용 오케스트레이터.
     *
     * scope 로 [kotlinx.coroutines.test.TestScope] 자신을 넘긴다. `backgroundScope` 를 쓰면
     * `advanceUntilIdle()` 이 배경 작업을 기다리지 않아(배경 작업은 idle 판정에서 제외된다)
     * 파이프라인이 한 발짝도 나아가지 않은 채 단언에 도달한다.
     */
    private fun kotlinx.coroutines.test.TestScope.build(
        planner: FakePlanGenerator,
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
}
