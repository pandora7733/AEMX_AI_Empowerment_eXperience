package com.sihoo.aemx.pipeline.youtube

import com.sihoo.aemx.accessibility.Ordinal
import com.sihoo.aemx.pipeline.llm.CloudRoutingPolicy
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType

/** 검색어와 최대 개수를 받아 영상 id 목록을 돌려준다. 실패면 null. 호출마다 새로 받는다. */
typealias YouTubeSearch = suspend (query: String, maxResults: Int) -> List<String>?

/**
 * 유튜브 "검색 후 N번째 재생" 계획을 YouTube Data API 로 미리 풀어, 영상 id 하나를 여는 계획으로 바꾼다.
 *
 * 화면에서 검색하고 스크롤해서 세는 대신 API 가 준 순서대로 바로 연다. 대상은 유튜브 앱의
 * [검색, 첫 영상 재생 또는 N번째 영상 재생] 꼴 계획뿐이다. 뮤직, 개인정보 명령, 다른 동작이 섞인 계획은 건드리지 않는다.
 * API 가 실패하거나 N개 미만이면 원래 계획을 그대로 돌려준다.
 */
object YouTubeShortcut {

    /** 같은 검색을 최소 이만큼은 받는다. 순번이 작아도 여유 있게. */
    const val MIN_RESULTS = 5

    /** 바꿔 쓸 수 있는 계획의 검색어와 순번. */
    data class Target(val query: String, val ordinal: Int)

    /** 바꿔 쓸 수 있는 계획이면 검색어와 순번, 아니면 null. */
    fun target(plan: ActionPlan, utterance: String): Target? {
        if (plan.app.filterNot { it.isWhitespace() } != "유튜브") return null
        if (CloudRoutingPolicy.isPersonal(utterance, plan)) return null
        // 앞의 앱 열기가 남아 있어도 같은 계획으로 본다(열기는 검색이 함께 연다).
        val steps = plan.steps.dropWhile { it.action == ActionType.OPEN_APP }
        if (steps.size != 2) return null
        val search = steps[0]
        val play = steps[1]
        val query = search.target?.trim().orEmpty()
        if (search.action != ActionType.SEARCH || query.isEmpty()) return null
        if (play.action != ActionType.PLAY_FIRST) return null
        val ordinal = Ordinal.find(play.value) ?: Ordinal.inUtterance(play.target.orEmpty()) ?: 1
        return Target(query, ordinal)
    }

    /**
     * 계획을 영상 열기 하나로 바꾼다. 바꿀 수 없거나 API 가 실패하면 [plan] 을 그대로 돌려준다.
     * 검색어는 API 에만 보내고, 개인정보 명령은 [target] 에서 이미 걸러진다.
     */
    suspend fun rewrite(plan: ActionPlan, utterance: String, search: YouTubeSearch): ActionPlan {
        val target = target(plan, utterance) ?: return plan
        val wanted = maxOf(target.ordinal, MIN_RESULTS).coerceAtMost(YouTubeSearchClient.MAX_RESULTS)
        val ids = search(target.query, wanted) ?: return plan
        val videoId = ids.getOrNull(target.ordinal - 1) ?: return plan
        return ActionPlan(
            app = plan.app,
            steps = listOf(ActionStep(index = 0, action = ActionType.OPEN_VIDEO, target = videoId)),
        )
    }
}
