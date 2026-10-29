package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 로컬 계획을 보고 클라우드 AI 에 넘길지 정하는 규칙. 어떤 클라우드 서비스를 쓰는지와 무관하다.
 *
 * 계획서의 원칙:
 * - 개인정보 서비스(메시지·연락처·음성)는 기기 밖으로 보내지 않는다. 이것이 가장 먼저 검사되고,
 *   다른 어떤 조건보다 우선한다.
 * - 간단한 시스템 동작(앱 열기, 스크롤, 대기)과 웹 검색은 기기에서 처리한다.
 * - 어려운 동작(영상 재생, 특정 앱 안에서 누르고 입력하기)만 클라우드로 보낸다.
 *
 * 두 가지로 쓴다. [routeUtterance] 는 발화만 보고 계획을 만들기 전에 정하고,
 * [route] 는 로컬 계획이 나온 뒤에 계획을 보고 정한다(발화 단서를 놓쳤을 때 대비).
 */
object CloudRoutingPolicy {

    enum class Route { LOCAL, CLOUD }

    /** 이 동작이 하나라도 있으면 무조건 로컬. */
    private val PERSONAL_ACTIONS = setOf(ActionType.SEND_MESSAGE)

    /** 이 동작이 있으면 클라우드 대상. */
    private val HARD_ACTIONS = setOf(
        ActionType.PLAY_FIRST,
        ActionType.NEXT_VIDEO,
        ActionType.TAP,
        ActionType.INPUT_TEXT,
    )

    /** 이 앱의 화면과 명령은 기기 밖으로 나가지 않는다. 앱 이름은 모델이 준 한글/영문 그대로 비교한다. */
    private val PERSONAL_APP_KEYWORDS = listOf(
        "카카오톡", "카톡", "kakaotalk", "메시지", "문자", "message", "sms",
        "연락처", "주소록", "contacts", "전화", "phone",
        // 재분석 화면은 한글 이름이 아니라 패키지명(com.kakao.talk, com.samsung.android.messaging …)으로 온다.
        "kakao", "messaging", "mms", "dialer", "incallui",
    )

    /** 발화에 이 말이 있으면 계획과 무관하게 로컬. */
    private val PERSONAL_UTTERANCE_KEYWORDS = listOf(
        "카톡", "카카오톡", "문자", "메시지", "연락처", "주소록", "전화",
    )

    /** "엄마한테 늦는다고 보내줘", "친구에게 사진 보내" 꼴. 키워드 목록에 없는 받는 사람도 잡는다. */
    private val PERSONAL_RECIPIENT_PATTERN = Regex("(한테|에게).*보내")

    /** 발화에 이 말이 있으면 화면 동작이 필요한 어려운 명령이다. */
    private val HARD_UTTERANCE_PATTERN = Regex(
        "틀어|재생|플레이(?!\\s?스토어)|넘겨|다음 영상|다음 거|쇼츠|shorts|릴스|" +
            "(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열|\\d+)\\s*번째|입력|쳐|눌러|클릭|들어가서",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 바로 검색이 안 되는 앱. 이 앱 안에서 검색하거나 찾으려면 화면을 눌러 가며 해야 한다.
     * 네이버·구글·유튜브·유튜브 뮤직은 검색 주소로 바로 가므로 여기 넣지 않는다.
     */
    private val NON_SEARCH_APP_KEYWORDS = listOf(
        "교보문고", "알라딘", "쿠팡", "인스타", "페이스북", "틱톡", "배달의민족", "지마켓", "11번가", "멜론",
    )

    private val SEARCH_WORDS = listOf("검색", "찾아", "찾기")

    fun route(utterance: String, localPlan: ActionPlan): Route {
        if (isPersonal(utterance, localPlan)) return Route.LOCAL
        return if (localPlan.steps.any { it.action in HARD_ACTIONS }) Route.CLOUD else Route.LOCAL
    }

    /** 계획을 만들기 전에 발화만 보고 정한다. 개인정보 발화가 가장 먼저 검사된다. */
    fun routeUtterance(utterance: String): Route {
        if (isPersonalUtterance(utterance)) return Route.LOCAL
        if (HARD_UTTERANCE_PATTERN.containsMatchIn(utterance)) return Route.CLOUD
        if (isNonSearchAppSearch(utterance)) return Route.CLOUD
        return Route.LOCAL
    }

    /** 재분석 때 현재 화면을 클라우드로 보내도 되는가. 개인정보 앱 화면이면 안 된다. */
    fun mayUploadScreen(utterance: String, appName: String?): Boolean {
        if (PERSONAL_UTTERANCE_KEYWORDS.any { utterance.contains(it) }) return false
        val app = appName?.lowercase().orEmpty()
        return PERSONAL_APP_KEYWORDS.none { app.contains(it) }
    }

    /** 계획이 개인정보 동작이나 개인정보 앱을 다루는가. 클라우드 답을 쓰기 전에도 이 검사를 거친다. */
    fun isPersonal(utterance: String, plan: ActionPlan): Boolean {
        if (plan.steps.any { it.action in PERSONAL_ACTIONS }) return true
        if (isPersonalUtterance(utterance)) return true
        val app = plan.app.lowercase()
        return PERSONAL_APP_KEYWORDS.any { app.contains(it) }
    }

    private fun isPersonalUtterance(utterance: String): Boolean =
        PERSONAL_UTTERANCE_KEYWORDS.any { utterance.contains(it) } ||
            PERSONAL_RECIPIENT_PATTERN.containsMatchIn(utterance)

    private fun isNonSearchAppSearch(utterance: String): Boolean =
        NON_SEARCH_APP_KEYWORDS.any { utterance.contains(it) } &&
            SEARCH_WORDS.any { utterance.contains(it) }
}
