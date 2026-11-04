package com.sihoo.aemx

/** 첫 실행 화면에서 지금 보일 것. 한 번에 하나만 보인다. */
enum class FirstRunScreen { Steps, Home }

/**
 * 첫 실행 단계. 이 순서가 화면의 순서이고, 시작할 때 아직 안 끝난 첫 단계부터 보여 준다.
 * 순서를 바꾸면 [FirstRunCursor] 테스트가 깨지므로 바꾸지 않는다.
 */
enum class FirstRunStep { MIC, NOTIFY, ACCESS, BACKGROUND, OVERLAY, CONTACTS, MODEL }

/** 단계 화면이 보여 줄 단계를 고르는 순수 함수. 안드로이드 없이 테스트한다. */
object FirstRunCursor {
    /**
     * 순서대로 보고 [finished] 에 없는 첫 단계의 번호(0부터). 모두 끝났으면 0 이다(쓰지 않는 값).
     * 접근성 연결 여부나 다른 단계의 상태는 시작 위치를 바꾸지 않는다.
     */
    fun firstPending(finished: Set<FirstRunStep>): Int =
        FirstRunStep.entries.indexOfFirst { it !in finished }.coerceAtLeast(0)
}

/**
 * 단계 화면과 홈 중 어느 쪽을 보일지 정하는 순수 함수. 안드로이드 없이 테스트한다.
 *
 * 규칙: 설정 다시 보기 중이면 단계. 접근성이 연결되지 않았으면 무조건 단계(접근성 단계가 보인다).
 * 나머지는 모든 단계가 끝났거나 이미 홈에 들어간 적이 있으면 홈이다.
 */
object FirstRunScreenPolicy {
    fun choose(
        reviewing: Boolean,
        accessibilityConnected: Boolean,
        allFinished: Boolean,
        homeReached: Boolean,
        walkthrough: Boolean = false,
    ): FirstRunScreen = when {
        reviewing -> FirstRunScreen.Steps
        // 첫 실행 안내 중에는 여섯 단계를 모두 보여 준다. 다 끝나도 홈으로 가지 않는다.
        walkthrough -> FirstRunScreen.Steps
        !accessibilityConnected -> FirstRunScreen.Steps
        allFinished || homeReached -> FirstRunScreen.Home
        else -> FirstRunScreen.Steps
    }
}
