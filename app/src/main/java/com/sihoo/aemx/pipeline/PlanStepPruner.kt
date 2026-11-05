package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 계획에서 시간만 드는 "앱 열기"를 뺀다. 뒤의 동작이 앱을 함께 열어 주면 앞의 열기는 필요 없다.
 *
 * - "X 열기" + "X 에서 Y 검색": 검색 주소가 앱을 함께 연다. [directSearch] 가 참인 앱(네이버·구글·유튜브·유튜브 뮤직)만.
 * - "메시지 앱 열기" + "보내기": 보내기는 공유 인텐트나 문자 인텐트로 들어간다. 열기 대상이 메시지 앱이거나 계획 앱과 같을 때만.
 *
 * 열기 대상이 비었거나 계획 앱과 같을 때만 뺀다. 다른 앱을 여는 열기는 건드리지 않는다.
 * 계획을 받은 직후(오케스트레이터)와 기기 직접 실행 경로에서 같은 함수를 쓴다.
 */
object PlanStepPruner {

    private val MESSAGE_APP_KEYWORDS = listOf("카카오톡", "카톡", "메시지", "문자", "kakaotalk", "sms")

    fun prune(
        plan: ActionPlan,
        directSearch: (String) -> Boolean = { AppLauncher.supportsDirectSearch(it) },
        messageApp: (String) -> Boolean = ::isMessageApp,
    ): ActionPlan {
        val kept = plan.steps.filterIndexed { index, step ->
            if (step.action != ActionType.OPEN_APP) return@filterIndexed true
            val next = plan.steps.getOrNull(index + 1)?.action
            val redundant = when (next) {
                ActionType.SEARCH -> directSearch(plan.app) && sameApp(step.target, plan.app)
                ActionType.SEND_MESSAGE -> sameApp(step.target, plan.app) || messageApp(step.target.orEmpty())
                else -> false
            }
            !redundant
        }
        if (kept.size == plan.steps.size) return plan
        return plan.copy(steps = kept.mapIndexed { i, step -> step.copy(index = i) })
    }

    fun isMessageApp(name: String): Boolean {
        val key = name.filterNot { it.isWhitespace() }.lowercase()
        return MESSAGE_APP_KEYWORDS.any { key.contains(it) }
    }

    /** 열기 대상이 없거나 계획의 앱과 같은 이름이면 같은 앱이다(공백은 무시). */
    private fun sameApp(target: String?, app: String): Boolean =
        target.isNullOrBlank() || target.replace(" ", "") == app.replace(" ", "")
}
