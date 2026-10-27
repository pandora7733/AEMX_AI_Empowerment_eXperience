package com.sihoo.aemx.assistant.overlay

import android.os.Handler

/**
 * 좌표 제스처(탭, 쓸기)를 보내는 동안 캡슐이 터치를 먹지 않게 하는 문.
 *
 * 접근성 서비스의 dispatchGesture 는 화면 좌표로 터치를 넣는다. 그 자리에 캡슐이 있으면 캡슐이 받아서
 * 아래 앱까지 닿지 않는다(유튜브 뮤직 첫 곡, 카톡 공유 행 탭). 그래서 제스처를 보내기 전에
 * [begin] 으로 캡슐을 터치 통과로 바꾸고, 제스처가 끝나면(완료나 취소 콜백) [end] 로 되돌린다.
 *
 * 콜백이 안 오는 경우를 대비해 [begin] 마다 [safetyMs] 짜리 안전 타이머를 건다. 그 안에 [end] 가 안
 * 와도 스스로 되돌려서, 캡슐이 영영 터치를 못 받는 일이 없게 한다.
 *
 * 제스처가 겹칠 수 있어(탭 직후 쓸기) 횟수로 센다. 모두 끝나야 되돌린다. 메인 스레드에서만 부른다.
 *
 * @param apply true 면 터치 통과(FLAG_NOT_TOUCHABLE), false 면 원래대로.
 */
class PassThroughGate(
    private val handler: Handler,
    private val apply: (Boolean) -> Unit,
    private val safetyMs: Long = SAFETY_MS
) {
    private var open = 0

    /** 지금 터치 통과인지. */
    var passingThrough = false
        private set

    private val safety = Runnable {
        open = 0
        release()
    }

    fun begin() {
        open++
        if (!passingThrough) {
            passingThrough = true
            apply(true)
        }
        handler.removeCallbacks(safety)
        handler.postDelayed(safety, safetyMs)
    }

    fun end() {
        if (open > 0) open--
        if (open == 0) {
            handler.removeCallbacks(safety)
            release()
        }
    }

    /** 서비스를 닫을 때. 대기 중인 타이머를 없애고 통과를 푼다. */
    fun reset() {
        open = 0
        handler.removeCallbacks(safety)
        release()
    }

    private fun release() {
        if (passingThrough) {
            passingThrough = false
            apply(false)
        }
    }

    companion object {
        /** 탭(60ms)과 쓸기(350ms)가 끝나고도 남는 시간. */
        const val SAFETY_MS = 1_000L
    }
}
