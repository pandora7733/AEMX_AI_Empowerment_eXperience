package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.SearchShortcutGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchShortcutGuardTest {

    private fun step(index: Int, action: ActionType, target: String?, value: String? = null) =
        ActionStep(index, action, target, value)

    /** 실기기에서 모델이 낸 계획 그대로다("쿠팡에서 생수 검색해줘"). */
    private val typedCoupang = ActionPlan(
        "쿠팡",
        listOf(
            step(0, ActionType.OPEN_APP, "쿠팡"),
            step(1, ActionType.SCROLL, "forward"),
            step(2, ActionType.SCROLL, "search"),
            step(3, ActionType.INPUT_TEXT, "검색어 입력", "생수"),
            step(4, ActionType.TAP, "검색"),
        ),
    )

    private fun shape(plan: ActionPlan) = plan.steps.map { it.action to it.target }

    @Test
    fun `검색칸 입력으로 풀어 낸 계획을 search 한 단계로 바꾼다`() {
        val fixed = SearchShortcutGuard.apply("쿠팡에서 생수 검색해줘", typedCoupang)

        assertEquals(
            listOf(ActionType.OPEN_APP to "쿠팡", ActionType.SEARCH to "생수"),
            shape(fixed),
        )
        assertEquals(listOf(0, 1), fixed.steps.map { it.index })
    }

    @Test
    fun `검색 뒤의 단계는 그대로 둔다`() {
        val withCart = typedCoupang.copy(steps = typedCoupang.steps + step(5, ActionType.ADD_TO_CART, "첫 번째 상품"))

        val fixed = SearchShortcutGuard.apply("쿠팡에서 생수 찾아서 장바구니에 담아줘", withCart)

        assertEquals(
            listOf(ActionType.OPEN_APP, ActionType.SEARCH, ActionType.ADD_TO_CART),
            fixed.steps.map { it.action },
        )
        assertEquals(listOf(0, 1, 2), fixed.steps.map { it.index })
    }

    @Test
    fun `이미 search 가 있으면 건드리지 않는다`() {
        val direct = ActionPlan("쿠팡", listOf(step(0, ActionType.OPEN_APP, "쿠팡"), step(1, ActionType.SEARCH, "생수")))

        assertSame(direct, SearchShortcutGuard.apply("쿠팡에서 생수 검색해줘", direct))
    }

    @Test
    fun `바로 검색을 지원하지 않는 앱은 화면 입력 그대로 둔다`() {
        val kyobo = ActionPlan(
            "교보문고",
            listOf(
                step(0, ActionType.OPEN_APP, "교보문고"),
                step(1, ActionType.TAP, "검색창"),
                step(2, ActionType.INPUT_TEXT, "검색창", "토지"),
                step(3, ActionType.TAP, "검색"),
            ),
        )

        assertSame(kyobo, SearchShortcutGuard.apply("교보문고에서 토지 검색해줘", kyobo))
    }

    @Test
    fun `검색하라는 말이 없으면 입력을 검색으로 바꾸지 않는다`() {
        assertSame(typedCoupang, SearchShortcutGuard.apply("쿠팡에 생수 입력해줘", typedCoupang))
    }

    @Test
    fun `검색칸이 아닌 곳에 넣는 입력은 바꾸지 않는다`() {
        val comment = ActionPlan(
            "유튜브",
            listOf(step(0, ActionType.OPEN_APP, "유튜브"), step(1, ActionType.INPUT_TEXT, "댓글", "검색해 봤어요")),
        )

        assertSame(comment, SearchShortcutGuard.apply("유튜브 댓글에 검색해 봤어요 라고 써줘", comment))
    }

    @Test
    fun `넣을 글자가 없는 입력은 바꾸지 않는다`() {
        val empty = ActionPlan(
            "쿠팡",
            listOf(step(0, ActionType.OPEN_APP, "쿠팡"), step(1, ActionType.INPUT_TEXT, "검색창", " ")),
        )

        assertSame(empty, SearchShortcutGuard.apply("쿠팡에서 검색해줘", empty))
    }
}
