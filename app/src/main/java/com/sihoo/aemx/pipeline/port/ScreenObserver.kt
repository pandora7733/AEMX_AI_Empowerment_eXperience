package com.sihoo.aemx.pipeline.port

import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 화면 변화를 관찰하는 경계.
 *
 * 실제 구현은 공규태의 AccessibilityService 가 받은 AccessibilityEvent
 * (TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED 등)를
 * [ScreenSnapshot] 으로 요약해 흘려보낸다.
 */
interface ScreenObserver {

    /** 관찰이 가능한 상태인지(=접근성 서비스가 연결돼 있는지). */
    fun isReady(): Boolean

    /**
     * 화면이 바뀔 때마다 흐르는 스트림.
     *
     * TYPE_WINDOW_CONTENT_CHANGED 는 초당 수십 번 쏟아질 수 있다.
     * 그대로 흘려보내고, 잦은 이벤트를 다루는 일(debounce)은 받는 쪽에서 한다.
     */
    val changes: Flow<ScreenChange>

    /** 지금 화면을 즉시 한 장 찍는다. 관찰이 불가능하면 null. */
    fun capture(): ScreenSnapshot?
}

data class ScreenChange(
    val kind: Kind,
    val snapshot: ScreenSnapshot
) {
    enum class Kind {
        /** 창이 통째로 바뀌었다(액티비티 전환, 다이얼로그 등장). */
        WINDOW_CHANGED,

        /** 같은 창 안에서 내용만 바뀌었다. */
        CONTENT_CHANGED
    }
}
