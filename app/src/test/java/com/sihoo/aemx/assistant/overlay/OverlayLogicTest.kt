package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantReducer
import com.sihoo.aemx.assistant.AssistantStep
import com.sihoo.aemx.assistant.AssistantUiState
import com.sihoo.aemx.assistant.VerifiedResult
import com.sihoo.aemx.pipeline.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLogicTest {

    private fun step(id: String, status: StepStatus, progress: String = "진행 $id") =
        AssistantStep(id, "라벨 $id", "끝 $id", status, progressText = progress)

    private fun state(
        phase: AssistantPhase,
        steps: List<AssistantStep> = emptyList(),
        cancellationRequested: Boolean = false,
        error: String? = null,
        result: VerifiedResult? = null,
        runId: String = "r1"
    ) = AssistantUiState(
        runId = runId, phase = phase, steps = steps,
        cancellationRequested = cancellationRequested, error = error, result = result
    )

    // ------------------------------------------------------------ 도킹

    @Test
    fun `직접 처리만 위에 두고 나머지는 아래에 둔다`() {
        for (phase in AssistantPhase.entries) {
            val expected = if (phase == AssistantPhase.NeedsUser) Dock.TOP else Dock.BOTTOM
            assertEquals("$phase", expected, DockPolicy.dockFor(phase))
        }
    }

    // ------------------------------------------------------------ 인셋으로 위치 계산

    @Test
    fun `아래 도킹은 내비게이션 바 위에 띄운다`() {
        val insets = OverlayInsets(top = 80, bottom = 120)
        assertEquals(128, OverlayGeometry.initialOffset(Dock.BOTTOM, insets, marginPx = 8))
    }

    @Test
    fun `키보드가 떠 있으면 키보드 위에 띄운다`() {
        val insets = OverlayInsets(top = 80, bottom = 120, ime = 900)
        assertEquals(908, OverlayGeometry.initialOffset(Dock.BOTTOM, insets, marginPx = 8))
    }

    @Test
    fun `키보드가 내비게이션 바보다 낮으면 내비게이션 바를 따른다`() {
        val insets = OverlayInsets(bottom = 120, ime = 50)
        assertEquals(128, OverlayGeometry.initialOffset(Dock.BOTTOM, insets, marginPx = 8))
    }

    @Test
    fun `위 도킹은 상태 표시줄과 컷아웃 아래에 띄운다`() {
        val insets = OverlayInsets(top = 110, bottom = 120, ime = 900)
        assertEquals(118, OverlayGeometry.initialOffset(Dock.TOP, insets, marginPx = 8))
    }

    @Test
    fun `있어야 할 가장자리 좌표를 낸다`() {
        val insets = OverlayInsets(top = 80, bottom = 120)
        assertEquals(2400 - 120 - 8, OverlayGeometry.desiredEdge(Dock.BOTTOM, 2400, insets, 8))
        assertEquals(88, OverlayGeometry.desiredEdge(Dock.TOP, 2400, insets, 8))
    }

    @Test
    fun `이미 맞는 위치는 고치지 않는다`() {
        assertEquals(128, OverlayGeometry.correctedOffset(Dock.BOTTOM, 128, actualEdge = 2272, desiredEdge = 2272))
        assertEquals(128, OverlayGeometry.correctedOffset(Dock.BOTTOM, 128, actualEdge = 2273, desiredEdge = 2272))
    }

    @Test
    fun `창이 인셋을 이미 피해 있어 너무 높게 그려지면 띄우는 거리를 줄인다`() {
        // 아래변이 목표(2272)보다 128 위에 있다 = 인셋을 두 번 뺐다.
        assertEquals(0, OverlayGeometry.correctedOffset(Dock.BOTTOM, 128, actualEdge = 2144, desiredEdge = 2272))
    }

    @Test
    fun `아래변이 목표보다 낮으면 띄우는 거리를 늘린다`() {
        assertEquals(168, OverlayGeometry.correctedOffset(Dock.BOTTOM, 128, actualEdge = 2312, desiredEdge = 2272))
    }

    @Test
    fun `위 도킹은 윗변이 목표보다 높으면 띄우는 거리를 늘린다`() {
        assertEquals(150, OverlayGeometry.correctedOffset(Dock.TOP, 118, actualEdge = 56, desiredEdge = 88))
        assertEquals(100, OverlayGeometry.correctedOffset(Dock.TOP, 118, actualEdge = 106, desiredEdge = 88))
    }

    // ------------------------------------------------------------ 캡슐 문구

    @Test
    fun `대기 중에는 캡슐을 숨긴다`() {
        assertFalse(CapsuleTextFactory.from(AssistantUiState()).visible)
    }

    @Test
    fun `실행 중에는 지금 하는 단계와 단계 번호를 보인다`() {
        val text = CapsuleTextFactory.from(
            state(
                AssistantPhase.Executing,
                listOf(step("a", StepStatus.Completed), step("b", StepStatus.InProgress, "'고양이' 검색 중…"), step("c", StepStatus.Pending))
            )
        )
        assertTrue(text.visible)
        assertEquals("'고양이' 검색 중…", text.title)
        assertEquals("단계 2/3", text.subtitle)
        assertTrue(text.showStop)
        assertTrue(text.stopEnabled)
    }

    @Test
    fun `이해하고 계획하고 다시 확인하는 동안의 문구`() {
        assertEquals(CapsuleTextFactory.UNDERSTANDING, CapsuleTextFactory.from(state(AssistantPhase.Understanding)).title)
        assertEquals(CapsuleTextFactory.PLANNING, CapsuleTextFactory.from(state(AssistantPhase.Planning)).title)
        assertEquals(CapsuleTextFactory.REPLANNING, CapsuleTextFactory.from(state(AssistantPhase.Replanning)).title)
    }

    @Test
    fun `중단을 누른 뒤 확인 전에는 중단하는 중이고 버튼을 막는다`() {
        val text = CapsuleTextFactory.from(
            state(AssistantPhase.Executing, listOf(step("a", StepStatus.InProgress)), cancellationRequested = true)
        )
        assertEquals("중단하는 중…", text.title)
        assertEquals("", text.subtitle)
        assertTrue(text.showStop)
        assertFalse(text.stopEnabled)
    }

    @Test
    fun `직접 처리는 기다린다고 말하고 버튼이 있다`() {
        val text = CapsuleTextFactory.from(state(AssistantPhase.NeedsUser))
        assertEquals(CapsuleTextFactory.NEEDS_USER, text.title)
        assertEquals(CapsuleTextFactory.NEEDS_USER_SUB, text.subtitle)
        assertTrue(text.showStop)
    }

    @Test
    fun `끝난 상태에는 중단 버튼이 없다`() {
        val success = CapsuleTextFactory.from(
            state(AssistantPhase.Success, result = VerifiedResult("r1", "유튜브를 열었어요", "증거"))
        )
        assertEquals("유튜브를 열었어요", success.title)
        assertEquals(CapsuleTextFactory.VERIFIED_SUB, success.subtitle)
        assertFalse(success.showStop)

        val delivered = CapsuleTextFactory.from(state(AssistantPhase.Delivered))
        assertEquals(AssistantReducer.DELIVERED_MESSAGE, delivered.title)
        assertFalse(delivered.title.contains("결과 확인됨"))
        assertEquals("", delivered.subtitle)
        assertFalse(delivered.showStop)

        val error = CapsuleTextFactory.from(state(AssistantPhase.Error, error = "LLM 오류"))
        assertEquals("LLM 오류", error.title)
        assertFalse(error.showStop)
        assertEquals(CapsuleTextFactory.ERROR, CapsuleTextFactory.from(state(AssistantPhase.Error)).title)

        val cancelled = CapsuleTextFactory.from(state(AssistantPhase.Cancelled))
        assertEquals(CapsuleTextFactory.CANCELLED, cancelled.title)
        assertFalse(cancelled.showStop)
    }

    @Test
    fun `중단 요청 표시가 남은 끝난 상태는 중단하는 중이 아니다`() {
        // 오류로 끝난 명령이 중단 요청 표시를 갖고 있어도(중단 확인 실패) 오류 문구를 보인다.
        val text = CapsuleTextFactory.from(state(AssistantPhase.Error, cancellationRequested = true, error = "중단을 확인하지 못했어요"))
        assertEquals("중단을 확인하지 못했어요", text.title)
    }

    @Test
    fun `단계 번호는 전체 수를 넘지 않는다`() {
        val text = CapsuleTextFactory.from(
            state(AssistantPhase.Executing, listOf(step("a", StepStatus.Completed), step("b", StepStatus.Completed)))
        )
        assertEquals("단계 2/2", text.subtitle)
    }

    // ------------------------------------------------------------ 보일지

    @Test
    fun `끄거나 숨기기로 한 명령이면 보이지 않는다`() {
        val running = state(AssistantPhase.Executing)
        assertTrue(CapsuleVisibility.shouldShow(running, enabled = true, dismissedRunId = null))
        assertFalse(CapsuleVisibility.shouldShow(running, enabled = false, dismissedRunId = null))
        assertFalse(CapsuleVisibility.shouldShow(running, enabled = true, dismissedRunId = "r1"))
        // 다음 명령은 다시 보인다.
        assertTrue(CapsuleVisibility.shouldShow(state(AssistantPhase.Executing, runId = "r2"), true, "r1"))
        assertFalse(CapsuleVisibility.shouldShow(AssistantUiState(), true, null))
    }

    @Test
    fun `성공과 전달만 저절로 사라진다`() {
        for (phase in AssistantPhase.entries) {
            val expected = phase == AssistantPhase.Success || phase == AssistantPhase.Delivered
            assertEquals("$phase", expected, CapsuleVisibility.autoHides(phase))
        }
    }
}
