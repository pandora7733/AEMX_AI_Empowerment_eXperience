package com.sihoo.aemx.pipeline.youtube

import com.sihoo.aemx.pipeline.llm.CloudRoutingPolicy
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * "유튜브 쇼츠 보여줘" 를 쇼츠 주소로 바로 연다. 계획의 [탭 Shorts] 를 따라가면 재생 화면에서 하단 탭이 없어
 * 못 찾고, 재분석이 낸 스크롤이 "성공"으로 끝나는 거짓 성공이 났다(실기기).
 *
 * 바꾸는 조건은 좁다. 유튜브 앱이고, 쇼츠를 말했고, 계획이 앱 열기와 쇼츠 관련 탭뿐이어야 한다.
 * 검색어나 재생, 스크롤이 섞이면 원래 계획을 그대로 쓴다. 개인정보 명령도 건드리지 않는다.
 */
object ShortsShortcut {

    private val SHORTS_WORD = Regex("쇼츠|shorts", RegexOption.IGNORE_CASE)

    fun rewrite(plan: ActionPlan, utterance: String): ActionPlan {
        if (plan.app.filterNot { it.isWhitespace() } != "유튜브") return plan
        if (!SHORTS_WORD.containsMatchIn(utterance)) return plan
        if (CloudRoutingPolicy.isPersonal(utterance, plan)) return plan
        if (plan.steps.any { it.action != ActionType.OPEN_APP && it.action != ActionType.TAP }) return plan
        // 탭은 쇼츠를 가리키는 것만 허용한다. 다른 버튼을 누르는 계획은 그대로 둔다.
        val taps = plan.steps.filter { it.action == ActionType.TAP }
        if (taps.any { !SHORTS_WORD.containsMatchIn(it.target.orEmpty()) }) return plan
        return ActionPlan(app = plan.app, steps = listOf(ActionStep(index = 0, action = ActionType.OPEN_SHORTS, target = null)))
    }
}
