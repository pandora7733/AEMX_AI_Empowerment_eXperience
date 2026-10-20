package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.accessibility.PlanOutcome

/**
 * 접근성 서비스의 "계획 하나를 통째로 실행하고 끝나면 알려 주는" 입구.
 *
 * 서비스는 static 입구(`AemxAccessibilityService` 의 companion)로만 만질 수 있고,
 * 안드로이드 서비스는 JVM 단위테스트에서 띄울 수 없다. 그래서 어댑터는 이 인터페이스만 보고
 * 짜 두고, 테스트에서는 가짜로 갈아 끼운다.
 */
interface PlanBridge {

    /** 접근성 서비스가 켜져 연결돼 있는지. */
    fun isConnected(): Boolean

    /**
     * 계획 JSON 을 서비스에 제출한다. 실제 구현은 **메인 스레드에서만** 받는다.
     * 파싱 실패, 이미 실행 중, 서비스 미준비는 실패 Result 로 돌아온다.
     *
     * @param announceCompletion 계획이 끝났을 때 서비스가 완료 음성 안내를 할지. step 마다 한 개짜리
     *   계획을 내는 쪽은 매번 말하지 않도록 false 로 보낸다.
     */
    fun submit(json: String, announceCompletion: Boolean = true): Result<Unit>

    /** 계획이 끝나면(성공이든 실패든) 불릴 콜백. 서비스에는 하나만 걸린다. null 이면 해제. */
    fun setObserver(callback: ((PlanOutcome) -> Unit)?)

    /** 돌고 있는 계획을 중단한다. */
    fun cancel()
}

/** [AemxAccessibilityService] 의 static 입구를 그대로 쓰는 실제 구현. */
object ServicePlanBridge : PlanBridge {
    override fun isConnected(): Boolean = AemxAccessibilityService.isConnected()

    override fun submit(json: String, announceCompletion: Boolean): Result<Unit> =
        AemxAccessibilityService.submitActionPlan(json, announceCompletion)

    override fun setObserver(callback: ((PlanOutcome) -> Unit)?) =
        AemxAccessibilityService.setPlanObserver(callback)

    override fun cancel() = AemxAccessibilityService.cancelActionPlan()
}
