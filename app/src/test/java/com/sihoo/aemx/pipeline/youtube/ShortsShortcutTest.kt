package com.sihoo.aemx.pipeline.youtube

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShortsShortcutTest {

    private fun plan(vararg steps: ActionStep) = ActionPlan("유튜브", steps.toList())

    @Test fun `쇼츠 말한 명령의 앱 열기와 쇼츠 탭은 쇼츠 열기 하나로 바꾼다`() {
        val original = plan(
            ActionStep(0, ActionType.OPEN_APP, "유튜브"),
            ActionStep(1, ActionType.TAP, "Shorts"),
        )

        val rewritten = ShortsShortcut.rewrite(original, "유튜브 쇼츠 보여줘")

        assertEquals(listOf(ActionType.OPEN_SHORTS), rewritten.steps.map { it.action })
        assertEquals("유튜브", rewritten.app)
    }

    @Test fun `쇼츠를 말해도 검색이나 재생이 섞이면 그대로 둔다`() {
        val original = plan(
            ActionStep(0, ActionType.SEARCH, "고양이"),
            ActionStep(1, ActionType.PLAY_FIRST, "1"),
        )

        assertSame(original, ShortsShortcut.rewrite(original, "유튜브에서 쇼츠 고양이 검색해줘"))
    }

    @Test fun `쇼츠를 말하지 않았으면 그대로 둔다`() {
        val original = plan(ActionStep(0, ActionType.OPEN_APP, "유튜브"), ActionStep(1, ActionType.TAP, "Shorts"))

        assertSame(original, ShortsShortcut.rewrite(original, "유튜브 열어줘"))
    }

    @Test fun `쇼츠가 아닌 버튼을 누르는 계획은 그대로 둔다`() {
        val original = plan(ActionStep(0, ActionType.OPEN_APP, "유튜브"), ActionStep(1, ActionType.TAP, "구독"))

        assertSame(original, ShortsShortcut.rewrite(original, "유튜브 쇼츠 보여줘"))
    }

    @Test fun `유튜브가 아닌 앱의 계획은 그대로 둔다`() {
        val original = ActionPlan("인스타그램", listOf(ActionStep(0, ActionType.OPEN_APP, "인스타그램"), ActionStep(1, ActionType.TAP, "릴스")))

        assertSame(original, ShortsShortcut.rewrite(original, "인스타그램 쇼츠 보여줘"))
    }
}
