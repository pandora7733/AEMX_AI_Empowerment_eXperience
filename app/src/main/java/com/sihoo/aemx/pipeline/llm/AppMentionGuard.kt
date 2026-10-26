package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 문장 맨 앞에서 "OO에서 / OO에" 로 지목한 앱을 계획의 앱과 맞춘다.
 *
 * "네이버에 유튜브 검색해줘" 에서 앱은 네이버이고 유튜브는 검색어다. 1.7B 모델은 PC 에서는
 * 맞게 내는데 NPU 로 돌린 폰에서 문장 속 "유튜브"를 앱으로 골라, 유튜브를 열고 유튜브를
 * 검색해 버렸다. 프롬프트만으로는 모델 쪽 수치 차이를 막을 수 없어서, 사람이 앱을
 * 지목한 말투일 때는 그 지목을 따르게 코드로 바로잡는다. 첫 계획뿐 아니라 재분석 계획에도
 * 같은 보정을 건다. 재분석 때도 폰의 모델이 앱을 바꾸거나 엉뚱한 step 을 내기 때문이다.
 * 지목이 없는 문장은 재분석이 화면을 보고 앱을 바꿀 수 있으므로 건드리지 않는다.
 *
 * "넷플릭스 열어줘" 처럼 앱만 여는 말은 별칭에 없는 앱도 있다. 1.7B 모델이 프롬프트 예시("쿠팡 열어줘")를
 * 따라 엉뚱한 앱을 내기 때문에, 문장 전체가 앱만 여는 말이면 사용자가 말한 앱 이름의 open_app
 * 하나로 계획을 바로잡는다. 모델이 "열어줘" 를 검색어로 붙인 step 도 걷어 낸다.
 * 설치 여부와 실제 앱 찾기는 AppLauncher 몫이라 여기서는 이름만 맞춘다.
 *
 * "인스타 릴스 다음 영상" 처럼 조사 없이 앱 이름만 나오는 말은 건드리지 않는다. 그런 말은
 * 앱을 지목했는지 검색어인지 글만으로 알 수 없다.
 */
object AppMentionGuard {

    fun apply(utterance: String, plan: ActionPlan): ActionPlan {
        // 발화 전체가 "OO 열어줘" 뿐이면 계획은 open_app 하나여야 한다. 모델이 "열어줘" 를 검색어로
        // 붙인 step 같은 군더더기를 낸 경우도 여기서 걷어 낸다.
        openOnlyApp(utterance)?.let { return openPlan(plan, it) }

        val named = leadingApp(utterance)
        val app = named ?: plan.app

        // open_app 의 target 은 plan.app 과 같아야 실행모듈이 받아 준다. 모델이 target 만 깨뜨려 내면
        // ("넷플ิก스" 처럼 다른 문자가 섞임) 계획 전체가 거부되므로 app 에 맞춘다. 앱이 이미 맞아도 건다.
        val steps = plan.steps.map { step ->
            if (step.action == ActionType.OPEN_APP && step.target != app) step.copy(target = app) else step
        }
        val appSame = (canonical(plan.app) ?: plan.app.trim()) == app
        if (appSame && steps == plan.steps) return plan
        return plan.copy(app = if (appSame) plan.app else app, steps = steps)
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

    /** 앱 [name] 을 여는 step 하나짜리 계획. 이미 그 모양이면 그대로 돌려준다. */
    private fun openPlan(plan: ActionPlan, name: String): ActionPlan {
        val open = plan.steps.firstOrNull { it.action == ActionType.OPEN_APP }
            ?: ActionStep(0, ActionType.OPEN_APP, name)
        val step = if (open.target == name) open else open.copy(target = name)
        if (plan.app == name && plan.steps.size == 1 && plan.steps[0] == step) return plan
        return plan.copy(app = name, steps = listOf(step))
    }

    /**
     * "OO 열어줘 / 켜줘 / 실행해줘" 처럼 문장 전체가 앱만 여는 말일 때 앱 이름을 읽는다.
     * 열기 동사 뒤에 글자가 더 있으면("OO 열어서 검색해줘") 다른 요청이 붙은 말이라 null 이다.
     * 별칭에 있으면 정식 이름, 없으면 말한 그대로 돌려준다.
     */
    internal fun openOnlyApp(utterance: String): String? {
        val match = OPEN_PATTERN.find(utterance.trim()) ?: return null
        var name = match.groupValues[1].trim()
        name = name.removeSuffix("을").removeSuffix("를").trim().removeSuffix("앱").trim()
        if (name.isEmpty() || name.length > 12 || name.split(' ').size > 2) return null
        val key = name.lowercase(java.util.Locale.ROOT)
        return ALIASES.firstOrNull { it.first == key }?.second ?: name
    }

    private fun canonical(app: String): String? {
        val key = app.trim().lowercase(java.util.Locale.ROOT)
        return ALIASES.firstOrNull { it.first == key }?.second
    }

    /**
     * 긴 별칭이 먼저 와야 "인스타그램" 이 "인스타" 에, "유튜브 뮤직" 이 "유튜브" 에 먼저 걸리지 않는다.
     * STT 오표기도 받는다.
     */
    private val ALIASES: List<Pair<String, String>> = listOf(
        "유튜브 뮤직" to "유튜브 뮤직", "유튜브뮤직" to "유튜브 뮤직", "유투브 뮤직" to "유튜브 뮤직", "유투브뮤직" to "유튜브 뮤직",
        "인스타그램" to "인스타그램", "인스타" to "인스타그램",
        "카카오톡" to "카카오톡", "카톡" to "카카오톡", "쿠팡" to "쿠팡",
        "메시지" to "메시지", "문자" to "메시지",
        "교보문고" to "교보문고", "알라딘" to "알라딘",
        "네이버" to "네이버", "구글" to "구글",
        "유튜브" to "유튜브", "유투브" to "유튜브",
    )

    private val OPEN_PATTERN = Regex("^(.+?)\\s+(?:좀\\s+)?(?:열어|켜|실행해|실행|띄워)(?:\\s*(?:줘|줄래|주세요|봐|라))?요?[\\s.!?]*$")

    /** 긴 조사가 먼저 와야 "에서" 가 "에" 에 먼저 걸리지 않는다. */
    private val PARTICLES = listOf("에서는", "에서", "에는", "에")
}
