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
 *    오케스트레이터가 step 을 실행모듈이 이해하는 명령으로 바꿔서 넘긴다. 예를 들어
 *    `open_app` 의 앱 이름은 패키지명으로 바뀐다.
 */
interface ActionExecutor {

    /** 접근성 서비스가 켜져 있고 조작 가능한 상태인지. */
    fun isReady(): Boolean

    /**
     * 실행모듈이 하던 동작을 멈췄는지. 중단을 요청한 뒤 정말 멈췄는지 확인하는 데 쓴다.
     * 알 수 없으면 true(멈춘 것으로 본다).
     */
    fun isIdle(): Boolean = true

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

        /** [fieldLabel] 로 찾은 입력란에 [text] 를 넣는다. */
        data class InputText(
            val fieldLabel: String?,
            val text: String
        ) : Command

        /**
         * 앱의 검색 결과 화면을 바로 연다(네이버/구글/유튜브).
         * 화면에서 검색창을 찾아 누르는 것보다 안정적이라 실행모듈이 따로 제공한다.
         */
        data class Search(val appName: String, val query: String) : Command

        /** 유튜브 검색 결과의 첫 번째 동영상을 재생한다. */
        data object PlayFirst : Command

        /** 유튜브/유튜브 뮤직 검색 결과의 [ordinal]번째(2 이상) 항목을 재생한다. 첫 번째는 [PlayFirst]. */
        data class PlayNth(val ordinal: Int) : Command

        /**
         * 쇼츠/릴스에서 다음 영상으로 넘긴다.
         * [delay] 가 있으면 그만큼 기다린 뒤 넘긴다("10s", "500ms", "3").
         *
         * 영상이 실제로 끝나는 순간은 접근성 정보로 알 수 없어서, 시간 지연이나
         * 즉시 넘기기만 지원한다(ACCESSIBILITY/README.md 참고).
         */
        data class NextVideo(val delay: String?) : Command

        data class Scroll(val direction: Direction) : Command

        /**
         * 카카오톡·문자(문자는 [appName] 이 "메시지")로 [target] 에게 [value] 를 보낸다.
         * 받는 사람이 정확히 하나로 정해지지 않거나 화면이 기대와 다르면 보내지 않고 실패한다.
         */
        data class SendMessage(val appName: String, val target: String, val value: String) : Command

        enum class Direction { UP, DOWN }
    }
}

sealed interface ExecutionResult {
    /**
     * 동작을 수행했다. 화면이 어떻게 됐는지는 오케스트레이터가 따로 확인한다.
     *
     * @param evidence 실행모듈이 직접 확인한 결과(메시지 전송 확인 등). 없으면 null.
     */
    data class Performed(val evidence: String? = null) : ExecutionResult

    /** 지시한 대상을 화면에서 못 찾았다. 재분석 대상. */
    data class TargetNotFound(val description: String) : ExecutionResult

    /** 접근성 서비스가 꺼져 있거나 아직 연결되지 않았다. */
    data class NotReady(val message: String) : ExecutionResult

    /** 대상은 찾았는데 동작이 실패했다(클릭 불가 노드 등). */
    data class Failed(val message: String) : ExecutionResult

    /** 실행모듈이 아직 지원하지 않는 동작. */
    data class Unsupported(val action: ActionType) : ExecutionResult
}
