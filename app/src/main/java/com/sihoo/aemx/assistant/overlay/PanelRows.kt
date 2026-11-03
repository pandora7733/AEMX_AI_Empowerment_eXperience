package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantUiState
import com.sihoo.aemx.pipeline.StepStatus

/** 패널의 단계 한 줄. 상태는 아이콘만으로 두지 않고 글자로도 보여 준다(디자인 원칙). */
data class PanelRow(
    val label: String,
    /** "완료", "진행 중", "대기", "확인 필요", "취소", "건너뜀", "실패". */
    val statusWord: String,
    val current: Boolean,
)

/** 패널에 쓰는 순수 계산. 안드로이드 없이 테스트한다. */
object PanelRows {

    fun statusWord(status: StepStatus): String = when (status) {
        StepStatus.Completed -> "완료"
        StepStatus.InProgress -> "진행 중"
        StepStatus.Pending -> "대기"
        StepStatus.Failed -> "확인 필요"
        StepStatus.Cancelled -> "취소"
        StepStatus.Skipped -> "건너뜀"
    }

    /** 단계 목록 줄. 하는 중인 단계(없으면 다음 대기 단계)를 current 로 표시한다. */
    fun from(state: AssistantUiState): List<PanelRow> {
        val currentId = state.steps.firstOrNull { it.status == StepStatus.InProgress }?.id
            ?: state.steps.firstOrNull { it.status == StepStatus.Pending }?.id
        return state.steps.map { step ->
            PanelRow(
                label = step.label,
                statusWord = statusWord(step.status),
                current = step.id == currentId,
            )
        }
    }
}
