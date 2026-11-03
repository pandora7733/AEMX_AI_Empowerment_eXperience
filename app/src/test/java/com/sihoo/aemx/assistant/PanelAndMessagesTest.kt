package com.sihoo.aemx.assistant

import com.sihoo.aemx.assistant.overlay.CapsuleTextFactory
import com.sihoo.aemx.assistant.overlay.PanelRows
import com.sihoo.aemx.assistant.UserMessages
import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PlannedStep
import com.sihoo.aemx.pipeline.StepStatus
import com.sihoo.aemx.pipeline.model.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAndMessagesTest {

    // ------------------------------------------------------------ 사용자 문장

    @Test
    fun `재분석과 같은 화면 반복은 사용자에게 이 화면에서 더 진행하지 못했다는 한 문장이다`() {
        assertEquals(UserMessages.GIVE_UP, UserMessages.forFailure("재분석을 3번 했는데도 풀리지 않아 멈춘다"))
        assertEquals(UserMessages.GIVE_UP, UserMessages.forFailure("같은 화면에서 2번 막혀 더 진행하지 않는다"))
        assertEquals(UserMessages.GIVE_UP, UserMessages.forFailure("step 을 30개 넘게 실행해 안전장치가 걸렸다"))
    }

    @Test
    fun `모델 오류는 요청을 이해하지 못했다고 바꾼다`() {
        assertEquals(UserMessages.UNDERSTAND_FAILED, UserMessages.forFailure("LLM 오류: 준비된 가짜 응답이 더 없다"))
        assertEquals(UserMessages.UNDERSTAND_FAILED, UserMessages.forFailure("클라우드 응답 오류 (HTTP 429)"))
    }

    @Test
    fun `접근성 서비스가 꺼졌으면 켜 달라고 안내한다`() {
        assertEquals(UserMessages.SERVICE_OFF, UserMessages.forFailure("접근성 서비스가 꺼져 있어요"))
    }

    @Test
    fun `이미 사용자 말로 쓴 안내는 그대로 두고 알 수 없는 개발 문장은 일반 안내로 바꾼다`() {
        assertEquals("앱 화면이 열리지 않았습니다. 확인해 주세요.", UserMessages.forFailure("앱 화면이 열리지 않았습니다. 확인해 주세요."))
        assertEquals(UserMessages.GENERIC, UserMessages.forFailure("대상을 찾지 못했다(검색창)"))
        assertEquals(UserMessages.GENERIC, UserMessages.forFailure(null))
        assertEquals(UserMessages.GENERIC, UserMessages.forFailure("  "))
    }

    @Test
    fun `캡슐의 오류 문장도 사용자용으로 바뀐다`() {
        val state = AssistantUiState(
            runId = "r1",
            sequence = 9,
            phase = AssistantPhase.Error,
            error = "재분석을 3번 했는데도 풀리지 않아 멈춘다",
        )

        assertEquals(UserMessages.GIVE_UP, CapsuleTextFactory.from(state).title)
    }

    // ------------------------------------------------------------ 패널 단계 줄

    @Test
    fun `단계 상태는 글자로도 보인다`() {
        assertEquals("완료", PanelRows.statusWord(StepStatus.Completed))
        assertEquals("진행 중", PanelRows.statusWord(StepStatus.InProgress))
        assertEquals("대기", PanelRows.statusWord(StepStatus.Pending))
        assertEquals("확인 필요", PanelRows.statusWord(StepStatus.Failed))
        assertEquals("취소", PanelRows.statusWord(StepStatus.Cancelled))
    }

    @Test
    fun `하는 중인 단계가 현재 줄이고 없으면 다음 대기 단계가 현재 줄이다`() {
        val running = AssistantUiState(
            runId = "r1",
            steps = listOf(
                AssistantStep("s0", "유튜브 열기", "유튜브를 열었어요", StepStatus.Completed),
                AssistantStep("s1", "검색", "검색을 마쳤어요", StepStatus.InProgress),
                AssistantStep("s2", "재생", "재생했어요", StepStatus.Pending),
            ),
        )
        val rows = PanelRows.from(running)
        assertEquals(listOf(false, true, false), rows.map { it.current })
        assertEquals("진행 중", rows[1].statusWord)

        val between = running.copy(steps = running.steps.map { if (it.id == "s1") it.copy(status = StepStatus.Completed) else it })
        assertEquals(listOf(false, false, true), PanelRows.from(between).map { it.current })
    }

    // ------------------------------------------------------------ 계속 의도

    @Test
    fun `직접 처리 대기에서 계속을 누르면 계속 의도가 나간다`() = runTest {
        val store = AssistantStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val got = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.continueRequests.collect { got += it } }

        store.post(PipelineEvent.RunStarted("r1", 1, "카톡으로 보내줘"))
        store.post(PipelineEvent.NeedsUser("r1", 2, "로그인이 필요해요"))
        advanceUntilIdle()
        store.requestContinuation()
        advanceUntilIdle()

        assertEquals(listOf("r1"), got)
        job.cancel()
    }

    @Test
    fun `직접 처리 대기가 아니면 계속 의도를 보내지 않는다`() = runTest {
        val store = AssistantStore(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val got = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.continueRequests.collect { got += it } }

        store.post(PipelineEvent.RunStarted("r1", 1, "유튜브 열어줘"))
        store.post(PipelineEvent.PlanValidated("r1", 2, 1, listOf(PlannedStep("p1-s0", ActionType.OPEN_APP, "유튜브", "유튜브"))))
        advanceUntilIdle()
        store.requestContinuation()
        advanceUntilIdle()

        assertTrue(got.isEmpty())
        assertFalse(store.state.value.phase == AssistantPhase.NeedsUser)
        job.cancel()
    }
}
