package com.sihoo.aemx.assistant.overlay

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * 캡슐과 펼친 패널의 움직임. motion.html 의 명세를 따른다(기본 easing cubic-bezier(0.2, 0, 0, 1)).
 *
 * 값 계산은 안드로이드 없이 테스트할 수 있게 함수로 두고, 실제 애니메이션은 View 에 붙인다. 움직임은 transform 과
 * alpha 위주다. 높이를 바꾸는 움직임은 창 크기 보정(위치 맞춤)과 충돌하므로 하지 않는다. 시스템의 "애니메이션 끄기"
 * (ValueAnimator.areAnimatorsEnabled 가 거짓)면 이동과 펄스 없이 바로 바뀐다.
 */
object OverlayMotion {

    /** 기본 easing. motion.html 의 --ease 와 같다. */
    val EASE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 펼친 패널의 단계 행이 차례로 나타나는 첫 지연과 간격. motion.html 의 .row.enter 와 같다. */
    const val ROW_BASE_DELAY_MS = 60L
    const val ROW_STEP_MS = 50L
    const val ROW_IN_MS = 200L

    /** 캡슐이 나타날 때와 사라질 때, 펼친 패널이 열릴 때와 닫힐 때의 시간. */
    const val CAPSULE_IN_MS = 260L
    const val CAPSULE_OUT_MS = 160L
    const val PANEL_IN_MS = 160L
    const val PANEL_OUT_MS = 120L

    /** 글자와 아이콘이 바뀌는 시간(다시 계획 등). */
    const val SWAP_MS = 160L

    /** 진행 중 표시의 은은한 펄스 주기(motion.html 의 nowp 1.6s). */
    const val EXECUTING_PULSE_MS = 1600L

    /** 인식 단계 문장이 한 글자씩 나타나는 간격. */
    const val TYPE_CHAR_MS = 40L

    /** 성공 때 체크가 커지며 시작하는 크기(motion.html 의 .cap.ok .ico 0.96 에서 1). */
    const val SUCCESS_FROM_SCALE = 0.96f

    /** 펄스의 가장 낮은 투명도(motion.html 의 pulse 0.55). */
    const val PULSE_MIN_ALPHA = 0.55f

    /** 한 단계 행의 나타나는 지연(ms). 0번째 행부터 차례로 간다. */
    fun rowDelayMs(index: Int): Long = ROW_BASE_DELAY_MS + ROW_STEP_MS * index

    /** 펄스 투명도 곡선. 0 에서 1, 주기의 절반에서 [PULSE_MIN_ALPHA] 로 내려갔다가 돌아온다. */
    fun pulseAlpha(elapsedMs: Long, periodMs: Long): Float {
        val phase = (elapsedMs % periodMs).toDouble() / periodMs
        val mid = (1f + PULSE_MIN_ALPHA) / 2.0
        val amp = (1f - PULSE_MIN_ALPHA) / 2.0
        return (mid + amp * cos(2.0 * PI * phase)).toFloat()
    }

    /** 글자 [total] 개 중 [elapsedMs] 시점에 보일 글자 수. 처음 한 글자는 바로 보인다. */
    fun typedCount(total: Int, elapsedMs: Long): Int {
        if (total <= 0) return 0
        return min(total, (elapsedMs / TYPE_CHAR_MS).toInt() + 1)
    }

    /** 성공 때 크기. [progress] 0 이면 [SUCCESS_FROM_SCALE], 1 이면 1. */
    fun successScale(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return SUCCESS_FROM_SCALE + (1f - SUCCESS_FROM_SCALE) * p
    }

    /** 시스템 애니메이션이 꺼져 있으면 참이다. 그때는 이동과 펄스를 하지 않는다. */
    fun isReduced(): Boolean = !ValueAnimator.areAnimatorsEnabled()

    // ---- View 에 붙이는 움직임. 같은 View 의 펄스와 글자 나타내기는 새로 시작할 때 이전 것을 끊는다.

    private val pulses = WeakHashMap<View, Animator>()
    private val typers = WeakHashMap<TextView, Animator>()

    /** 캡슐이 나타난다. 살짝 작은 크기에서 커지고 밝아진다. [distancePx] 만큼 아래에서 올라온다. */
    fun enter(view: View, distancePx: Float, reduced: Boolean = isReduced()) {
        view.animate().cancel()
        if (reduced) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.translationY = distancePx
        view.scaleX = SUCCESS_FROM_SCALE
        view.scaleY = SUCCESS_FROM_SCALE
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(CAPSULE_IN_MS).setInterpolator(EASE).start()
    }

