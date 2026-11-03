package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionType

/** 단계 하나의 진행 상태. 화면 계약(AemxContract)의 StepStatus 와 같은 뜻이다. */
enum class StepStatus { Pending, InProgress, Completed, Failed, Cancelled, Skipped }

/**
 * 계획에 든 단계 하나. 화면에 보여 줄 만큼만 담는다.
 *
 * 보낼 내용(value)은 일부러 담지 않는다. 이벤트가 화면과 로그로 흘러가도 사용자가 말한 내용이
 * 새지 않게 하려는 것이다. [stepId] 는 "p{계획번호}-s{index}" 라서, 재분석으로 계획이 바뀌어도
 * 같은 id 가 다른 단계를 가리키지 않는다.
 */
data class PlannedStep(
    val stepId: String,
    val action: ActionType,
    val target: String?,
    /** 이 단계가 속한 계획의 대상 앱 이름. */
    val app: String
)

/**
 * 오케스트레이터가 화면(오버레이)에 알리는 사건들.
 *
 * [PipelineState] 는 "지금 어디인가"만 알려 줘서 몇 번째 명령인지, 오래된 알림인지, 중단이
 * 확인됐는지를 알 수 없다. 이 흐름은 그 상태와 별개로 하나 더 내보낸다.
 *
 *  - [runId]: 명령 하나(submit 한 번)마다 새로 붙는다. 다른 runId 의 사건은 화면이 버린다.
 *  - [sequence]: 프로세스 안에서 계속 커진다. 이미 본 번호 이하는 화면이 버린다.
 */
sealed interface PipelineEvent {
    val runId: String
    val sequence: Long

    /** 명령 하나를 받았다. LLM 에 계획을 묻는 중이다. */
    data class RunStarted(
        override val runId: String,
        override val sequence: Long,
        val utterance: String
    ) : PipelineEvent

    /**
     * LLM 이 준 계획을 받아 실행할 단계가 정해졌다. 재분석으로 새 계획이 오면 다시 온다.
     * 이미 끝낸 단계는 화면이 그대로 두고, 아직 안 끝낸 단계만 새 계획으로 바꾼다.
     */
    data class PlanValidated(
        override val runId: String,
        override val sequence: Long,
        val planNumber: Int,
        val steps: List<PlannedStep>
    ) : PipelineEvent

    data class StepChanged(
        override val runId: String,
        override val sequence: Long,
        val stepId: String,
        val status: StepStatus,
        val reason: String? = null
    ) : PipelineEvent

    /** 화면이 예상과 달라 계획을 다시 받는 중이다. */
    data class Replanning(
        override val runId: String,
        override val sequence: Long,
        val replanCount: Int,
        val reason: String
    ) : PipelineEvent

    /** 사람이 직접 해야 해서 자동 진행을 멈췄다. */
    data class NeedsUser(
        override val runId: String,
        override val sequence: Long,
        val reason: String
    ) : PipelineEvent

    /**
     * 모든 단계를 끝냈다.
     *
     * @param evidence 마지막 단계에서 관찰로 확인한 것(화면 검증, 전송 확인). null 이면 확인한 것이 없어
     *   화면은 "성공"이 아니라 "전달했어요"로 낮춰서 보여 준다.
     */
    data class ResultVerified(
        override val runId: String,
        override val sequence: Long,
        val evidence: String?
    ) : PipelineEvent

    data class Failed(
        override val runId: String,
        override val sequence: Long,
        val message: String
    ) : PipelineEvent

    /** 사용자가 중단을 눌렀다. 아직 멈춘 것은 확인되지 않았다. */
    data class CancelRequested(
        override val runId: String,
        override val sequence: Long
    ) : PipelineEvent

    /** 중단이 확인됐다(명령 처리가 끝났고 실행모듈도 멈췄다). */
    data class Cancelled(
        override val runId: String,
        override val sequence: Long,
        val completedStepIds: Set<String>
    ) : PipelineEvent

    /**
     * 듣기가 시작됐다(호출어를 들었거나 듣기 버튼을 눌렀다). 명령 전이라 runId 는 음성 세션마다 새로 붙는다.
     * 화면은 "듣는 중" 파형을 띄운다.
     */
    data class ListeningStarted(
        override val runId: String,
        override val sequence: Long
    ) : PipelineEvent

    /** 듣는 중에 중간 인식 글이 왔다. 화면은 인식 중(흐린 파형)으로 바꾸고 글을 보여 준다. */
    data class Recognizing(
        override val runId: String,
        override val sequence: Long,
        val partial: String
    ) : PipelineEvent

    /** 듣기가 끝났다(명령으로 넘어갔거나 호출어 창이 닫혔거나 듣기를 껐다). 화면의 듣는 중 상태를 치운다. */
    data class ListeningEnded(
        override val runId: String,
        override val sequence: Long
    ) : PipelineEvent
}
