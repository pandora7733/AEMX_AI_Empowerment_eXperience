package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CartFlowTest {

    @Test
    fun `담기 버튼은 글자가 정확히 같을 때만이다`() {
        assertTrue(CartFlow.isAddButton("장바구니 담기"))
        assertTrue(CartFlow.isAddButton(" 장바구니 담기 "))
        assertFalse(CartFlow.isAddButton("바로구매"))
        assertFalse(CartFlow.isAddButton("장바구니"))
        assertFalse(CartFlow.isAddButton("장바구니에 상품을 담았습니다."))
        assertFalse(CartFlow.isAddButton(null))
    }

    @Test
    fun `담겼다는 안내 문구를 알아본다`() {
        assertTrue(CartFlow.isAddedNotice("장바구니에 상품을 담았습니다."))
        assertTrue(CartFlow.isAddedNotice("상품이 장바구니에 담겼습니다"))
    }

    @Test
    fun `버튼 글자나 탭 이름은 담겼다는 안내가 아니다`() {
        assertFalse(CartFlow.isAddedNotice("장바구니 담기"))
        assertFalse(CartFlow.isAddedNotice("장바구니"))
        assertFalse(CartFlow.isAddedNotice("바로구매"))
        assertFalse(CartFlow.isAddedNotice(null))
    }

    @Test
    fun `가격 글자를 알아본다`() {
        assertTrue(CartFlow.looksLikePrice("8,540원"))
        assertTrue(CartFlow.looksLikePrice("15,300원"))
        assertTrue(CartFlow.looksLikePrice("900원"))
        assertFalse(CartFlow.looksLikePrice("(100ml당 36원)"))
        assertFalse(CartFlow.looksLikePrice("평창수 무라벨 생수, 2L, 12개"))
        assertFalse(CartFlow.looksLikePrice("19%"))
    }

    @Test
    fun `장바구니 숫자를 읽는다`() {
        assertEquals(11, CartFlow.badgeCount("11"))
        assertEquals(99, CartFlow.badgeCount("99+"))
        assertNull(CartFlow.badgeCount(""))
        assertNull(CartFlow.badgeCount(null))
    }

    @Test
    fun `숫자가 늘었을 때만 담긴 것으로 본다`() {
        assertTrue(CartFlow.badgeIncreased(11, 12))
        assertFalse(CartFlow.badgeIncreased(12, 12))
        assertFalse(CartFlow.badgeIncreased(null, 12))
        assertFalse(CartFlow.badgeIncreased(11, null))
    }
}
