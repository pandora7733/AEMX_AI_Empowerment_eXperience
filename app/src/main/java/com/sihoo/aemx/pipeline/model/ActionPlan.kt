package com.sihoo.aemx.pipeline.model

/**
 * LLM 이 내놓는 실행계획.
 *
 * 스키마는 LLM 파트가 이미 확정해 검증까지 끝낸 형태를 그대로 따른다
 * (`LLM/system_prompt.txt` 참고).
 *
 * ```json
 * {"app": "알라딘", "steps": [{"action": "tap", "target": "로그인"}]}
 * ```
 *
 * 모델이 주지 않는 값([ActionStep.expect], [ActionStep.index], [ActionStep.timeoutMs])은
 * 파서와 [com.sihoo.aemx.pipeline.ExpectationInferencer] 가 채운다.
 * 나중에 프롬프트가 바뀌어 모델이 직접 expect 를 주더라도 그대로 받을 수 있도록,
 * 파서는 JSON 에 expect 가 있으면 추론값 대신 그 값을 쓴다.
 */
data class ActionPlan(
    /** 조작 대상 앱 이름. 모델은 "알라딘", "교보문고" 같은 한글 이름을 준다. */
    val app: String,
    val steps: List<ActionStep>
)

/** 실행모듈이 한 번에 수행하는 동작 하나. */
data class ActionStep(
    /** 계획 안에서의 순번. 모델이 id 를 주지 않아 파서가 0부터 붙인다. */
    val index: Int,
    val action: ActionType,
    /**
     * 조작 대상. viewId 가 아니라 사람이 읽는 이름이다("검색창", "Shorts", "첫 번째 동영상").
     * [ActionType.SEARCH] 는 예외적으로 검색어가 여기에 온다 — 실행모듈이 그렇게 받는다.
     * [ActionType.SCROLL] 은 up/down/forward/backward 중 하나다.
     */
    val target: String?,
    /**
     * [ActionType.INPUT_TEXT] 에서 입력할 내용.
     * [ActionType.NEXT_VIDEO] 와 [ActionType.WAIT] 에서는 대기 시간("10s", "500ms", "3").
     */
    val value: String? = null,
    /** 이 step 이 끝났을 때 화면이 어떠해야 하는지. 추론되거나 모델이 직접 준 값. */
    val expect: Expectation = Expectation.NONE,
    /** 화면이 안정될 때까지 기다려 줄 최대 시간. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /**
     * 로그·UI 표시용 한 줄.
     * 자격증명은 값이 없고 target 만 있으므로 그대로 찍어도 안전하지만,
     * [ActionType.INPUT_TEXT] 의 value 는 사용자가 말한 내용이라 길이만 남긴다.
     */
    fun describe(): String {
        val targetPart = target?.let { " \"$it\"" } ?: ""
        val valuePart = when (action) {
            ActionType.INPUT_TEXT, ActionType.SEND_MESSAGE -> value?.let { " (${it.length}자 입력)" } ?: ""
            else -> ""
        }
        return "#${index + 1} ${action.label}$targetPart$valuePart"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}

/**
 * 실행모듈이 받아들이는 action 목록.
 *
 * **세 곳이 같아야 한다**: 시스템 프롬프트(`LLM/system_prompt.txt`), 이 enum,
 * 실행모듈의 `accessibility/ActionPlan.kt`. 여기만 늘리면 모델은 그 action 을 영영
 * 만들지 않고, 프롬프트만 늘리면 실행모듈이 계획 전체를 거부한다.
 *
 * 로그인/자격증명 입력은 지원하지 않는다. 실기기에서 앱마다 로그인 화면 구조가
 * 너무 달라 안정적으로 동작하지 않아 범위에서 뺐다(ACCESSIBILITY/README.md 참고).
 */
enum class ActionType(val wire: String, val label: String) {
    OPEN_APP("open_app", "앱 실행"),
    TAP("tap", "누르기"),
    INPUT_TEXT("input_text", "텍스트 입력"),

    /** 앱의 검색 결과 화면을 바로 연다. 검색어는 value 가 아니라 target 에 온다. */
    SEARCH("search", "검색"),

    /** 유튜브 검색 결과의 첫 번째 동영상을 재생한다. */
    PLAY_FIRST("play_first", "첫 영상 재생"),

    /** 쇼츠/릴스에서 다음 영상으로 넘긴다. value 가 있으면 그만큼 기다린 뒤 넘긴다. */
    NEXT_VIDEO("next_video", "다음 영상"),

    SCROLL("scroll", "스크롤"),
    WAIT("wait", "대기"),

    /** 카카오톡·문자로 메시지를 보낸다. target 은 받는 사람, value 는 보낼 내용이다. */
    SEND_MESSAGE("send_message", "메시지 보내기"),

    /**
     * 유튜브 영상 하나를 바로 연다. target 은 영상 id 다. 계획에 나오지 않고, YouTube Data API 로 검색한
     * 결과로 [SEARCH] + [PLAY_FIRST] 를 바꿔 넣을 때만 쓴다(pipeline/youtube/YouTubeShortcut 참고).
     */
    OPEN_VIDEO("open_video", "영상 열기"),

    /** 유튜브 쇼츠 피드를 바로 연다. target 은 쓰지 않는다. 쇼츠를 말한 명령을 탭 대신 이걸로 바꾼다(ShortsShortcut). */
    OPEN_SHORTS("open_shorts", "쇼츠 열기"),

    /** 파서가 모르는 action 문자열. 실행하지 않고 재분석 대상이 된다. */
    UNKNOWN("", "알 수 없는 동작");

    companion object {
        fun from(raw: String?): ActionType {
            val key = raw?.trim()?.lowercase() ?: return UNKNOWN
            return entries.firstOrNull { it.wire == key } ?: UNKNOWN
        }
    }
}

/**
 * step 실행 뒤 화면이 어떠해야 하는지에 대한 기대.
 *
 * 지금은 LLM 이 이 값을 주지 않아서 action 종류로부터 유추한다.
 * [note] 는 재분석 프롬프트에 "무엇을 기대했는지" 알려주기 위한 사람 말이다.
 */
data class Expectation(
    /** 이 패키지의 화면이어야 한다. 앱 이름을 패키지로 아는 경우에만 채워진다. */
    val packageName: String? = null,
    /** 이 중 하나라도 화면에 보여야 한다. */
    val anyText: List<String> = emptyList(),
    /** 이 중 하나라도 보이면 실패로 친다. */
    val absentText: List<String> = emptyList(),
    /** 실행 전과 화면이 달라져 있어야 한다(누르면 무언가 반응해야 하는 경우). */
    val requireScreenChange: Boolean = false,
    /**
     * 화면이 안 바뀌었어도 통과시킬 조건. 같은 검색을 연달아 하면 결과 화면이 이미 떠 있어서
     * 화면이 그대로인데, 그 앱 화면이고 검색어가 보이면 이미 도착한 것이다.
     */
    val unchangedOk: UnchangedOk? = null,
    /** 사람이 읽는 기대 설명. */
    val note: String = ""
) {
    val isEmpty: Boolean
        get() = packageName == null && anyText.isEmpty() && absentText.isEmpty() &&
            !requireScreenChange

    companion object {
        val NONE = Expectation()
    }
}

/** 화면이 그대로여도 [packageName] 앱의 화면에 [texts] 중 하나가 보이면 이미 도착한 것으로 본다. */
data class UnchangedOk(val packageName: String, val texts: List<String>)
