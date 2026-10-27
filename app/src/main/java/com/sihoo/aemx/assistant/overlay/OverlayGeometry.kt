package com.sihoo.aemx.assistant.overlay

/** 가려지면 안 되는 시스템 영역의 두께(px). [ime] 은 키보드가 떠 있을 때만 0 보다 크다. */
data class OverlayInsets(val top: Int = 0, val bottom: Int = 0, val ime: Int = 0)

/**
 * 캡슐의 창 위치 계산. 안드로이드 없이 테스트하려고 순수 함수로 뒀다.
 *
 * 창은 gravity(BOTTOM 또는 TOP) 기준으로 놓고 y 는 그 가장자리에서 얼마나 떨어뜨릴지다.
 * 오버레이 창이 시스템 바를 이미 피해서 배치되는지는 기기마다 달라서 한 번에 확정하지 않는다.
 * 먼저 [initialOffset] 으로 놓고, 그려진 뒤 실제 위치를 재서 [correctedOffset] 으로 한 번 맞춘다.
 */
object OverlayGeometry {

    /** 시스템 영역에서 캡슐을 띄우는 간격(dp). */
    const val MARGIN_DP = 8

    /** 가장자리에서 처음 떨어뜨릴 거리. 아래는 내비게이션 바(키보드가 있으면 키보드) 위, 위는 상태 표시줄 아래다. */
    fun initialOffset(dock: Dock, insets: OverlayInsets, marginPx: Int): Int = when (dock) {
        Dock.BOTTOM -> maxOf(insets.bottom, insets.ime) + marginPx
        Dock.TOP -> insets.top + marginPx
    }

    /** 캡슐 가장자리가 화면에서 있어야 할 좌표. 아래는 아래변의 y, 위는 윗변의 y 다. */
    fun desiredEdge(dock: Dock, screenHeight: Int, insets: OverlayInsets, marginPx: Int): Int = when (dock) {
        Dock.BOTTOM -> screenHeight - maxOf(insets.bottom, insets.ime) - marginPx
        Dock.TOP -> insets.top + marginPx
    }

    /**
     * 실제로 그려진 가장자리([actualEdge])가 있어야 할 곳([desiredEdge])과 다르면 y 를 그만큼 고친다.
     * 이미 맞으면(오차 [TOLERANCE_PX] 이하) 그대로 둔다 — 위치가 계속 흔들리지 않게 하려는 것이다.
     */
    fun correctedOffset(dock: Dock, currentOffset: Int, actualEdge: Int, desiredEdge: Int): Int {
        val delta = actualEdge - desiredEdge
        if (kotlin.math.abs(delta) <= TOLERANCE_PX) return currentOffset
        // 아래: 아래변이 너무 낮으면(delta > 0) 띄우는 거리를 늘린다. 위: 윗변이 너무 높으면(delta < 0) 늘린다.
        return when (dock) {
            Dock.BOTTOM -> currentOffset + delta
            Dock.TOP -> currentOffset - delta
        }.coerceAtLeast(0)
    }

    const val TOLERANCE_PX = 2
}
