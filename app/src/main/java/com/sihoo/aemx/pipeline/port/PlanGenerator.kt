package com.sihoo.aemx.pipeline.port

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ExecutedStep
import com.sihoo.aemx.pipeline.model.ScreenSnapshot

/**
 * LLM 파트(`LLM/LlamaDemo` 의 `com.arm.aichat.InferenceEngine`)를 감싸는 경계.
 *
 * app 모듈은 InferenceEngine 에 직접 의존하지 않는다. LLM 모듈이 합류하면
 * `sendUserPrompt()` 가 흘려주는 토큰 Flow 를 모아 문자열로 합치는 어댑터 하나만 추가하면 된다.
 */
interface PlanGenerator {

    fun isReady(): Boolean

    /**
     * 추론은 실기기에서 20~35초가 걸린다(`LLM/README.md` 측정값).
     * 호출자는 반드시 메인스레드 밖에서 부르고, 진행 상태를 사용자에게 보여 줘야 한다.
     */
    suspend fun generate(request: PlanRequest): PlanResult
}

/**
 * LLM 에 넘기는 요청.
 *
 * ## 대화 히스토리에 관하여
 *
 * `InferenceEngine.resetConversation()` 이 시스템 프롬프트는 두고 그 뒤의 대화 기록(KV 캐시와
 * 채팅 목록)만 비운다. 시스템 프롬프트를 다시 처리하지 않아 비용이 거의 없다.
 *
 * 어댑터(`LlamaPlanGenerator`)는 [Mode.INITIAL] 과 [Mode.REPLAN] 요청 전에 이를 부른다.
 * REPLAN 프롬프트는 원래 명령, 지금까지 한 것, 현재 화면을 모두 담은 자기완결형이라 이전
 * 대화가 필요 없다. [Mode.REPAIR_JSON] 은 비우지 않는다. 프롬프트에 깨진 응답이 없어서
 * 기록에 남은 직전 답을 봐야 형식만 고칠 수 있다.
 */
data class PlanRequest(
    val utterance: String,
    val mode: Mode,
    /** 재분석일 때의 현재 화면. [Mode.INITIAL] 에서는 null. */
    val screen: ScreenSnapshot? = null,
    /** 지금까지 실행한 step 들. 재분석 때 "여기까지 했다"고 알려 준다. */
    val history: List<ExecutedStep> = emptyList(),
    /** 왜 재분석하는지 한 줄 사유. */
    val reason: String? = null,
    /** JSON 복구 요청일 때, 직전에 모델이 뱉은 깨진 응답. */
    val previousRaw: String? = null,
    /** JSON 복구 요청일 때, 파서가 낸 오류 메시지. */
    val parseError: String? = null
) {
    enum class Mode {
        /** 새 사용자 명령. 가능하면 이전 대화를 끊고 시작한다. */
        INITIAL,

        /** 실행 중 화면이 예상과 달라 다시 판단을 요청한다. */
        REPLAN,

        /** 직전 응답의 JSON 이 깨져서 형식만 다시 달라고 요청한다. */
        REPAIR_JSON
    }
}

sealed interface PlanResult {
    data class Success(
        val plan: ActionPlan,
        val raw: String,
        val warnings: List<String> = emptyList()
    ) : PlanResult

    /** 응답은 왔는데 JSON 으로 읽을 수 없다. [PlanRequest.Mode.REPAIR_JSON] 으로 재요청 대상. */
    data class Malformed(val raw: String, val reason: String) : PlanResult

    /** 추론 자체가 실패했다(모델 미로딩, 메모리 부족 등). 재요청해도 소용없다. */
    data class EngineError(val message: String) : PlanResult

    /**
     * 가드가 계획을 거절했다. 재요청도 재분석도 하지 않고 바로 끝내며, [message] 는 접두어 없이
     * 사용자에게 화면과 음성으로 그대로 알린다. 예: "인스타그램 메시지는 아직 보낼 수 없어요".
     */
    data class Refused(val message: String) : PlanResult
}
