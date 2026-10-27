package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase

/** 캡슐을 화면 어디에 붙이는지. */
enum class Dock { BOTTOM, TOP }

/**
 * 캡슐을 위에 둘지 아래에 둘지.
 *
 * 기본은 아래다(카톡 공유 시트가 아래에서 올라와도 "보내기"는 노드 클릭이라 가려도 실행에 영향이
 * 없다는 판단이다). 직접 처리(NeedsUser)만 위에 둔다 — 사용자가 로그인 같은 일을 하는 화면은 아래쪽에
 * 입력창이나 시트가 있는 일이 많아서 가리지 않으려는 것이다.
 */
object DockPolicy {
    fun dockFor(phase: AssistantPhase): Dock =
        if (phase == AssistantPhase.NeedsUser) Dock.TOP else Dock.BOTTOM
}
