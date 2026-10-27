package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantReducer
import com.sihoo.aemx.assistant.AssistantUiState
import com.sihoo.aemx.pipeline.StepStatus

/** 캡슐에 보이는 글자와 버튼. [visible] 이 false 면 캡슐을 숨긴다. */
data class CapsuleText(
    val visible: Boolean,
    val title: String = "",
    /** 보조 줄("단계 2/3"). 없으면 빈 문자열. */
    val subtitle: String = "",
    val showStop: Boolean = false,
    val stopEnabled: Boolean = true
)

/** 화면 상태를 캡슐 문구로 바꾼다. 순수 함수라 안드로이드 없이 테스트한다. */
object CapsuleTextFactory {

    const val CANCELLING = "중단하는 중…"
    const val UNDERSTANDING = "요청을 이해하고 있어요…"
    const val PLANNING = "실행을 준비하고 있어요…"
    const val REPLANNING = "화면을 다시 확인하고 있어요…"
    const val LISTENING = "듣고 있어요…"
    const val CONFIRMING = "이렇게 보낼까요?"
    const val NEEDS_USER = "직접 해 주셔야 해요"
    const val NEEDS_USER_SUB = "AEMX · 기다리는 중"
    const val CANCELLED = "중단했어요"
    const val ERROR = "문제가 생겨 멈췄어요"
    const val VERIFIED_SUB = "결과 확인됨"

    fun from(state: AssistantUiState): CapsuleText {
        if (state.phase == AssistantPhase.Idle) return CapsuleText(visible = false)

        val running = !state.phase.isTerminal
        val title = when {
            // 중단을 눌렀지만 멈춘 것이 확인되기 전에는 "중단했어요"가 아니라 "중단하는 중…"이다.
            running && state.cancellationRequested -> CANCELLING
            else -> when (state.phase) {
                AssistantPhase.Idle -> ""
                AssistantPhase.Listening -> LISTENING
                AssistantPhase.Recognizing -> state.transcript.ifBlank { LISTENING }
                AssistantPhase.Understanding -> UNDERSTANDING
                AssistantPhase.Planning -> PLANNING
                AssistantPhase.ConfirmingAction -> CONFIRMING
                AssistantPhase.Executing -> currentStepText(state)
                AssistantPhase.Replanning -> REPLANNING
                AssistantPhase.NeedsUser -> NEEDS_USER
                AssistantPhase.Success -> state.result?.summary ?: ""
                AssistantPhase.Delivered -> AssistantReducer.DELIVERED_MESSAGE
                AssistantPhase.Error -> state.error?.takeIf { it.isNotBlank() } ?: ERROR
                AssistantPhase.Cancelled -> CANCELLED
            }
        }

        val subtitle = when {
            running && state.cancellationRequested -> ""
            state.phase == AssistantPhase.NeedsUser -> NEEDS_USER_SUB
            state.phase == AssistantPhase.Success -> VERIFIED_SUB
            state.phase == AssistantPhase.Executing || state.phase == AssistantPhase.Replanning ->
                stepCounter(state)
            else -> ""
        }

        return CapsuleText(
            visible = true,
            title = title,
            subtitle = subtitle,
            showStop = running,
            stopEnabled = !state.cancellationRequested
        )
    }

    /** 지금 하는 단계 문장. 하는 단계가 없으면(막 끝낸 직후) 다음 단계, 그것도 없으면 일반 문구. */
    private fun currentStepText(state: AssistantUiState): String {
        val step = state.steps.firstOrNull { it.status == StepStatus.InProgress }
            ?: state.steps.firstOrNull { it.status == StepStatus.Pending }
        return step?.progressText?.takeIf { it.isNotBlank() } ?: "실행하고 있어요…"
    }

    /** "단계 n/m". n 은 끝낸 단계 수 + 1(하는 중인 단계), 다 끝났으면 m 을 넘지 않는다. */
    private fun stepCounter(state: AssistantUiState): String {
        val total = state.steps.size
        if (total == 0) return ""
        val done = state.steps.count { it.status == StepStatus.Completed }
        return "단계 ${minOf(done + 1, total)}/$total"
    }
}

/** 캡슐을 보일지. 숨기기로 한 명령(runId)과 개발 화면의 끄기 스위치를 함께 본다. */
object CapsuleVisibility {

    /** 성공·전달 뒤 캡슐이 저절로 사라지기까지의 시간(디자인 가이드의 6.5초). 오류와 취소는 저절로 지우지 않는다. */
    const val AUTO_HIDE_MS = 6_500L

    fun shouldShow(state: AssistantUiState, enabled: Boolean, dismissedRunId: String?): Boolean =
        enabled && state.phase != AssistantPhase.Idle && state.runId != dismissedRunId

    fun autoHides(phase: AssistantPhase): Boolean =
        phase == AssistantPhase.Success || phase == AssistantPhase.Delivered
}
