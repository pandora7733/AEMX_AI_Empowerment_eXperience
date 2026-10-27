package com.sihoo.aemx.pipeline.model

/** 한 step 을 실행하고 화면까지 검사한 결과. */
sealed interface StepOutcome {

    /** 그때의 화면. 재분석 프롬프트에 넣을 때 쓴다. */
    val screen: ScreenSnapshot

    /** 기대한 대로 화면이 바뀌었다. */
    data class Success(
        override val screen: ScreenSnapshot,
        /** 성공을 뒷받침하는 관찰(화면 검증, 전송 확인). 확인한 것이 없으면 null. */
        val evidence: String? = null
    ) : StepOutcome

    /** 실행은 됐는데 화면이 기대와 다르다. 재분석 대상. */
    data class Mismatch(
        override val screen: ScreenSnapshot,
        /** 사람이 읽을 수 있는 불일치 사유. 재분석 프롬프트에 그대로 넣는다. */
        val reason: String
    ) : StepOutcome

    /** 시간 안에 화면이 안정되지도, 기대를 만족하지도 않았다. */
    data class Timeout(
        override val screen: ScreenSnapshot,
        val waitedMs: Long
    ) : StepOutcome

    /** 실행모듈이 동작 자체를 못 했다(대상 못 찾음, 접근성 서비스 꺼짐 등). */
    data class ExecutorError(
        override val screen: ScreenSnapshot,
        val message: String,
        /** 재분석해도 풀릴 수 없는 실패(설치되지 않은 앱 등). true 면 바로 포기한다. */
        val unrecoverable: Boolean = false
    ) : StepOutcome

    /** 사람이 직접 처리해야 한다(캡차·2단계 인증). 자동 재시도하지 않는다. */
    data class NeedsUser(
        override val screen: ScreenSnapshot,
        val reason: String
    ) : StepOutcome
}

/** 실행한 step 하나의 기록. 재분석할 때 "여기까지 했다"고 LLM 에 알려주는 데 쓴다. */
data class ExecutedStep(
    val step: ActionStep,
    val outcome: StepOutcome
) {
    fun toPromptLine(): String {
        val result = when (outcome) {
            is StepOutcome.Success -> "성공"
            is StepOutcome.Mismatch -> "화면이 예상과 다름 — ${outcome.reason}"
            is StepOutcome.Timeout -> "시간 초과(${outcome.waitedMs}ms)"
            is StepOutcome.ExecutorError -> "실행 실패 — ${outcome.message}"
            is StepOutcome.NeedsUser -> "사람 개입 필요 — ${outcome.reason}"
        }
        return "${step.describe()} -> $result"
    }
}
