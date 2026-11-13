package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.accessibility.Ordinal
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 장바구니 담기(add_to_cart)는 사용자가 담으라고 분명히 말했을 때만 계획에 남긴다.
 *
 * 담기는 사용자의 쿠팡 계정에 실제로 반영된다. 모델이 "검색해줘" 같은 말에 담기를 붙여 내도 실행되면 안 되므로,
 * 프롬프트에만 맡기지 않고 발화를 직접 본다. 발화에 장바구니(카트)와 담는 말(담/넣/추가)이 함께 있을 때만 명시로 본다.
 * "장바구니 열어줘", "장바구니 눌러줘" 는 담으라는 말이 아니다. "담지 마" 같은 부정도 아니다.
 *
 * 명시했는데 모델이 담기를 내지 않았거나 "장바구니 담기" 를 누르기(tap)로 냈으면, 쿠팡 계획에 한해 담기 하나로 바로잡는다.
 * 1.7B 모델은 새 동작을 자주 빠뜨리기 때문이다. 순번("두 번째")을 말했으면 value 로 채운다.
 */
object CartGuard {

    /** 사용자가 장바구니에 담으라고 분명히 말했는가. */
    fun asksToAdd(utterance: String): Boolean =
        CART.containsMatchIn(utterance) && PUT.containsMatchIn(utterance) && !NEGATED.containsMatchIn(utterance)

    fun apply(utterance: String, plan: ActionPlan): ActionPlan {
        val hasCartStep = plan.steps.any { it.action == ActionType.ADD_TO_CART }
        if (!asksToAdd(utterance)) {
            if (!hasCartStep) return plan
            // 말하지 않은 담기는 뺀다. 남는 단계가 없으면 앱만 연다.
            val kept = plan.steps.filter { it.action != ActionType.ADD_TO_CART }
                .ifEmpty { listOf(ActionStep(index = 0, action = ActionType.OPEN_APP, target = plan.app)) }
            return plan.copy(steps = reindex(kept))
        }
        // 담기는 쿠팡에서만 한다. 다른 앱의 계획은 건드리지 않는다.
        if (AppLauncher.packageFor(plan.app) != AppLauncher.COUPANG_PACKAGE) return plan

        val ordinal = Ordinal.inUtterance(utterance)?.takeIf { it >= 2 }
        // 담기는 하나만 남기고, 모델이 대신 낸 "장바구니 담기" 누르기는 뺀다(같은 일을 두 번 하지 않는다).
        val base = plan.steps.filter { it.action != ActionType.ADD_TO_CART && !isCartTap(it) }
        val cart = ActionStep(
            index = base.size,
            action = ActionType.ADD_TO_CART,
            target = CART_TARGET,
            value = ordinal?.toString(),
        )
        val steps = reindex(base + cart)
        return if (steps == plan.steps) plan else plan.copy(steps = steps)
    }

    private fun isCartTap(step: ActionStep): Boolean =
        step.action == ActionType.TAP && step.target?.let { CART.containsMatchIn(it) || it.contains("담기") } == true

    private fun reindex(steps: List<ActionStep>): List<ActionStep> =
        steps.mapIndexed { position, step -> if (step.index == position) step else step.copy(index = position) }

    private const val CART_TARGET = "첫 번째 상품"

    private val CART = Regex("장바구니|카트")
    private val PUT = Regex("담|넣|추가")

    /** "담지 마", "넣지 말고", "추가하지 마", "안 담아도" 처럼 하지 말라는 말. */
    private val NEGATED = Regex("(담|넣)지\\s*(마|말|않)|추가\\s*하지\\s*(마|말|않)|안\\s*(담|넣)")
}
