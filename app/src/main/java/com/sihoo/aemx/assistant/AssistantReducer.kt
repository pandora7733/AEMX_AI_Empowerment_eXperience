package com.sihoo.aemx.assistant

import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.StepStatus

/**
 * 사건을 받아 화면 상태를 만든다. 순수 함수라서 안드로이드 없이 테스트한다.
 *
 * 지키는 규칙:
 *  1. 이미 본 번호 이하이거나 다른 명령(runId)의 사건은 버린다. 새 명령의 시작(RunStarted)만
 *     runId 가 달라도 번호가 더 크면 받는다.
 *  2. 끝 단계(성공·전달·오류·취소)에 이른 명령은 더 바뀌지 않는다.
 *  3. 중단을 요청한 뒤에는 Cancelled 와 Failed 만 받는다. Cancelled 는 중단 요청이 있었을 때만 받는다 —
 *     중단이 확인되기 전에는 "취소됨"이 될 수 없다.
 *  4. 모든 단계가 끝난 결과에 증거가 있을 때만 Success, 증거가 없으면 Delivered 다.
 */
object AssistantReducer {

    const val DELIVERED_MESSAGE = "명령을 전달했어요. 결과를 확인해 주세요"

    fun reduce(state: AssistantUiState, event: PipelineEvent): AssistantUiState {
        if (event.sequence <= state.sequence) return state

        if (event is PipelineEvent.RunStarted) {
            return AssistantUiState(
                runId = event.runId,
                sequence = event.sequence,
                phase = AssistantPhase.Understanding,
                transcript = event.utterance
            )
        }

        if (event.runId != state.runId) return state
        if (state.phase.isTerminal) return state
        if (state.cancellationRequested && event !is PipelineEvent.Cancelled && event !is PipelineEvent.Failed) {
            return state
        }

        val next = when (event) {
            is PipelineEvent.RunStarted -> state // 위에서 처리했다.

            is PipelineEvent.PlanValidated -> {
                // 끝낸 단계는 그대로 두고 아직 안 끝낸 단계만 새 계획으로 바꾼다.
                val kept = state.steps.filter { it.status == StepStatus.Completed }
                val added = event.steps.map {
                    AssistantStep(
                        id = it.stepId,
                        label = StepLabelFormatter.label(it.action, it.target, it.app),
                        doneText = StepLabelFormatter.doneText(it.action, it.target, it.app),
                        status = StepStatus.Pending
                    )
                }
                // 재분석 중이면 첫 단계가 시작할 때까지 재분석 표시를 둔다.
                val phase = if (state.phase == AssistantPhase.Replanning) AssistantPhase.Replanning else AssistantPhase.Planning
                state.copy(phase = phase, steps = kept + added)
            }

            is PipelineEvent.StepChanged -> {
                if (state.steps.none { it.id == event.stepId }) return state
                val steps = state.steps.map {
                    if (it.id == event.stepId) it.copy(status = event.status, reason = event.reason) else it
                }
                val phase = if (event.status == StepStatus.InProgress) AssistantPhase.Executing else state.phase
                state.copy(phase = phase, steps = steps)
            }

            is PipelineEvent.Replanning ->
                state.copy(phase = AssistantPhase.Replanning, replanCount = event.replanCount)

            is PipelineEvent.NeedsUser ->
                state.copy(phase = AssistantPhase.NeedsUser, userActionPrompt = event.reason, expanded = true)

            is PipelineEvent.ResultVerified -> verified(state, event) ?: return state

            is PipelineEvent.Failed ->
                state.copy(phase = AssistantPhase.Error, error = event.message, expanded = true)

            is PipelineEvent.CancelRequested ->
                state.copy(cancellationRequested = true)

            is PipelineEvent.Cancelled -> {
                if (!state.cancellationRequested) return state
                // 끝낸 단계는 그대로 두고, 끝내지 못한 단계만 취소로 표시한다.
                val steps = state.steps.map {
                    if (it.id in event.completedStepIds || it.status == StepStatus.Completed) {
                        it.copy(status = StepStatus.Completed)
                    } else {
                        it.copy(status = StepStatus.Cancelled)
                    }
                }
                state.copy(phase = AssistantPhase.Cancelled, steps = steps)
            }
        }
        return next.copy(sequence = event.sequence)
    }

    /**
     * 결과를 받아들인다. 모든 단계가 끝난 상태에서만 받는다(실행 중에 온 결과는 믿지 않는다).
     * 증거가 있으면 Success, 없으면 Delivered 다. 받지 않으면 null.
     */
    private fun verified(state: AssistantUiState, event: PipelineEvent.ResultVerified): AssistantUiState? {
        val inRun = state.phase == AssistantPhase.Executing || state.phase == AssistantPhase.Planning
        if (!inRun) return null
        if (state.steps.any { it.status != StepStatus.Completed && it.status != StepStatus.Skipped }) return null

        val summary = state.steps.lastOrNull()?.doneText ?: DELIVERED_MESSAGE
        val verified = !event.evidence.isNullOrBlank() && state.steps.isNotEmpty()
        return state.copy(
            phase = if (verified) AssistantPhase.Success else AssistantPhase.Delivered,
            result = VerifiedResult(state.runId, summary, event.evidence?.takeIf { it.isNotBlank() })
        )
    }
}
