package com.sihoo.aemx.accessibility

/**
 * 검색창과 검색 버튼을 이름이 아니라 역할로 고른다. 이름("검색창")이 노드 글자와 맞지 않는 화면(웹뷰, 글자 없는 입력칸)을 위한 것이다.
 *
 * 입력칸은 편집 가능한 노드 중에서 고른다. id·힌트·설명에 검색 말이 있는 것을 우선하고, 그런 칸이 없으면 화면 위쪽 3분의 1에 있는
 * 칸이 하나뿐일 때만 쓴다. 둘 이상이 애매하면 고르지 않는다. 검색 버튼은 입력칸 바로 오른쪽에서 세로로 겹치는 누를 수 있는 것 하나다.
 * 순수 함수라서 노드 대신 [Field] 와 [Button] 으로 받는다. 같은 순서의 목록에서 번호를 돌려주면 호출한 쪽이 노드를 고른다.
 */
internal object SearchFieldRole {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Field(
        val id: String?,
        val hint: String?,
        val description: String?,
        val box: Box,
        val focused: Boolean = false,
    )

    data class Button(
        val label: String?,
        val description: String?,
        val box: Box,
    )

    /** 입력칸을 찾는 말과 검색 의미를 알아보는 말. 공백을 빼고 소문자로 비교한다. */
    private val SEARCH_WORDS = listOf("search", "keyword", "query", "검색")
    private val BUTTON_WORDS = listOf("버튼", "button", "돋보기")
    private val FIELD_WORDS = listOf("창", "칸", "입력", "필드", "box", "field", "검색어", "search", "keyword", "query")
    private val BUTTON_NAMES = setOf("검색", "검색하기", "search", "submit")

    private fun key(text: String?): String = text.orEmpty().filterNot { it.isWhitespace() }.lowercase()

    /** 눌러서 검색하라는 말인가("검색", "검색 버튼", "search button"). */
    fun isButtonRequest(target: String): Boolean {
        val k = key(target)
        return BUTTON_WORDS.any { k.contains(it) } || k in BUTTON_NAMES
    }

    /** 검색 입력칸을 가리키는 말인가("검색창", "검색어 입력", "통합검색어 입력"). 버튼 말이 섞이면 아니다. */
    fun isFieldRequest(target: String): Boolean {
        if (isButtonRequest(target)) return false
        val k = key(target)
        return FIELD_WORDS.any { k.contains(it) }
    }

    /**
     * 입력칸 하나를 고른다. 검색 말이 있는 칸이 정확히 하나면 그것, 둘 이상이면 애매해서 null.
     * 검색 말이 있는 칸이 없으면 화면 위쪽 3분의 1에 있는 칸이 정확히 하나일 때만 그것.
     */
    fun pickField(fields: List<Field>, screenHeight: Int): Int? {
        val withSearch = fields.indices.filter { i ->
            val f = fields[i]
            listOf(f.id, f.hint, f.description).any { text -> SEARCH_WORDS.any { key(text).contains(it) } }
        }
        if (withSearch.size == 1) return withSearch[0]
        // 웹뷰는 아래에 깔린 화면의 같은 칸도 함께 내놓는다. 검색 말이 있는 칸이 여럿이면 포커스가 있는 칸 하나만 고른다.
        if (withSearch.size > 1) return withSearch.singleOrNull { fields[it].focused }
        val top = fields.indices.filter { fields[it].box.top < screenHeight / 3 }
        return top.singleOrNull()
    }

    /**
     * 입력칸 오른쪽에서 세로로 겹치는 검색 버튼 하나. 후보가 둘 이상이거나 없으면 null.
     * 버튼은 글자나 설명에 검색 말(search, 검색, 돋보기)이 있어야 한다.
     */
    fun pickButton(buttons: List<Button>, field: Box): Int? {
        val candidates = buttons.indices.filter { i ->
            val b = buttons[i]
            val named = listOf(b.label, b.description).any { text ->
                listOf("search", "검색", "돋보기").any { key(text).contains(it) }
            }
            val rightOfField = b.box.left >= field.right - RIGHT_SLACK_PX
            val overlapsVertically = b.box.top < field.bottom && b.box.bottom > field.top
            named && rightOfField && overlapsVertically
        }
        return candidates.singleOrNull()
    }

    /** 버튼이 칸의 오른쪽 끝에 살짝 겹쳐도 같은 줄로 본다. */
    private const val RIGHT_SLACK_PX = 40
}
