package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.StepStatus

/**
 * 화면(캡슐과 패널)이 보여 줄 단계. 디자인 계약(AemxContract)의 AssistantPhase 에 Delivered 를 더했다.
 *
 * 앞쪽 세 개(Listening, Recognizing, ConfirmingAction)는 아직 이벤트가 닿지 않는다. 듣기와 인식은
 * 개발 화면이 소유한 음성 쪽에서, 보내기 확인은 다음 단계에서 이어 붙일 자리만 남겨 둔 것이다.
 */
enum class AssistantPhase {
    Idle,
    Listening,
    Recognizing,

    /** 말을 받아 LLM 이 요청을 이해하는 중(추론에 20~35초가 걸린다). */
    Understanding,

    /** 계획을 받아 실행할 단계가 정해졌고 아직 첫 단계를 시작하지 않았다. */
    Planning,

    /** 보내기 전에 받는 사람과 내용을 사용자에게 확인받는 중. 아직 쓰지 않는다. */
    ConfirmingAction,
    Executing,

    /** 화면이 예상과 달라 계획을 다시 받는 중. */
    Replanning,

    /** 사람이 직접 해야 해서 멈췄다. */
    NeedsUser,

    /** 모든 단계를 끝냈고 결과를 관찰로 확인했다. */
    Success,

    /** 모든 단계를 끝냈지만 결과를 확인한 증거가 없다. "성공"이라고 말하지 않는다. */
    Delivered,
    Error,
    Cancelled;

    /** 더 바뀌지 않는 끝 단계. 다음 명령은 새 runId 로 시작한다. */
    val isTerminal: Boolean
        get() = this == Success || this == Delivered || this == Error || this == Cancelled
}

/** 패널에 보이는 단계 한 줄. 글자는 [StepLabelFormatter] 가 만든 것이라 JSON 이나 보낼 내용이 없다. */
data class AssistantStep(
    val id: String,
    val label: String,
    /** 끝난 뒤 결과를 말할 때 쓰는 문장("유튜브를 열었어요"). */
    val doneText: String,
    val status: StepStatus,
    val reason: String? = null
)

/** 관찰로 확인한 결과. [evidence] 가 비어 있으면 [AssistantPhase.Delivered] 다. */
data class VerifiedResult(
    val runId: String,
    val summary: String,
    val evidence: String?
)

data class AssistantUiState(
    val runId: String = "",
    val sequence: Long = 0,
    val phase: AssistantPhase = AssistantPhase.Idle,
    /** 사용자가 한 말. */
    val transcript: String = "",
    val steps: List<AssistantStep> = emptyList(),
    val canCancel: Boolean = true,
    /** 중단을 눌렀지만 아직 멈춘 것이 확인되지 않았다. 이 동안 "중단하는 중…"을 보여 준다. */
    val cancellationRequested: Boolean = false,
    val replanCount: Int = 0,
    /** 사람이 해야 할 일. [AssistantPhase.NeedsUser] 일 때만 있다. */
    val userActionPrompt: String? = null,
    val result: VerifiedResult? = null,
    val error: String? = null,
    val expanded: Boolean = false
) {
    /**
     * 화면이 "성공"을 보여도 되는지. 모든 단계가 끝났고 그 결과를 관찰로 확인했을 때만 true 다.
     * 단순히 명령을 전달한 것(Delivered)은 성공이 아니다.
     */
    val mayShowSuccess: Boolean
        get() = phase == AssistantPhase.Success &&
            result?.runId == runId &&
            result?.evidence?.isNotBlank() == true &&
            !cancellationRequested &&
            steps.isNotEmpty() &&
            steps.all { it.status == StepStatus.Completed || it.status == StepStatus.Skipped }
}
