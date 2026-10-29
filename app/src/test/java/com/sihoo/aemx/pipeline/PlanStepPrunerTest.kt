package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlanStepPrunerTest {

    private val directApps = setOf("네이버", "구글", "유튜브", "유튜브 뮤직")
    private val direct: (String) -> Boolean = { it in directApps }
    private val message: (String) -> Boolean = PlanStepPruner::isMessageApp

    private fun plan(app: String, vararg steps: Pair<ActionType, String?>) = ActionPlan(
        app = app,
        steps = steps.mapIndexed { i, (action, target) -> ActionStep(i, action, target) },
    )

    private fun prune(p: ActionPlan) = PlanStepPruner.prune(p, direct, message)

    // ------------------------------------------------------------ 검색 앞의 열기

    @Test
    fun `직접 검색 앱이면 앱 열기를 빼고 검색만 남긴다`() {
        val pruned = prune(plan("네이버", ActionType.OPEN_APP to "네이버", ActionType.SEARCH to "서울 날씨"))

        assertEquals(listOf(ActionType.SEARCH), pruned.steps.map { it.action })
        assertEquals("서울 날씨", pruned.steps[0].target)
        assertEquals(0, pruned.steps[0].index)
    }

    @Test
    fun `열기 대상이 비어 있으면 계획의 앱으로 보고 뺀다`() {
        val pruned = prune(
            plan("유튜브", ActionType.OPEN_APP to null, ActionType.SEARCH to "고양이 영상", ActionType.PLAY_FIRST to "첫 번째 동영상"),
        )

        assertEquals(listOf(ActionType.SEARCH, ActionType.PLAY_FIRST), pruned.steps.map { it.action })
        assertEquals(listOf(0, 1), pruned.steps.map { it.index })
    }

    @Test
    fun `직접 검색이 안 되는 앱이면 검색 앞의 열기를 그대로 둔다`() {
        val original = plan("알라딘", ActionType.OPEN_APP to "알라딘", ActionType.SEARCH to "해리포터")

        assertSame(original, prune(original))
    }

    @Test
    fun `열기 대상이 계획의 앱과 다르면 그대로 둔다`() {
        val original = plan("네이버", ActionType.OPEN_APP to "구글", ActionType.SEARCH to "서울 날씨")

        assertSame(original, prune(original))
    }

    @Test
    fun `열기 뒤에 검색이 바로 오지 않으면 그대로 둔다`() {
        val original = plan("유튜브", ActionType.OPEN_APP to "유튜브", ActionType.SCROLL to "down", ActionType.SEARCH to "고양이")

        assertSame(original, prune(original))
    }

    // ------------------------------------------------------------ 보내기 앞의 열기

    @Test
    fun `카카오톡 열기 뒤에 보내기가 오면 열기를 뺀다`() {
        val pruned = prune(plan("카카오톡", ActionType.OPEN_APP to "카카오톡", ActionType.SEND_MESSAGE to "배재준"))

        assertEquals(listOf(ActionType.SEND_MESSAGE), pruned.steps.map { it.action })
        assertEquals("배재준", pruned.steps[0].target)
    }

    @Test
    fun `문자 앱 열기 뒤에 보내기가 와도 열기를 뺀다`() {
        val pruned = prune(plan("문자", ActionType.OPEN_APP to "메시지", ActionType.SEND_MESSAGE to "엄마"))

        assertEquals(listOf(ActionType.SEND_MESSAGE), pruned.steps.map { it.action })
    }

    @Test
    fun `메시지 앱이 아닌 앱을 열고 보내기로 가면 열기를 그대로 둔다`() {
        val original = plan("카카오톡", ActionType.OPEN_APP to "알라딘", ActionType.SEND_MESSAGE to "배재준")

        assertSame(original, prune(original))
    }

    @Test
    fun `보내기 단계만 있으면 그대로 둔다`() {
        val original = plan("카카오톡", ActionType.SEND_MESSAGE to "배재준")

        assertSame(original, prune(original))
    }

    // ------------------------------------------------------------ 기본 후처리

    @Test
    fun `기본 인자로도 같은 규칙을 쓴다`() {
        val pruned = PlanStepPruner.prune(plan("네이버", ActionType.OPEN_APP to "네이버", ActionType.SEARCH to "날씨"))

        assertEquals(listOf(ActionType.SEARCH), pruned.steps.map { it.action })
    }
}
