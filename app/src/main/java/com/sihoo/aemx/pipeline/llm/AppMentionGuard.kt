package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 문장 맨 앞에서 "OO에서 / OO에" 로 지목한 앱을 계획의 앱과 맞춘다.
 *
 * "네이버에 유튜브 검색해줘" 에서 앱은 네이버이고 유튜브는 검색어다. 1.7B 모델은 PC 에서는
 * 맞게 내는데 NPU 로 돌린 폰에서 문장 속 "유튜브"를 앱으로 골라, 유튜브를 열고 유튜브를
 * 검색해 버렸다. 프롬프트만으로는 모델 쪽 수치 차이를 막을 수 없어서, 사람이 앱을
 * 지목한 말투일 때는 그 지목을 따르게 코드로 바로잡는다.
 *
 * "인스타 릴스 다음 영상" 처럼 조사 없이 앱 이름만 나오는 말은 건드리지 않는다. 그런 말은
 * 앱을 지목했는지 검색어인지 글만으로 알 수 없다.
 */
object AppMentionGuard {

    fun apply(utterance: String, plan: ActionPlan): ActionPlan {
        val named = leadingApp(utterance) ?: return plan
        if (canonical(plan.app) == named) return plan

        val steps = plan.steps.map { step ->
            if (step.action == ActionType.OPEN_APP) step.copy(target = named) else step
        }
        return plan.copy(app = named, steps = steps)
    }

    /** 문장 앞에서 "앱 + 에서/에/에서는/에는" 으로 지목한 앱의 정식 이름. */
    internal fun leadingApp(utterance: String): String? {
        val text = utterance.trim()
        for ((alias, name) in ALIASES) {
            if (!text.startsWith(alias)) continue
            val rest = text.substring(alias.length).trimStart()
            if (PARTICLES.any { rest.startsWith(it) }) return name
        }
        return null
    }

    private fun canonical(app: String): String? {
        val key = app.trim().lowercase(java.util.Locale.ROOT)
        return ALIASES.firstOrNull { it.first == key }?.second
    }

    /** 긴 별칭이 먼저 와야 "인스타그램" 이 "인스타" 에 먼저 걸리지 않는다. STT 오표기도 받는다. */
    private val ALIASES: List<Pair<String, String>> = listOf(
        "인스타그램" to "인스타그램", "인스타" to "인스타그램",
        "교보문고" to "교보문고", "알라딘" to "알라딘",
        "네이버" to "네이버", "구글" to "구글",
        "유튜브" to "유튜브", "유투브" to "유튜브",
    )

    /** 긴 조사가 먼저 와야 "에서" 가 "에" 에 먼저 걸리지 않는다. */
    private val PARTICLES = listOf("에서는", "에서", "에는", "에")
}
