package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PlannedStep
import com.sihoo.aemx.pipeline.StepStatus
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantReducerTest {

    private val run = "r1"
    private var seq = 0L

    private fun next() = ++seq

    private fun started(id: String = run, text: String = "유튜브 열어줘") =
        PipelineEvent.RunStarted(id, next(), text)

    private fun plan(number: Int = 1, vararg steps: Pair<ActionType, String?>, id: String = run) =
        PipelineEvent.PlanValidated(
            id, next(), number,
            steps.mapIndexed { i, (action, target) -> PlannedStep("p$number-s$i", action, target, "유튜브") }
        )

    private fun step(stepId: String, status: StepStatus, reason: String? = null, id: String = run) =
        PipelineEvent.StepChanged(id, next(), stepId, status, reason)

    private fun verified(evidence: String?, id: String = run) = PipelineEvent.ResultVerified(id, next(), evidence)

    private fun fold(vararg events: PipelineEvent, from: AssistantUiState = AssistantUiState()) =
        events.fold(from) { state, event -> AssistantReducer.reduce(state, event) }

    /** 사건은 만든 순서대로 번호가 붙으므로, 먼저 상태를 만든 뒤에 사건을 만든다. */
    private fun afterRun(make: () -> PipelineEvent): AssistantUiState {
        val base = runningOneStep()
        return AssistantReducer.reduce(base, make())
    }

    private fun afterStart(make: () -> PipelineEvent): AssistantUiState {
        val base = fold(started())
        return AssistantReducer.reduce(base, make())
    }

    /** 열기 한 단계를 끝까지 실행한 상태(결과 직전). */
    private fun runningOneStep(): AssistantUiState = fold(
        started(),
        plan(1, ActionType.OPEN_APP to "유튜브"),
        step("p1-s0", StepStatus.InProgress),
        step("p1-s0", StepStatus.Completed),
    )

    // ------------------------------------------------------------ 흐름

    @Test
    fun `명령을 받으면 이해 중이고 계획을 받으면 단계가 생긴다`() {
        val started = fold(started())
        assertEquals(AssistantPhase.Understanding, started.phase)
        assertEquals("유튜브 열어줘", started.transcript)

        val planned = fold(plan(1, ActionType.OPEN_APP to "유튜브", ActionType.SEARCH to "고양이"), from = started)
        assertEquals(AssistantPhase.Planning, planned.phase)
        assertEquals(listOf("유튜브 열기", "'고양이' 검색"), planned.steps.map { it.label })
        assertTrue(planned.steps.all { it.status == StepStatus.Pending })
    }

    @Test
    fun `첫 단계가 시작하면 실행 중이다`() {
        val state = fold(started(), plan(1, ActionType.OPEN_APP to "유튜브"), step("p1-s0", StepStatus.InProgress))
        assertEquals(AssistantPhase.Executing, state.phase)
        assertEquals(StepStatus.InProgress, state.steps.single().status)
    }

    @Test
    fun `알 수 없는 stepId 는 무시한다`() {
        val before = fold(started(), plan(1, ActionType.OPEN_APP to "유튜브"))
        val after = AssistantReducer.reduce(before, step("p9-s9", StepStatus.Completed))
        assertEquals(before, after)
    }

    // ------------------------------------------------- 오래된 사건 걸러내기

    @Test
    fun `이미 본 번호 이하의 사건은 무시한다`() {
        val state = runningOneStep()
        val stale = PipelineEvent.StepChanged(run, state.sequence, "p1-s0", StepStatus.Failed, "늦게 온 알림")
        assertEquals(state, AssistantReducer.reduce(state, stale))

        val older = PipelineEvent.StepChanged(run, state.sequence - 1, "p1-s0", StepStatus.Failed, "더 오래된 알림")
        assertEquals(state, AssistantReducer.reduce(state, older))
    }

    @Test
    fun `다른 명령의 사건은 무시한다`() {
        val state = runningOneStep()
        val other = PipelineEvent.Failed("다른것", next(), "지난 명령의 오류")
        assertEquals(state, AssistantReducer.reduce(state, other))
    }

    @Test
    fun `새 명령이 번호가 더 크면 이전 명령을 대신한다`() {
        val old = runningOneStep()
        val fresh = AssistantReducer.reduce(old, started(id = "r2", text = "새 명령"))
        assertEquals("r2", fresh.runId)
        assertEquals(AssistantPhase.Understanding, fresh.phase)
        assertTrue(fresh.steps.isEmpty())
        assertEquals("새 명령", fresh.transcript)
    }

    @Test
    fun `번호가 작은 새 명령 시작은 받지 않는다`() {
        val old = runningOneStep()
        val stale = PipelineEvent.RunStarted("r2", old.sequence - 1, "옛날 명령")
        assertEquals(old, AssistantReducer.reduce(old, stale))
    }

    @Test
    fun `끝난 명령은 늦게 온 사건으로 바뀌지 않는다`() {
        val done = afterRun { verified("앞 화면이 기대한 앱임") }
        assertEquals(AssistantPhase.Success, done.phase)
        val late = AssistantReducer.reduce(done, step("p1-s0", StepStatus.Failed, "늦은 실패"))
        assertEquals(done, late)
    }

    // ------------------------------------------------ 성공과 전달

    @Test
    fun `증거가 있으면 성공이다`() {
        val state = afterRun { verified("앞 화면이 기대한 앱임") }
        assertEquals(AssistantPhase.Success, state.phase)
        assertTrue(state.mayShowSuccess)
        assertEquals("유튜브를 열었어요", state.result?.summary)
        assertEquals("앞 화면이 기대한 앱임", state.result?.evidence)
    }

    @Test
    fun `증거가 없으면 성공이 아니라 전달이다`() {
        for (evidence in listOf(null, "", "   ")) {
            val state = afterRun { verified(evidence) }
            assertEquals("evidence=$evidence", AssistantPhase.Delivered, state.phase)
            assertFalse(state.mayShowSuccess)
            assertNull(state.result?.evidence)
        }
    }

    @Test
    fun `끝나지 않은 단계가 있으면 결과를 받아들이지 않는다`() {
        val state = fold(
            started(), plan(1, ActionType.OPEN_APP to "유튜브", ActionType.SEARCH to "고양이"),
            step("p1-s0", StepStatus.InProgress), step("p1-s0", StepStatus.Completed),
            step("p1-s1", StepStatus.InProgress),
        )
        val after = AssistantReducer.reduce(state, verified("증거는 있지만 일찍 왔다"))
        assertEquals(AssistantPhase.Executing, after.phase)
        assertNull(after.result)
    }

    @Test
    fun `실행 중이 아닐 때 온 결과는 받아들이지 않는다`() {
        val state = fold(started())
        assertEquals(state, AssistantReducer.reduce(state, verified("증거")))
    }

    // ------------------------------------------------ 재분석 실패 취소

    @Test
    fun `재분석하면 끝낸 단계는 남고 나머지는 새 계획으로 바뀐다`() {
        val state = fold(
            started(),
            plan(1, ActionType.OPEN_APP to "유튜브", ActionType.SEARCH to "고양이"),
            step("p1-s0", StepStatus.InProgress), step("p1-s0", StepStatus.Completed),
            step("p1-s1", StepStatus.InProgress), step("p1-s1", StepStatus.Failed, "화면이 안 바뀜"),
            PipelineEvent.Replanning(run, next(), 1, "화면이 안 바뀜"),
        )
        assertEquals(AssistantPhase.Replanning, state.phase)
        assertEquals(1, state.replanCount)

        val replanned = fold(plan(2, ActionType.SEARCH to "고양이 영상"), from = state)
        assertEquals(AssistantPhase.Replanning, replanned.phase)
        assertEquals(listOf("p1-s0", "p2-s0"), replanned.steps.map { it.id })
        assertEquals(StepStatus.Completed, replanned.steps[0].status)

        val running = fold(step("p2-s0", StepStatus.InProgress), from = replanned)
        assertEquals(AssistantPhase.Executing, running.phase)
    }

    @Test
    fun `사람이 해야 하면 이유를 담고 펼친다`() {
        val state = fold(
            started(), plan(1, ActionType.OPEN_APP to "네이버"), step("p1-s0", StepStatus.InProgress),
            PipelineEvent.NeedsUser(run, next(), "로그인이 필요해요"),
        )
        assertEquals(AssistantPhase.NeedsUser, state.phase)
        assertEquals("로그인이 필요해요", state.userActionPrompt)
        assertTrue(state.expanded)
    }

    @Test
    fun `실패하면 오류다`() {
        val state = afterStart { PipelineEvent.Failed(run, next(), "LLM 오류") }
        assertEquals(AssistantPhase.Error, state.phase)
        assertEquals("LLM 오류", state.error)
    }

    // ------------------------------------------------ 중단

    @Test
    fun `중단 확인 전에는 취소됨이 될 수 없다`() {
        val state = runningOneStep()
        // 중단을 요청한 적이 없는데 Cancelled 가 오면 받지 않는다.
        assertEquals(state, AssistantReducer.reduce(state, PipelineEvent.Cancelled(run, next(), setOf("p1-s0"))))

        // 요청만 했을 때는 중단하는 중일 뿐 취소됨이 아니다.
        val requested = AssistantReducer.reduce(state, PipelineEvent.CancelRequested(run, next()))
        assertTrue(requested.cancellationRequested)
        assertEquals(AssistantPhase.Executing, requested.phase)
    }

    @Test
    fun `중단을 요청하면 다른 사건은 받지 않고 확인되면 취소됨이다`() {
        val running = fold(
            started(), plan(1, ActionType.OPEN_APP to "유튜브", ActionType.SEARCH to "고양이"),
            step("p1-s0", StepStatus.InProgress), step("p1-s0", StepStatus.Completed),
            step("p1-s1", StepStatus.InProgress),
        )
        val requested = AssistantReducer.reduce(running, PipelineEvent.CancelRequested(run, next()))

        val ignored = AssistantReducer.reduce(requested, step("p1-s1", StepStatus.Completed))
        assertEquals(requested, ignored)
        assertEquals(requested, AssistantReducer.reduce(requested, verified("증거")))

        val cancelled = AssistantReducer.reduce(requested, PipelineEvent.Cancelled(run, next(), setOf("p1-s0")))
        assertEquals(AssistantPhase.Cancelled, cancelled.phase)
        assertEquals(listOf(StepStatus.Completed, StepStatus.Cancelled), cancelled.steps.map { it.status })
        assertFalse(cancelled.mayShowSuccess)
    }

    @Test
    fun `추론 중에 중단하면 계획 없이 취소됨이 된다`() {
        val state = fold(
            started(),
            PipelineEvent.CancelRequested(run, next()),
            PipelineEvent.Cancelled(run, next(), emptySet()),
        )
        assertEquals(AssistantPhase.Cancelled, state.phase)
        assertTrue(state.steps.isEmpty())
    }

    @Test
    fun `중단을 확인하지 못하면 오류다`() {
        val state = fold(
            started(),
            PipelineEvent.CancelRequested(run, next()),
            PipelineEvent.Failed(run, next(), "중단을 확인하지 못했어요"),
        )
        assertEquals(AssistantPhase.Error, state.phase)
        assertEquals("중단을 확인하지 못했어요", state.error)
    }

    @Test
    fun `끝난 명령에 온 중단 요청은 무시한다`() {
        val done = afterRun { verified("증거") }
        assertEquals(done, AssistantReducer.reduce(done, PipelineEvent.CancelRequested(run, next())))
    }
}
