package com.sihoo.aemx.pipeline.port

import com.sihoo.aemx.pipeline.model.ActionType

/**
 * 실행 파트(AccessibilityService, 담당 공규태)를 감싸는 경계.
 *
 * ## 계약에서 중요한 두 가지
 *
 * 1. **step 루프는 실행모듈이 아니라 오케스트레이터가 돈다.** 화면 검증과 재분석이
 *    파이프라인 담당이라, 실행모듈은 동작 하나를 수행하고 수행 여부만 돌려준다.
 *    화면이 기대대로 바뀌었는지는 판단하지 않는다.
 *
 * 2. **[Command] 는 [com.sihoo.aemx.pipeline.model.ActionStep] 이 아니다.**
 *    `input_credential` 은 오케스트레이터가 계정 저장소에서 실제 값을 꺼내
 *    [Command.InputText] 로 바꿔서 넘긴다. 실행모듈은 자격증명 조회를 하지 않는다.
 */
interface ActionExecutor {

    /** 접근성 서비스가 켜져 있고 조작 가능한 상태인지. */
    fun isReady(): Boolean

    /**
     * 동작 하나를 수행한다.
     * 수행 "시도"까지만 책임지고, 화면 변화를 기다리지 않는다.
     */
    suspend fun execute(command: Command): ExecutionResult

    /** 실행모듈이 실제로 받는 명령. */
    sealed interface Command {

        /** 패키지명으로 앱을 실행한다. */
        data class LaunchApp(val packageName: String) : Command

        /**
         * 화면에서 [label] 로 보이는 요소를 누른다.
         * 접근성 노드의 text / contentDescription 을 훑어 찾는다.
         */
        data class Tap(val label: String) : Command

        /**
         * [fieldLabel] 로 찾은 입력란에 [text] 를 넣는다.
         * [sensitive] 가 true 면 로그에 값을 남기지 않는다(자격증명).
         */
        data class InputText(
            val fieldLabel: String?,
            val text: String,
            val sensitive: Boolean = false
        ) : Command

        data class Scroll(val direction: Direction) : Command

        enum class Direction { UP, DOWN }
    }
}

sealed interface ExecutionResult {
    /** 동작을 수행했다. 화면이 어떻게 됐는지는 오케스트레이터가 따로 확인한다. */
    data object Performed : ExecutionResult

    /** 지시한 대상을 화면에서 못 찾았다. 재분석 대상. */
    data class TargetNotFound(val description: String) : ExecutionResult

    /** 접근성 서비스가 꺼져 있거나 아직 연결되지 않았다. */
    data class NotReady(val message: String) : ExecutionResult

    /** 대상은 찾았는데 동작이 실패했다(클릭 불가 노드 등). */
    data class Failed(val message: String) : ExecutionResult

    /** 실행모듈이 아직 지원하지 않는 동작. */
    data class Unsupported(val action: ActionType) : ExecutionResult
}
