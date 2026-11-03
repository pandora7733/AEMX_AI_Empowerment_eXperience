package com.sihoo.aemx.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.sihoo.aemx.R

/**
 * 듣는 중 파형. 막대 9개가 브랜드 파랑→보라 그라데이션으로 늘었다 줄었다 한다.
 *
 * [setListening] 이 true 인 동안만 다시 그린다(멈추면 애니메이션 루프도 멈춘다). 인식 단계에서는
 * [setResting] 으로 막대를 줄이고 흐리게 한다. 감소 모션이 켜져 있으면 가운데 높이로 정지해 그린다.
 * 장식이라 접근성에서는 제외한다.
 */
internal class VoiceWaveView(context: Context) : View(context) {

    private val barWidth = resources.getDimension(R.dimen.aemx_wave_bar_width)
    private val barGap = resources.getDimension(R.dimen.aemx_wave_bar_gap)
    private val maxHeight = resources.getDimension(R.dimen.aemx_wave_height)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shaders = arrayOfNulls<Shader>(VoiceWaveGeometry.BAR_COUNT)

    private var listening = false
    private var resting = false
    private var restFactor = 1f
    private var restAnimator: ValueAnimator? = null
    private var startUptime = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (barWidth * VoiceWaveGeometry.BAR_COUNT + barGap * (VoiceWaveGeometry.BAR_COUNT - 1)).toInt()
        setMeasuredDimension(width, maxHeight.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 막대마다 자기 상자(세로 34dp) 안에서 파랑→보라 그라데이션. 그리기 전에 원점을 옮겨 쓴다.
        val blue = context.getColor(R.color.aemx_logo)
        val purple = context.getColor(R.color.aemx_logo_end)
        for (i in shaders.indices) {
            shaders[i] = LinearGradient(0f, 0f, barWidth, maxHeight, blue, purple, Shader.TileMode.CLAMP)
        }
    }

    /** 듣는 중이면 true. 아니면 파형을 멈추고 그리지 않는다(보이기만 하고 움직이지 않는다). */
    fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        if (value) startUptime = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 인식 단계: 막대를 [VoiceWaveGeometry.REST_SCALE] 로 줄이고 흐리게 한다. 전환은 180ms. */
    fun setResting(value: Boolean) {
        if (resting == value) return
        resting = value
        val target = if (value) 0f else 1f
        restAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            restFactor = target
            invalidate()
            return
        }
        restAnimator = ValueAnimator.ofFloat(restFactor, target).apply {
            duration = CapsuleMotion.RECOGNIZE_MS
            addUpdateListener {
                restFactor = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val animated = listening && ValueAnimator.areAnimatorsEnabled()
        val seconds = (SystemClock.uptimeMillis() - startUptime) / 1000.0
        val factor = VoiceWaveGeometry.REST_SCALE + (1f - VoiceWaveGeometry.REST_SCALE) * restFactor
        val alpha = VoiceWaveGeometry.REST_ALPHA + (1f - VoiceWaveGeometry.REST_ALPHA) * restFactor
        val centerY = height / 2f

        for (i in 0 until VoiceWaveGeometry.BAR_COUNT) {
            val scale = if (animated) VoiceWaveGeometry.scale(i, seconds) else VoiceWaveGeometry.still(i)
            val barHeight = maxHeight * scale * factor
            val left = i * (barWidth + barGap)
            // 그라데이션은 막대의 상자(세로 34dp) 기준이라 막대 왼쪽 위로 원점을 옮긴 뒤 그린다.
            canvas.save()
            canvas.translate(left, centerY - maxHeight / 2f)
            paint.shader = shaders[i]
            paint.alpha = (alpha * 255).toInt()
            rect.set(0f, (maxHeight - barHeight) / 2f, barWidth, (maxHeight + barHeight) / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
            canvas.restore()
        }

        if (animated) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        restAnimator?.cancel()
        restAnimator = null
        super.onDetachedFromWindow()
    }
}

/**
 * 듣는 중 캡슐 바깥의 광원. 캡슐 테두리 하나에 가우시안 블러를 건 것이라 층이 지지 않는다.
 *
 * 캡슐 뒤(자식 순서 맨 앞)에 깔린다. 그리는 사각형은 캡슐 위아래로 [CapsuleView] 가 준 여백 안에 들어간다.
 * 좌우는 창 폭이 캡슐과 같아서 잘린다(창 폭을 늘리는 것은 컨트롤러 일이라 여기서 하지 않는다).
 */
internal class CapsuleGlowView(context: Context) : View(context) {

    private val pad = resources.getDimension(R.dimen.aemx_glow_pad)
    private val strokeWidth = resources.getDimension(R.dimen.aemx_glow_stroke)
    private val radius = resources.getDimension(R.dimen.aemx_capsule_radius)
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@CapsuleGlowView.strokeWidth
        maskFilter = BlurMaskFilter(
            resources.getDimension(R.dimen.aemx_glow_blur),
            BlurMaskFilter.Blur.NORMAL
        )
    }
    private var active = false
    private var dim = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    /** 광원을 켠다. [dimmed] 면 인식 단계처럼 약하게(0.45) 켠다. */
    fun setGlow(on: Boolean, dimmed: Boolean) {
        if (active == on && dim == dimmed) return
        active = on
        dim = dimmed
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                context.getColor(R.color.aemx_logo),
                context.getColor(R.color.aemx_logo_end),
                context.getColor(R.color.aemx_brand_cyan)
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        // 기본 투명도 0.85 에 호흡(0.6~1.0)을 곱한다. 인식 단계는 여기에 0.45 를 더 곱한다.
        val breathe = if (ValueAnimator.areAnimatorsEnabled()) {
            VoiceWaveGeometry.breathe(SystemClock.uptimeMillis())
        } else {
            0.5f
        }
        val base = if (dim) 0.45f else 1f
        paint.alpha = ((0.6f + 0.4f * breathe) * 0.85f * base * 255).toInt()

        // 스트로크 가운데가 캡슐 가장자리에 온다.
        rect.set(0f, pad, width.toFloat(), height - pad)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }
}
