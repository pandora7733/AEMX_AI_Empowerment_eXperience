package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayOrdinalGuardTest {
    private fun plan(vararg steps: ActionStep) = ActionPlan("유튜브", steps.toList())
    private fun step(index: Int, action: ActionType, target: String? = null, value: String? = null) =
        ActionStep(index, action, target, value)

    @Test
    fun `두 번째를 play_first 두 번으로 낸 계획은 하나로 합치고 순번을 채운다`() {
        val plan = plan(
            step(0, ActionType.OPEN_APP, "유튜브"),
            step(1, ActionType.SEARCH, "페이커"),
            step(2, ActionType.PLAY_FIRST, "첫 번째 동영상"),
            step(3, ActionType.PLAY_FIRST, "첫 번째 동영상"),
        )

        val fixed = PlayOrdinalGuard.apply("유튜브에서 페이커 검색하고 두번째 영상 틀어줘", plan)

        assertEquals(listOf(ActionType.OPEN_APP, ActionType.SEARCH, ActionType.PLAY_FIRST), fixed.steps.map { it.action })
        assertEquals("2", fixed.steps.last().value)
        assertEquals(listOf(0, 1, 2), fixed.steps.map { it.index })
    }

    @Test
    fun `play_first 하나에 value 가 비었으면 순번을 채운다`() {
        val fixed = PlayOrdinalGuard.apply(
            "아이유 노래 3번째 곡 틀어줘",
            plan(step(0, ActionType.SEARCH, "아이유 노래"), step(1, ActionType.PLAY_FIRST, "첫 번째 동영상")),
        )
        assertEquals("3", fixed.steps.last().value)
    }

    @Test
    fun `모델이 다른 순번을 적었어도 사용자가 말한 순번이 이긴다`() {
        val fixed = PlayOrdinalGuard.apply(
            "셋째 영상 틀어줘",
            plan(step(0, ActionType.PLAY_FIRST, "첫 번째 동영상", "2")),
        )
        assertEquals("3", fixed.steps.single().value)
    }

    @Test
    fun `순번을 말하지 않았으면 계획을 그대로 둔다`() {
        val plan = plan(step(0, ActionType.SEARCH, "고양이"), step(1, ActionType.PLAY_FIRST, "첫 번째 동영상"))
        assertSame(plan, PlayOrdinalGuard.apply("유튜브에서 고양이 영상 틀어줘", plan))
        assertSame(plan, PlayOrdinalGuard.apply("유튜브에서 2024 검색하고 틀어줘", plan))
        assertSame(plan, PlayOrdinalGuard.apply("첫 번째 영상 틀어줘", plan))
    }

    @Test
    fun `play_first 가 없으면 그대로 둔다`() {
        val plan = plan(step(0, ActionType.SEARCH, "고양이"))
        assertSame(plan, PlayOrdinalGuard.apply("두 번째 영상 틀어줘", plan))
    }
}
