package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 단계를 사용자가 읽는 한 줄로 바꾼다("유튜브 열기", "'고양이' 검색", "배재준에게 카톡 보내기").
 *
 * JSON 이나 action 이름을 그대로 보이지 않는다. 보낼 내용과 입력한 글자는 받지도 않는다 — 패널은
 * 다른 사람이 어깨 너머로 볼 수 있어서, 받는 사람과 검색어까지만 보인다.
 */
object StepLabelFormatter {

    fun label(action: ActionType, target: String?, app: String): String {
        val name = clean(target)
        return when (action) {
            ActionType.OPEN_APP -> "${name ?: clean(app) ?: "앱"} 열기"
            ActionType.TAP -> if (name != null) "'$name' 누르기" else "버튼 누르기"
            ActionType.INPUT_TEXT -> "글자 입력하기"
            ActionType.SEARCH -> if (name != null) "'$name' 검색" else "검색하기"
            ActionType.PLAY_FIRST -> "첫 영상 재생하기"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘기기"
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
            ActionType.OPEN_APP -> {
                val appName = name ?: clean(app) ?: "앱"
                "$appName${eulReul(appName)} 여는 중…"
            }
            ActionType.TAP -> if (name != null) "'$name'${eulReul(name)} 누르는 중…" else "버튼을 누르는 중…"
            ActionType.INPUT_TEXT -> "글자 입력 중…"
            ActionType.SEARCH -> if (name != null) "'$name' 검색 중…" else "검색하는 중…"
            ActionType.PLAY_FIRST -> "첫 영상을 재생하는 중…"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘기는 중…"
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
            ActionType.OPEN_APP -> {
                val appName = name ?: clean(app) ?: "앱"
                "$appName${eulReul(appName)} 열었어요"
            }
            ActionType.TAP -> if (name != null) "'$name'${eulReul(name)} 눌렀어요" else "버튼을 눌렀어요"
            ActionType.INPUT_TEXT -> "글자를 입력했어요"
            ActionType.SEARCH -> if (name != null) "'$name' 검색을 마쳤어요" else "검색을 마쳤어요"
            ActionType.PLAY_FIRST -> "첫 영상을 재생했어요"
            ActionType.NEXT_VIDEO -> "다음 영상으로 넘겼어요"
            ActionType.SCROLL -> "스크롤했어요"
            ActionType.WAIT -> "잠시 기다렸어요"
            ActionType.SEND_MESSAGE -> {
                val kind = messenger(app)
                "${recipient(name)}에게 $kind${eulReul(kind)} 보냈어요"
            }
            ActionType.UNKNOWN -> "화면을 조작했어요"
        }
    }

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
