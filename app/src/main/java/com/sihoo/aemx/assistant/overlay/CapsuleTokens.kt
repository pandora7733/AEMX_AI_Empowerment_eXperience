package com.sihoo.aemx.assistant.overlay

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 캡슐 움직임의 시간(ms). 디자인 tokens.json 의 motionMs 와 같다. 색과 크기는 res 의 aemx_* 에 있다.
 * 0 은 즉시 바꾼다는 뜻이다(cancel, reduced).
 */
object CapsuleMotion {
    const val ENTER_MS = 260L
    const val RECOGNIZE_MS = 180L
    const val UNDERSTAND_PULSE_MS = 1800L
    const val PLAN_MS = 220L
    const val EXECUTE_MS = 260L
    const val SUCCESS_MS = 220L
    const val ERROR_MS = 160L
    const val CANCEL_MS = 0L
    const val REDUCED_MS = 0L

    /** 외곽 광원이 숨 쉬는 주기. motion.html 의 breathe 2.4s. */
    const val GLOW_BREATHE_MS = 2400L
}

/**
 * 듣는 중 파형 막대 9개의 기하. motion.html 의 hs, ds, dl 과 같다.
 *
 * 막대마다 세로 배율이 [range] 의 lo 와 hi 사이를 ease-in-out 으로 오간다(alternate 한 바퀴가 2d 초).
 * 막대는 가운데를 축으로 늘고 줄어든다.
 */
object VoiceWaveGeometry {
    const val BAR_COUNT = 9

    /** 원본 막대 높이(단위: [MAX_HEIGHT] 기준). */
    private val HEIGHTS = intArrayOf(12, 22, 32, 24, 34, 26, 32, 20, 12)
    private val DURATIONS_S = doubleArrayOf(0.9, 1.15, 0.8, 1.3, 0.95, 1.2, 0.85, 1.1, 1.0)

    /** 음수 지연은 그 시간만큼 이미 진행된 상태에서 시작한다는 뜻이다(motion.html 의 dl). */
    private val DELAYS_S = doubleArrayOf(0.0, -0.4, -0.8, -0.2, -0.6, -1.0, -0.3, -0.7, -0.5)

    const val MAX_HEIGHT = 34

    /** 인식 단계에서 파형이 줄어드는 배율(motion.html 의 .cap.rec .bar scaleY(.2)). */
    const val REST_SCALE = 0.2f

    /** 인식 단계에서 파형이 흐려지는 투명도. */
    const val REST_ALPHA = 0.6f

    /** 막대 [index] 의 세로 배율 범위 (lo, hi). */
    fun range(index: Int): Pair<Float, Float> {
        val s = HEIGHTS[index] / MAX_HEIGHT.toFloat()
        val lo = max(0.18f, s * 0.45f)
        val hi = min(1f, s * 1.12f + 0.08f)
        return lo to hi
    }

    /** [seconds] 초 때 막대 [index] 의 세로 배율. 움직이지 않을 때(감소 모션)는 [still]. */
    fun scale(index: Int, seconds: Double): Float {
        val (lo, hi) = range(index)
        val phase = (seconds - DELAYS_S[index]) / DURATIONS_S[index]
        val eased = (1.0 - cos(PI * phase)) / 2.0
        return lo + (hi - lo) * eased.toFloat()
    }

    /** 움직이지 않을 때의 배율. 범위의 가운데. */
    fun still(index: Int): Float {
        val (lo, hi) = range(index)
        return (lo + hi) / 2f
    }

    /** 광원 투명도의 호흡 곡선. 0 에서 0 으로, 가운데 1 이다. 값은 0..1. */
    fun breathe(elapsedMs: Long): Float {
        val phase = (elapsedMs % CapsuleMotion.GLOW_BREATHE_MS).toDouble() / CapsuleMotion.GLOW_BREATHE_MS
        // 한 주기 동안 0 → 1 → 0 이다(cos 한 바퀴).
        return ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
    }
}
