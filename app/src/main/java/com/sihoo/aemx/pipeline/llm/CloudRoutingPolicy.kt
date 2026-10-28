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

    fun route(utterance: String, localPlan: ActionPlan): Route {
        if (isPersonal(utterance, localPlan)) return Route.LOCAL
        return if (localPlan.steps.any { it.action in HARD_ACTIONS }) Route.CLOUD else Route.LOCAL
    }

    /** 재분석 때 현재 화면을 클라우드로 보내도 되는가. 개인정보 앱 화면이면 안 된다. */
    fun mayUploadScreen(utterance: String, appName: String?): Boolean {
        if (PERSONAL_UTTERANCE_KEYWORDS.any { utterance.contains(it) }) return false
        val app = appName?.lowercase().orEmpty()
        return PERSONAL_APP_KEYWORDS.none { app.contains(it) }
    }

    private fun isPersonal(utterance: String, plan: ActionPlan): Boolean {
        if (plan.steps.any { it.action in PERSONAL_ACTIONS }) return true
        if (PERSONAL_UTTERANCE_KEYWORDS.any { utterance.contains(it) }) return true
        val app = plan.app.lowercase()
        return PERSONAL_APP_KEYWORDS.any { app.contains(it) }
    }
}