    /** 캡슐이 사라진다. [end] 는 끝난 뒤(또는 바로) 한 번 부른다. 끊기면 부르지 않는다. */
    fun exit(view: View, reduced: Boolean = isReduced(), end: () -> Unit) {
        view.animate().cancel()
        if (reduced) {
            end()
            return
        }
        view.animate().alpha(0f).scaleX(SUCCESS_FROM_SCALE).scaleY(SUCCESS_FROM_SCALE)
            .setDuration(CAPSULE_OUT_MS).setInterpolator(EASE).withEndAction(end).start()
    }

    /** 사라지는 움직임을 끊고 보이는 상태로 되돌린다. */
    fun cancelExit(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }

    /** 단계 행 하나가 [index] 번째 순서로 나타난다. */
    fun rowIn(view: View, index: Int, distancePx: Float, reduced: Boolean = isReduced()) {
        view.animate().cancel()
        if (reduced) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = distancePx
        view.animate().alpha(1f).translationY(0f).setStartDelay(rowDelayMs(index))
            .setDuration(ROW_IN_MS).setInterpolator(EASE).start()
    }

    /** 펼친 패널이 열린다(투명도와 위치). */
    fun panelIn(view: View, distancePx: Float, reduced: Boolean = isReduced()) {
        view.animate().cancel()
        if (reduced) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = distancePx
        view.animate().alpha(1f).translationY(0f).setDuration(PANEL_IN_MS).setInterpolator(EASE).start()
    }

    /** 펼친 패널이 닫힌다. [end] 는 끝난 뒤 한 번 부른다. */
    fun panelOut(view: View, distancePx: Float, reduced: Boolean = isReduced(), end: () -> Unit) {
        view.animate().cancel()
        if (reduced) {
            end()
            return
        }
        view.animate().alpha(0f).translationY(distancePx).setDuration(PANEL_OUT_MS)
            .setInterpolator(EASE).withEndAction(end).start()
    }

    /** 은은하게 반복하는 펄스. [stop] 으로 즉시 멈춘다. */
    fun pulse(view: View, periodMs: Long, reduced: Boolean = isReduced()) {
        stop(view)
        if (reduced) return
        val animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, PULSE_MIN_ALPHA, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulses[view] = animator
        animator.start()
    }

    /** 펄스를 0ms 로 멈추고 투명도를 1 로 되돌린다(중단을 눌렀을 때). */
    fun stop(view: View) {
        pulses.remove(view)?.cancel()
        view.animate().cancel()
        view.alpha = 1f
    }

    /** 성공하면 캡슐이 0.96 에서 1 로 커진다(체크가 나타나는 순간). */
    fun successPop(view: View, reduced: Boolean = isReduced()) {
        view.animate().cancel()
        if (reduced) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = SUCCESS_FROM_SCALE
        view.scaleY = SUCCESS_FROM_SCALE
        view.animate().scaleX(1f).scaleY(1f).setDuration(CapsuleMotion.SUCCESS_MS).setInterpolator(EASE).start()
    }

    /** 글자가 바뀔 때 잠깐 흐렸다가 선명해진다(다시 계획 등). */
    fun swap(view: View, reduced: Boolean = isReduced()) {
        view.animate().cancel()
        if (reduced) {
            view.alpha = 1f
            return
        }
        view.alpha = 0.4f
        view.animate().alpha(1f).setDuration(SWAP_MS).setInterpolator(EASE).start()
    }

    /** 인식 문장을 한 글자씩 나타낸다. 다 나타나면 [full] 전체가 남는다. */
    fun typewrite(view: TextView, full: String, reduced: Boolean = isReduced()) {
        typers.remove(view)?.cancel()
        if (reduced || full.isEmpty()) {
            view.text = full
            return
        }
        val total = full.length
        val animator = ValueAnimator.ofInt(0, total * TYPE_CHAR_MS.toInt()).apply {
            duration = total * TYPE_CHAR_MS
            interpolator = null
            addUpdateListener { view.text = full.take(typedCount(total, (it.animatedValue as Int).toLong())) }
        }
        typers[view] = animator
        animator.start()
    }

    /** 진행 중인 글자 나타내기를 멈추고 [full] 을 바로 보인다. */
    fun finishTypewrite(view: TextView, full: String) {
        typers.remove(view)?.cancel()
        view.text = full
    }
}
