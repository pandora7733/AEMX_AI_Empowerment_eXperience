package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 단계를 사용자가 읽는 한 줄로 바꾼다("유튜브 열기", "'고양이' 검색", "배재준에게 카톡 보내기").
 *
 * JSON 이나 action 이름을 그대로 보이지 않는다. 보낼 내용과 입력한 글자는 받지도 않는다 — 패널은
 * 다른 사람이 어깨 너머로 볼 수 있어서, 받는 사람과 검색어까지만 보인다.
 *
 * 클라우드 계획의 target 은 가끔 화면 내부 이름(resource-id, "search button", "edit_text" 등)으로 온다.
 * 그런 영문 식별자는 따옴표에 넣어 보이지 않고 일반 말로 바꾼다(검색, 쇼츠, 버튼, 검색어). 이 규칙은
 * 캡슐 문장, 패널 줄, 완료 문장이 모두 같이 쓴다. 한글 라벨("검색창", "재생")은 지금처럼 따옴표로 보인다.
 */
object StepLabelFormatter {

    fun label(action: ActionType, target: String?, app: String): String {
        val name = clean(target)
        return when (action) {
            ActionType.OPEN_APP -> "${spokenApp(name, app)} 열기"
            ActionType.TAP -> "${tapObject(target).text} 누르기"
            ActionType.INPUT_TEXT -> if (isIdentifier(name)) "검색어 입력하기" else "글자 입력하기"
            ActionType.SEARCH -> if (name != null) "'$name' 검색" else "검색하기"
            ActionType.PLAY_FIRST -> "첫 영상 재생하기"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘기기"
            ActionType.OPEN_VIDEO -> "영상 열기"
            ActionType.OPEN_SHORTS -> "쇼츠 열기"
            ActionType.ADD_TO_CART -> "장바구니에 담기"
            ActionType.SCROLL -> if (isUp(target)) "위로 스크롤하기" else "아래로 스크롤하기"
            ActionType.WAIT -> "잠시 기다리기"
            ActionType.SEND_MESSAGE -> "${recipient(name)}에게 ${messenger(app)} 보내기"
            ActionType.UNKNOWN -> "화면 조작하기"
        }
    }

    /** 단계를 하고 있는 동안 캡슐에 보이는 문장("'고양이' 검색 중…"). */
    fun progressText(action: ActionType, target: String?, app: String): String {
        val name = clean(target)
        return when (action) {
            ActionType.OPEN_APP -> spokenApp(name, app).let { "$it${eulReul(it)} 여는 중…" }
            ActionType.TAP -> tapObject(target).let { obj -> "${obj.text}${eulReul(obj.base)} 누르는 중…" }
            ActionType.INPUT_TEXT -> if (isIdentifier(name)) "검색어 입력 중…" else "글자 입력 중…"
            ActionType.SEARCH -> if (name != null) "'$name' 검색 중…" else "검색하는 중…"
            ActionType.PLAY_FIRST -> "첫 영상을 재생하는 중…"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘기는 중…"
            ActionType.OPEN_VIDEO -> "영상을 여는 중…"
            ActionType.OPEN_SHORTS -> "쇼츠를 여는 중…"
            ActionType.ADD_TO_CART -> "장바구니에 담는 중…"
            ActionType.SCROLL -> if (isUp(target)) "위로 스크롤하는 중…" else "아래로 스크롤하는 중…"
            ActionType.WAIT -> "잠시 기다리는 중…"
            ActionType.SEND_MESSAGE -> "${recipient(name)}에게 ${messenger(app)} 보내는 중…"
            ActionType.UNKNOWN -> "화면을 조작하는 중…"
        }
    }

