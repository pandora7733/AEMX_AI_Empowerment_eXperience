package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantReducer
import com.sihoo.aemx.assistant.AssistantUiState
import com.sihoo.aemx.assistant.UserMessages
import com.sihoo.aemx.pipeline.StepStatus

/** 듣는 중 캡슐의 모양. 아니면 [None] 이고 접힌 캡슐이다. */
enum class VoiceState { None, Listening, Recognizing }

/** 캡슐에 보이는 글자와 버튼. [visible] 이 false 면 캡슐을 숨긴다. */
data class CapsuleText(
    val visible: Boolean,
    val title: String = "",
    /** 보조 줄("단계 2/3"). 없으면 빈 문자열. */
    val subtitle: String = "",
    val showStop: Boolean = false,
    val stopEnabled: Boolean = true,
    /** 듣는 중이면 높이 88 캡슐에 파형과 광원을 켠다. 중단을 누르면 [None] 이 된다. */
    val voice: VoiceState = VoiceState.None,
    /** 이해 단계라 글자가 약하게 맥박친다(1800ms). */
    val pulse: Boolean = false,
)

/** 화면 상태를 캡슐 문구로 바꾼다. 순수 함수라 안드로이드 없이 테스트한다. */
object CapsuleTextFactory {

    const val CANCELLING = "중단하는 중…"
    const val UNDERSTANDING = "요청을 이해하고 있어요…"
    const val PLANNING = "실행을 준비하고 있어요…"
    const val REPLANNING = "화면을 다시 확인하고 있어요…"
    const val LISTENING = "듣고 있어요…"

    /** 인식 중(흐린 파형)에는 중간 결과 글자를 보이지 않는다. 짧은 고정 문구만 보인다. */
    const val RECOGNIZING = "알아듣고 있어요…"

    /** 최종 문장을 보여 주는 줄의 길이 상한. 넘으면 말줄임으로 자른다. */
    const val SENTENCE_MAX = 28
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
                AssistantPhase.Recognizing -> RECOGNIZING
                AssistantPhase.Understanding -> UNDERSTANDING
                AssistantPhase.Planning -> PLANNING
                AssistantPhase.ConfirmingAction -> CONFIRMING
                AssistantPhase.Executing -> currentStepText(state)
                AssistantPhase.Replanning -> REPLANNING
                AssistantPhase.NeedsUser -> NEEDS_USER
                AssistantPhase.Success -> state.result?.summary ?: ""
                AssistantPhase.Delivered -> AssistantReducer.DELIVERED_MESSAGE
                // 개발 로그 문장은 사용자에게 그대로 내보내지 않고 짧은 안내로 바꾼다.
                AssistantPhase.Error -> UserMessages.forFailure(state.error)
                AssistantPhase.Cancelled -> CANCELLED
            }
        }

        val subtitle = when {
            running && state.cancellationRequested -> ""
            state.phase == AssistantPhase.NeedsUser -> NEEDS_USER_SUB
            state.phase == AssistantPhase.Success -> VERIFIED_SUB
            state.phase == AssistantPhase.Executing || state.phase == AssistantPhase.Replanning ->
                stepCounter(state)
            // 알아들은 문장은 이해 단계에서 한 줄로 한 번만 보인다(글자가 계속 바뀌는 중간 결과는 보이지 않는다).
            state.phase == AssistantPhase.Understanding -> sentenceLine(state.transcript)
            else -> ""
        }

        return CapsuleText(
            visible = true,
            title = title,
            subtitle = subtitle,
            showStop = running,
            stopEnabled = !state.cancellationRequested,
            voice = voiceFor(state),
            pulse = running && !state.cancellationRequested && state.phase == AssistantPhase.Understanding,
        )
    }

    /** 중단을 눌렀으면 파형과 광원을 바로 멈춘다(접힌 캡슐로 돌아간다). */
    private fun voiceFor(state: AssistantUiState): VoiceState {
        if (state.cancellationRequested) return VoiceState.None
        return when (state.phase) {
            AssistantPhase.Listening -> VoiceState.Listening
            AssistantPhase.Recognizing -> VoiceState.Recognizing
            else -> VoiceState.None
        }
    }

    /** 최종 문장을 한 줄로. 줄바꿈을 없애고 길면 자른다. 비면 빈 문자열. */
    internal fun sentenceLine(sentence: String): String {
        val oneLine = sentence.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return ""
        return if (oneLine.length > SENTENCE_MAX) oneLine.take(SENTENCE_MAX) + "…" else oneLine
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
