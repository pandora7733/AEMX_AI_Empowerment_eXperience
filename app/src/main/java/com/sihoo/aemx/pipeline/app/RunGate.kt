package com.sihoo.aemx.pipeline.app

/**
 * 명령을 지금 받을 수 있는지 정하는 순수 규칙. 안드로이드 없이 테스트한다.
 *
 * 입력은 지금 실제로 도는 것들이다. 예전에는 "실행 꺼짐" 표시 하나를 따로 두고 상태가 바뀔 때만 갱신해서,
 * 명령이 끝나는 순간의 낡은 값이 영영 남을 수 있었다(시연 중 모든 명령이 건너뛰어진 원인).
 */
internal object RunGate {

    /**
     * 명령을 받지 못하는 이유. 막힌 것이 없으면 null. 우선순위는 LLM 준비, 실기기 직접 경로, 오케스트레이터, 접근성 계획 순이다.
     */
    fun busyReason(
        modelPreparing: Boolean,
        directRunning: Boolean,
        orchestratorBusy: Boolean,
        planRunning: Boolean,
    ): String? = when {
        modelPreparing -> "LLM 준비 중"
        directRunning -> "실기기 직접 실행 중"
        orchestratorBusy -> "명령 처리 중"
        planRunning -> "접근성 계획 실행 중"
        else -> null
    }

    /** 실행 버튼을 잠가야 하는지. 접근성 계획은 버튼 잠금 대상이 아니다(예전 동작과 같다). */
    fun locked(modelPreparing: Boolean, directRunning: Boolean, orchestratorBusy: Boolean): Boolean =
        modelPreparing || directRunning || orchestratorBusy

    /**
     * 표시된 잠금 상태([mirrorLocked])가 실제로 돌고 있는 것이 없다([busyReason] 이 null)면 낡은 값이다.
     * 이때는 다시 계산해서 풀어야 한다.
     */
    fun isStale(mirrorLocked: Boolean, reason: String?): Boolean = mirrorLocked && reason == null
}