    /** 단계를 끝낸 뒤 결과를 말하는 문장. 증거가 있을 때만 화면에 나간다. */
    fun doneText(action: ActionType, target: String?, app: String): String {
        val name = clean(target)
        return when (action) {
            ActionType.OPEN_APP -> spokenApp(name, app).let { "$it${eulReul(it)} 열었어요" }
            ActionType.TAP -> tapObject(target).let { obj -> "${obj.text}${eulReul(obj.base)} 눌렀어요" }
            ActionType.INPUT_TEXT -> if (isIdentifier(name)) "검색어를 입력했어요" else "글자를 입력했어요"
            ActionType.SEARCH -> if (name != null) "'$name' 검색을 마쳤어요" else "검색을 마쳤어요"
            ActionType.PLAY_FIRST -> "첫 영상을 재생했어요"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘겼어요"
            ActionType.OPEN_VIDEO -> "영상을 열었어요"
            ActionType.OPEN_SHORTS -> "쇼츠를 열었어요"
            ActionType.ADD_TO_CART -> "장바구니에 담았어요"
            ActionType.SCROLL -> "스크롤했어요"
            ActionType.WAIT -> "잠시 기다렸어요"
            ActionType.SEND_MESSAGE -> {
                val kind = messenger(app)
                "${recipient(name)}에게 $kind${eulReul(kind)} 보냈어요"
            }
            ActionType.UNKNOWN -> "화면을 조작했어요"
        }
    }

    /**
     * 누를 대상을 사용자가 읽을 말로. [text] 는 문장에 그대로 넣는 꼴("'로그인'", "검색", "버튼"),
     * [base] 는 조사를 고를 말이다. 한글 라벨은 따옴표로, 영문 식별자는 따옴표 없이 일반 말로 보인다.
     */
    internal data class Target(val text: String, val base: String)

    internal fun tapObject(target: String?): Target {
        val name = clean(target) ?: return Target("버튼", "버튼")
        if (isIdentifier(name)) return identifierTarget(name)
        // 한글이라도 "검색 아이콘", "검색 버튼"처럼 검색 누르기를 가리키면 검색으로 말한다("검색창"은 입력칸이라 그대로).
        if (name.contains("검색") && listOf("아이콘", "버튼", "돋보기").any { name.contains(it) }) {
            return Target("검색", "검색")
        }
        return Target("'$name'", name)
    }

    /** 영문 식별자를 일반 말로: 검색 관련이면 검색, 쇼츠면 쇼츠, 그 밖은 버튼. */
    private fun identifierTarget(name: String): Target {
        val lower = name.lowercase()
        return when {
            lower.contains("search") -> Target("검색", "검색")
            lower.contains("short") -> Target("쇼츠", "쇼츠")
            else -> Target("버튼", "버튼")
        }
    }

    /**
     * 화면 내부 이름인지. 영문, 숫자, 밑줄, 공백과 resource-id 에 쓰는 기호만 있고 글자(영문)가 하나 이상 있으면 참이다.
     * 한글이 있으면 사용자가 읽는 라벨이라 거짓이다.
     */
    internal fun isIdentifier(name: String?): Boolean {
        // 긴 이름은 [clean] 이 줄였으므로 줄임표는 떼고 본다("searchMainKeyword" 가 "searchMainKeywor…" 가 돼도 같다).
        val text = name?.trim()?.removeSuffix("…").orEmpty()
        return text.isNotEmpty() && IDENTIFIER.matches(text) && text.any { it.isLetter() }
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9_ .:/\\-]+")

    /** 앱 이름. 대상이 내부 이름이면 대상 앱 이름을 쓴다. */
    private fun spokenApp(name: String?, app: String): String =
        (name?.takeUnless { isIdentifier(it) }) ?: clean(app) ?: "앱"

    private fun recipient(name: String?) = name ?: "받는 사람"

    /** 대상 앱 이름으로 어느 쪽으로 보내는지. 모르면 "메시지". */
    private fun messenger(app: String): String = when {
        app.contains("카카오") || app.contains("카톡") -> "카톡"
        app.contains("메시지") || app.contains("문자") -> "문자"
        else -> "메시지"
    }

    private fun isUp(target: String?): Boolean {
        val t = target?.trim()?.lowercase() ?: return false
        return t == "up" || t == "backward" || t.contains("위")
    }

    /** 줄바꿈을 없애고 길면 자른다. 비면 null. */
    private fun clean(text: String?): String? {
        val oneLine = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (oneLine.isEmpty()) return null
        return if (oneLine.length > MAX_TARGET) oneLine.take(MAX_TARGET) + "…" else oneLine
    }

    /** 받침이 있으면 "을", 없으면 "를". 한글이 아니면 구분할 수 없어 "을(를)". */
    private fun eulReul(word: String): String {
        val last = word.trimEnd('…').lastOrNull() ?: return "을(를)"
        if (last !in '가'..'힣') return "을(를)"
        return if ((last - '가') % 28 == 0) "를" else "을"
    }

    private const val MAX_TARGET = 16
}
