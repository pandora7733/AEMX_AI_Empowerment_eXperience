package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ScreenSnapshot

/** 파이프라인이 지금 어느 단계에 있는지. UI 는 이 값만 보고 화면을 그린다. */
sealed interface PipelineState {

    data object Idle : PipelineState

    /** 마이크를 열고 사용자 발화를 기다리는 중. */
    data class Listening(val partial: String = "") : PipelineState

    /**
     * LLM 에 실행계획을 요청한 상태.
     * 실기기에서 20~35초가 걸리므로 UI 는 반드시 진행 중임을 보여 줘야 한다.
     */
    data class Planning(val utterance: String, val replanCount: Int) : PipelineState

    data class Executing(
        val step: ActionStep,
        val position: Int,
        val total: Int
    ) : PipelineState

    /** step 을 수행하고 화면이 안정되기를 기다리며 검증하는 중. */
    data class Verifying(val step: ActionStep) : PipelineState

    /** 자동 진행을 멈췄다. 사람이 캡차·2단계 인증 등을 처리해야 한다. */
    data class NeedsUser(val reason: String, val screen: ScreenSnapshot) : PipelineState

    /** 접근성 서비스, 마이크 권한, 계정 등록 같은 선행 조건이 빠졌다. */
    data class NeedsSetup(val reason: String) : PipelineState

    data class Finished(val message: String) : PipelineState

    /**
     * @param speakReason true 면 [reason] 이 사용자에게 읽어 줄 문장이다(가드의 거절 등).
     *  false 면 개발자용 설명이라 음성은 고정 실패 문구만 말한다.
     */
    data class Failed(val reason: String, val speakReason: Boolean = false) : PipelineState
}

/** 화면에 그대로 찍어 주기 위한 진행 로그 한 줄. */
data class PipelineLogEntry(
    val level: Level,
    val message: String,
    val at: Long = System.currentTimeMillis()
) {
    enum class Level { INFO, STEP, WARN, ERROR, JSON }
}

/**
 * 파이프라인 동작을 조절하는 값들.
 * 실기기에서 맞춰 볼 수 있게 한곳에 모아 둔다.
 */
data class PipelineConfig(
    /** 화면 이벤트가 이만큼 잠잠하면 "화면이 안정됐다"고 본다. */
    val quietPeriodMs: Long = 600L,

    /** 한 명령을 처리하며 허용하는 재분석 횟수. 넘으면 포기한다. */
    val maxReplans: Int = 3,

    /** 한 명령에서 실행할 수 있는 step 총량. 계획이 길어져도 여기서 끊는다. */
    val maxSteps: Int = 20,

    /** JSON 이 깨졌을 때 형식만 다시 달라고 재요청할 횟수. */
    val maxJsonRepairs: Int = 1,

    /**
     * 같은 화면에서 재분석이 이 횟수를 넘게 반복되면 진전이 없다고 보고 멈춘다.
     * 무한루프를 막는 마지막 안전장치다.
     */
    val sameScreenReplanLimit: Int = 2,

    /**
     * 중단을 요청한 뒤 명령 처리가 끝나기를 기다리는 최대 시간. LLM 추론은 도중에 멈출 수 없어
     * 20~35초가 걸려 돌아와야 끝나므로 그보다 넉넉하다. 넘으면 중단을 확인하지 못한 것으로 본다.
     */
    val cancelJoinTimeoutMs: Long = 60_000L,

    /** 명령 처리가 끝난 뒤 실행모듈(접근성 서비스)이 멈추기를 기다리는 최대 시간. */
    val cancelConfirmTimeoutMs: Long = 5_000L
)
