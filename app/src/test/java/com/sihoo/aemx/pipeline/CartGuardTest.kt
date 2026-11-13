package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CartGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CartGuardTest {

    private fun plan(app: String, vararg steps: Pair<ActionType, String?>): ActionPlan =
        ActionPlan(app, steps.mapIndexed { index, (action, target) -> ActionStep(index, action, target) })

    private fun actions(plan: ActionPlan): List<ActionType> = plan.steps.map { it.action }

    private val searchOnly = plan("쿠팡", ActionType.OPEN_APP to "쿠팡", ActionType.SEARCH to "생수")
    private val searchAndCart = plan(
        "쿠팡", ActionType.OPEN_APP to "쿠팡", ActionType.SEARCH to "생수", ActionType.ADD_TO_CART to "첫 번째 상품",
    )

    @Test
    fun `장바구니에 담으라는 말만 명시로 본다`() {
        assertTrue(CartGuard.asksToAdd("쿠팡에서 생수 검색해서 장바구니에 담아줘"))
        assertTrue(CartGuard.asksToAdd("생수 장바구니에 넣어줘"))
        assertTrue(CartGuard.asksToAdd("이거 장바구니 추가해줘"))
        assertTrue(CartGuard.asksToAdd("카트에 담아"))
    }

    @Test
    fun `검색이나 구매나 장바구니 열기는 담으라는 말이 아니다`() {
        assertFalse(CartGuard.asksToAdd("쿠팡에서 생수 검색해줘"))
        assertFalse(CartGuard.asksToAdd("쿠팡에서 생수 사줘"))
        assertFalse(CartGuard.asksToAdd("쿠팡 장바구니 열어줘"))
        assertFalse(CartGuard.asksToAdd("쿠팡에서 장바구니 눌러줘"))
        // 장바구니 없이 담아 달라는 말은 무엇에 담는지 분명하지 않다.
        assertFalse(CartGuard.asksToAdd("생수 담아줘"))
    }

    @Test
    fun `담지 말라는 말은 명시가 아니다`() {
        assertFalse(CartGuard.asksToAdd("생수 검색만 하고 장바구니에 담지 마"))
        assertFalse(CartGuard.asksToAdd("장바구니에는 넣지 말고 보여만 줘"))
        assertFalse(CartGuard.asksToAdd("장바구니에 추가하지 마"))
    }

    @Test
    fun `말하지 않았는데 모델이 낸 담기는 뺀다`() {
        val fixed = CartGuard.apply("쿠팡에서 생수 검색해줘", searchAndCart)

        assertEquals(listOf(ActionType.OPEN_APP, ActionType.SEARCH), actions(fixed))
    }

    @Test
    fun `담기를 빼고 남는 단계가 없으면 앱만 연다`() {
        val onlyCart = plan("쿠팡", ActionType.ADD_TO_CART to "첫 번째 상품")

        val fixed = CartGuard.apply("쿠팡 보여줘", onlyCart)

        assertEquals(listOf(ActionType.OPEN_APP), actions(fixed))
        assertEquals("쿠팡", fixed.steps.single().target)
    }

    @Test
    fun `명시했고 계획에 담기가 있으면 그대로 둔다`() {
        assertSame(searchAndCart, CartGuard.apply("쿠팡에서 생수 검색해서 장바구니에 담아줘", searchAndCart))
    }

    @Test
    fun `명시했는데 모델이 담기를 빠뜨리면 맨 뒤에 붙인다`() {
        val fixed = CartGuard.apply("쿠팡에서 생수 검색해서 장바구니에 담아줘", searchOnly)

        assertEquals(listOf(ActionType.OPEN_APP, ActionType.SEARCH, ActionType.ADD_TO_CART), actions(fixed))
        assertEquals(listOf(0, 1, 2), fixed.steps.map { it.index })
    }

    @Test
    fun `모델이 담기를 누르기로 냈으면 담기 하나로 바꾼다`() {
        val tapped = plan(
            "쿠팡", ActionType.OPEN_APP to "쿠팡", ActionType.SEARCH to "생수", ActionType.TAP to "장바구니 담기",
        )

        val fixed = CartGuard.apply("쿠팡에서 생수 찾아서 장바구니에 넣어줘", tapped)

        assertEquals(listOf(ActionType.OPEN_APP, ActionType.SEARCH, ActionType.ADD_TO_CART), actions(fixed))
    }

    @Test
    fun `담기가 여러 번 나와도 하나만 남긴다`() {
        val twice = plan(
            "쿠팡", ActionType.SEARCH to "생수", ActionType.ADD_TO_CART to "첫 번째 상품", ActionType.ADD_TO_CART to "첫 번째 상품",
        )

        val fixed = CartGuard.apply("생수 장바구니에 담아줘", twice)

        assertEquals(listOf(ActionType.SEARCH, ActionType.ADD_TO_CART), actions(fixed))
    }

    @Test
    fun `순번을 말하면 담기의 value 로 채운다`() {
        val fixed = CartGuard.apply("쿠팡에서 생수 검색해서 두 번째 상품 장바구니에 담아줘", searchOnly)

        assertEquals("2", fixed.steps.last().value)
    }

    @Test
    fun `쿠팡이 아닌 앱의 계획에는 담기를 붙이지 않는다`() {
        val naver = plan("네이버", ActionType.OPEN_APP to "네이버", ActionType.SEARCH to "생수")

        assertSame(naver, CartGuard.apply("네이버에서 생수 검색해서 장바구니에 담아줘", naver))
    }

    @Test
    fun `담기와 상관없는 계획은 건드리지 않는다`() {
        assertSame(searchOnly, CartGuard.apply("쿠팡에서 생수 검색해줘", searchOnly))
    }
}
