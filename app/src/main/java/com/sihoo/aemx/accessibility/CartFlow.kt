package com.sihoo.aemx.accessibility

/**
 * 쿠팡 장바구니 담기에서 화면 글자를 판정하는 순수 규칙. 안드로이드 없이 테스트한다.
 *
 * 화면 구조는 실기기에서 확인했다(2026-11-14, 쿠팡 앱).
 * - 검색 결과: 목록(result_recycler_view) 안의 상품 칸마다 가격("8,540원")이 있고 칸 전체가 눌린다.
 * - 상품 화면: 아래에 "장바구니 담기" 와 "바로구매" 버튼이 나란히 있다. 각각 따로 눌리는 영역이다.
 * - 담은 뒤: "장바구니에 상품을 담았습니다." 안내 창이 뜨고 아래 탭의 장바구니 숫자가 하나 오른다.
 */
internal object CartFlow {

    /** 담기 버튼의 글자. 이 글자와 정확히 같은 노드만 누른다("바로구매" 를 누르지 않게 한다). */
    const val ADD_BUTTON_TEXT = "장바구니 담기"

    /** 검색 결과 목록과 장바구니 숫자의 view id 끝부분. */
    const val RESULT_LIST_ID = ":id/result_recycler_view"
    const val CART_BADGE_ID = ":id/tb_tab_count_badge"

    /** 담겼다는 것을 확인했을 때 남기는 증거 문장. */
    const val EVIDENCE = "장바구니에 담았다는 안내를 확인했다"

    /** 담기 버튼인가. 앞뒤 공백만 무시한다. */
    fun isAddButton(text: String?): Boolean = text?.trim() == ADD_BUTTON_TEXT

    /** 담겼다는 안내 문구인가("장바구니에 상품을 담았습니다."). 버튼 글자("장바구니 담기")는 아니다. */
    fun isAddedNotice(text: String?): Boolean {
        val value = text ?: return false
        return value.contains("장바구니") && ADDED_WORDS.any { value.contains(it) }
    }

    /** 가격 글자인가("8,540원", "15,300원"). 상품 칸을 광고 띠나 안내 줄과 가를 때 쓴다. */
    fun looksLikePrice(text: String?): Boolean = text != null && PRICE.matches(text.trim())

    /** 장바구니 숫자. 읽을 수 없으면 null("99+" 는 99 로 본다). */
    fun badgeCount(text: String?): Int? = text?.trim()?.removeSuffix("+")?.toIntOrNull()

    /** 숫자가 늘었는가. 둘 중 하나라도 모르면 판단하지 않는다. */
    fun badgeIncreased(before: Int?, after: Int?): Boolean = before != null && after != null && after > before

    private val ADDED_WORDS = listOf("담았", "담겼", "담김", "추가했", "추가되었", "추가됐")
    private val PRICE = Regex("\\d{1,3}(,\\d{3})*원")
}
