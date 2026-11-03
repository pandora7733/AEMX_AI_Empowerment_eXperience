package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFieldRoleTest {

    private val screenHeight = 2_400

    /** 교보문고 화면 dump 와 같은 배치: 검색칸 [142,397][1012,490], 옆 검색 버튼 [1053,397][1147,490], 메뉴 버튼은 왼쪽 위. */
    private val kyoboField = SearchFieldRole.Field(
        id = "searchMainKeyword",
        hint = null,
        description = null,
        box = SearchFieldRole.Box(142, 397, 1012, 490),
    )
    private val kyoboSearchButton = SearchFieldRole.Button(
        label = "search button",
        description = null,
        box = SearchFieldRole.Box(1053, 397, 1147, 490),
    )
    private val kyoboMenu = SearchFieldRole.Button(
        label = null,
        description = "메뉴",
        box = SearchFieldRole.Box(20, 80, 120, 180),
    )

    private fun field(id: String?, top: Int = 100, hint: String? = null, description: String? = null) =
        SearchFieldRole.Field(id = id, hint = hint, description = description, box = SearchFieldRole.Box(100, top, 900, top + 90))

    // ------------------------------------------------------------ 말이 무엇을 가리키나

    @Test
    fun `검색창과 검색 입력 말은 입력칸을 가리킨다`() {
        listOf("검색창", "검색어 입력", "통합검색어 입력", "검색 칸", "search box").forEach {
            assertTrue(it, SearchFieldRole.isFieldRequest(it))
            assertFalse(it, SearchFieldRole.isButtonRequest(it))
        }
    }

    @Test
    fun `검색과 search button 말은 버튼을 가리키고 입력칸이 아니다`() {
        listOf("검색", "검색 버튼", "search button", "돋보기", "검색하기").forEach {
            assertTrue(it, SearchFieldRole.isButtonRequest(it))
            assertFalse(it, SearchFieldRole.isFieldRequest(it))
        }
    }

    @Test
    fun `검색과 상관없는 말은 역할 대상이 아니다`() {
        listOf("로그인", "확인", "결제하기").forEach {
            assertFalse(it, SearchFieldRole.isFieldRequest(it))
            assertFalse(it, SearchFieldRole.isButtonRequest(it))
        }
    }

    // ------------------------------------------------------------ 입력칸 고르기

    @Test
    fun `교보문고 화면에서는 검색 id 를 가진 입력칸 하나를 고른다`() {
        assertEquals(0, SearchFieldRole.pickField(listOf(kyoboField), screenHeight))
    }

    @Test
    fun `검색 말이 있는 입력칸이 둘이면 애매해서 고르지 않는다`() {
        val fields = listOf(field("searchA", top = 100), field("keywordB", top = 900))

        assertNull(SearchFieldRole.pickField(fields, screenHeight))
    }

    @Test
    fun `검색 말이 없고 위쪽 3분의 1에 입력칸이 하나면 그것을 고른다`() {
        val fields = listOf(field("title", top = 200), field("body", top = 1_500))

        assertEquals(0, SearchFieldRole.pickField(fields, screenHeight))
    }

    @Test
    fun `위쪽에 입력칸이 둘이면 고르지 않는다`() {
        val fields = listOf(field("username", top = 200), field("password", top = 400))

        assertNull(SearchFieldRole.pickField(fields, screenHeight))
    }

    @Test
    fun `다른 앱의 일반 입력칸이 아래쪽에만 있으면 고르지 않는다`() {
        val fields = listOf(field("message_body", top = 1_800))

        assertNull(SearchFieldRole.pickField(fields, screenHeight))
    }

    @Test
    fun `입력칸이 없으면 고르지 않는다`() {
        assertNull(SearchFieldRole.pickField(emptyList(), screenHeight))
    }

    @Test
    fun `힌트나 설명에 검색 말이 있어도 검색 입력칸으로 본다`() {
        assertEquals(0, SearchFieldRole.pickField(listOf(field("x", hint = "Search here", top = 1_800)), screenHeight))
    }

    // ------------------------------------------------------------ 검색 버튼 고르기

    @Test
    fun `입력칸 오른쪽에 붙은 검색 버튼 하나를 고른다`() {
        val buttons = listOf(kyoboMenu, kyoboSearchButton)

        assertEquals(1, SearchFieldRole.pickButton(buttons, kyoboField.box))
    }

    @Test
    fun `검색 버튼이 입력칸 옆이 아니면 고르지 않는다`() {
        val farAway = SearchFieldRole.Button("search button", null, SearchFieldRole.Box(50, 1_500, 150, 1_600))

        assertNull(SearchFieldRole.pickButton(listOf(farAway), kyoboField.box))
    }

    @Test
    fun `검색 말이 없는 버튼은 검색 버튼이 아니다`() {
        assertNull(SearchFieldRole.pickButton(listOf(kyoboMenu), kyoboField.box))
    }

    @Test
    fun `검색 버튼이 둘이면 고르지 않는다`() {
        // 입력칸과 같은 줄에 있는 다른 검색 버튼(세로로 겹침).
        val second = SearchFieldRole.Button("검색", null, SearchFieldRole.Box(1053, 400, 1147, 480))

        assertNull(SearchFieldRole.pickButton(listOf(kyoboSearchButton, second), kyoboField.box))
    }
}
