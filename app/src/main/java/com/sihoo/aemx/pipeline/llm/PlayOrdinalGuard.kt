package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.accessibility.Ordinal
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * "두 번째 영상 틀어줘" 처럼 순번을 말했는데 모델이 play_first 를 순번 없이 내거나 두 번 내는 것을 바로잡는다.
 *
 * 1.7B 모델은 "두 번째" 를 play_first 두 번으로 풀어 내곤 한다. 그러면 첫 영상을 누르고 또 누른다.
 * 발화에 "두 번째/2번째/둘째" 가 있고 계획에 play_first 가 있으면, play_first 를 맨 앞 하나만 남기고
 * value 를 그 순번으로 채운다(모델이 다른 순번을 적었어도 사용자가 말한 쪽이 이긴다).
 * 순번을 말하지 않았거나 첫 번째면 계획은 그대로다.
 */
object PlayOrdinalGuard {

    fun apply(utterance: String, plan: ActionPlan): ActionPlan {
        val ordinal = Ordinal.inUtterance(utterance)?.takeIf { it >= 2 } ?: return plan
        val first = plan.steps.indexOfFirst { it.action == ActionType.PLAY_FIRST }
        if (first < 0) return plan

        val value = ordinal.toString()
        val steps = plan.steps
            .filterIndexed { position, step -> step.action != ActionType.PLAY_FIRST || position == first }
            .map { step -> if (step.action == ActionType.PLAY_FIRST) step.copy(value = value) else step }
            .mapIndexed { position, step -> if (step.index == position) step else step.copy(index = position) }
        return if (steps == plan.steps) plan else plan.copy(steps = steps)
    }
}
