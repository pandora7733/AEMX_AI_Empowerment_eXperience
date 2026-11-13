package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.accessibility.SearchFieldRole
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 바로 검색할 수 있는 앱인데 모델이 검색을 "검색칸에 입력하고 검색 누르기" 로 풀어 낸 계획을 search 한 단계로 바로잡는다.
 *
 * 1.7B 모델은 같은 말("쿠팡에서 생수 검색해줘")에도 어떤 때는 search 를, 어떤 때는
 * open_app, scroll, scroll, input_text("검색어 입력"), tap("검색") 을 낸다. 뒤의 계획은 화면의 검색칸을 찾지 못해
 * 재분석만 돌다 실패한다(실기기에서 확인, 45초). 검색 주소로 여는 쪽이 빠르고 틀리지 않는다.
 *
 * 바꾸는 조건은 좁게 잡는다. 엉뚱한 입력을 검색으로 바꾸지 않으려는 것이다.
 * - 계획의 앱이 바로 검색을 지원한다([AppLauncher.supportsDirectSearch]).
 * - 계획에 search 가 없다.
 * - 사용자가 검색하라고 말했다(검색, 찾아).
 * - 입력 대상이 검색칸을 가리키는 말이고("검색창", "검색어 입력") 넣을 글자가 있다.
 *
 * 바꿀 때는 입력 앞의 스크롤과 검색칸 누르기, 입력 뒤의 검색 버튼 누르기를 함께 뺀다. 그 뒤의 단계
 * (첫 영상 재생, 장바구니 담기 등)는 그대로 둔다.
 */
object SearchShortcutGuard {

    fun apply(utterance: String, plan: ActionPlan): ActionPlan {
        if (!ASKS_SEARCH.containsMatchIn(utterance)) return plan
        if (!AppLauncher.supportsDirectSearch(plan.app)) return plan
        if (plan.steps.any { it.action == ActionType.SEARCH }) return plan

        val at = plan.steps.indexOfFirst { step ->
            step.action == ActionType.INPUT_TEXT && !step.value.isNullOrBlank() &&
                SearchFieldRole.isFieldRequest(step.target.orEmpty())
        }
        if (at < 0) return plan
        val query = plan.steps[at].value.orEmpty().trim()

        // 입력 앞에서는 앱 열기만 남긴다(검색 주소가 화면을 바로 여니 스크롤과 검색칸 누르기는 필요 없다).
        val before = plan.steps.take(at).filter { it.action == ActionType.OPEN_APP }
        // 입력 바로 뒤의 검색 버튼 누르기는 검색에 포함된다.
        val after = plan.steps.drop(at + 1).dropWhile { step ->
            step.action == ActionType.TAP && SearchFieldRole.isButtonRequest(step.target.orEmpty())
        }
        val search = ActionStep(index = before.size, action = ActionType.SEARCH, target = query)
        val steps = (before + search + after)
            .mapIndexed { position, step -> if (step.index == position) step else step.copy(index = position) }
        return plan.copy(steps = steps)
    }

    private val ASKS_SEARCH = Regex("검색|찾아|찾기")
}
