package com.sihoo.aemx

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** 첫 실행 화면에 쓰는 선 아이콘. 글자 없이 그려서 폰트와 상관없이 같은 모양이다. */
enum class Glyph { MIC, BELL, PERSON, LAYERS, PEOPLE, CHIP, PLAY, SUN, BUBBLE, GEAR, BOLT }

/**
 * 선 아이콘 하나. 칸 가득 그린다(안쪽 여백은 호출하는 쪽이 준다). 색은 [color] 로 정한다.
 * 장식이라 접근성에서는 제외한다(옆 글자가 뜻을 말한다).
 */
class GlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var glyph: Glyph = Glyph.MIC,
    var color: Int = 0xFF000000.toInt(),
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        paint.color = color
        paint.strokeWidth = s * STROKE_RATIO
        canvas.save()
        canvas.translate((w - s) / 2f, (h - s) / 2f)
        drawGlyph(canvas, s)
        canvas.restore()
    }

    /** 한 변 s 짜리 칸 기준으로 그린다. 좌표는 0..s 이고 p(x) 가 칸 비율을 픽셀로 바꾼다. */
    private fun drawGlyph(canvas: Canvas, s: Float) {
        fun p(x: Float) = x * s
        when (glyph) {
            Glyph.MIC -> {
                box.set(p(0.36f), p(0.18f), p(0.64f), p(0.62f))
                canvas.drawRoundRect(box, p(0.14f), p(0.14f), paint)
                path.reset()
                path.moveTo(p(0.26f), p(0.46f))
                path.quadTo(p(0.26f), p(0.66f), p(0.5f), p(0.66f))
                path.quadTo(p(0.74f), p(0.66f), p(0.74f), p(0.46f))
                path.moveTo(p(0.5f), p(0.66f))
                path.lineTo(p(0.5f), p(0.82f))
                canvas.drawPath(path, paint)
            }
            Glyph.BELL -> {
                path.reset()
                path.moveTo(p(0.28f), p(0.66f))
                path.lineTo(p(0.28f), p(0.44f))
                path.quadTo(p(0.28f), p(0.2f), p(0.5f), p(0.2f))
                path.quadTo(p(0.72f), p(0.2f), p(0.72f), p(0.44f))
                path.lineTo(p(0.72f), p(0.66f))
                path.lineTo(p(0.8f), p(0.74f))
                path.lineTo(p(0.2f), p(0.74f))
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(p(0.44f), p(0.82f), p(0.56f), p(0.82f), paint)
            }
            Glyph.PERSON -> {
                canvas.drawCircle(p(0.5f), p(0.34f), p(0.14f), paint)
                box.set(p(0.24f), p(0.56f), p(0.76f), p(1.0f))
                canvas.drawArc(box, 180f, 180f, false, paint)
            }
            Glyph.LAYERS -> {
                // 겹친 창 두 장.
                box.set(p(0.2f), p(0.18f), p(0.62f), p(0.6f))
                canvas.drawRoundRect(box, p(0.08f), p(0.08f), paint)
                box.set(p(0.38f), p(0.4f), p(0.8f), p(0.82f))
                canvas.drawRoundRect(box, p(0.08f), p(0.08f), paint)
            }
            Glyph.PEOPLE -> {
                canvas.drawCircle(p(0.36f), p(0.36f), p(0.12f), paint)
                box.set(p(0.14f), p(0.56f), p(0.58f), p(0.98f))
                canvas.drawArc(box, 180f, 180f, false, paint)
                canvas.drawCircle(p(0.7f), p(0.4f), p(0.1f), paint)
                box.set(p(0.6f), p(0.6f), p(0.86f), p(0.96f))
                canvas.drawArc(box, 200f, 140f, false, paint)
            }
            Glyph.CHIP -> {
                box.set(p(0.28f), p(0.28f), p(0.72f), p(0.72f))
                canvas.drawRoundRect(box, p(0.06f), p(0.06f), paint)
                for (i in 0..2) {
                    val c = p(0.36f + i * 0.14f)
                    canvas.drawLine(c, p(0.12f), c, p(0.24f), paint)
                    canvas.drawLine(c, p(0.76f), c, p(0.88f), paint)
                    canvas.drawLine(p(0.12f), c, p(0.24f), c, paint)
                    canvas.drawLine(p(0.76f), c, p(0.88f), c, paint)
                }
            }
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(p(0.36f), p(0.28f))
                path.lineTo(p(0.72f), p(0.5f))
                path.lineTo(p(0.36f), p(0.72f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Glyph.SUN -> {
                canvas.drawCircle(p(0.5f), p(0.5f), p(0.16f), paint)
                drawRays(canvas, s, 0.26f, 0.36f, paint)
            }
            Glyph.BUBBLE -> {
                box.set(p(0.16f), p(0.2f), p(0.84f), p(0.68f))
                canvas.drawRoundRect(box, p(0.14f), p(0.14f), paint)
                path.reset()
                path.moveTo(p(0.34f), p(0.68f))
                path.lineTo(p(0.3f), p(0.84f))
                path.lineTo(p(0.48f), p(0.68f))
                canvas.drawPath(path, paint)
            }
            Glyph.BOLT -> {
                // 번개: 계속 켜 두는 것을 뜻한다(백그라운드 듣기 단계).
                path.reset()
                path.moveTo(p(0.58f), p(0.12f))
                path.lineTo(p(0.3f), p(0.56f))
                path.lineTo(p(0.5f), p(0.56f))
                path.lineTo(p(0.42f), p(0.88f))
                path.lineTo(p(0.72f), p(0.44f))
                path.lineTo(p(0.52f), p(0.44f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Glyph.GEAR -> {
                canvas.drawCircle(p(0.5f), p(0.5f), p(0.14f), paint)
                drawRays(canvas, s, 0.24f, 0.36f, paint)
            }
        }
    }

    /** 가운데에서 여덟 방향으로 나가는 짧은 선들. 해와 톱니 바퀴 이빨에 쓴다. */
    private fun drawRays(canvas: Canvas, s: Float, inner: Float, outer: Float, paint: Paint) {
        for (i in 0..7) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat()
            val d = Math.sin(a).toFloat()
            canvas.drawLine(
                (0.5f + inner * c) * s, (0.5f + inner * d) * s,
                (0.5f + outer * c) * s, (0.5f + outer * d) * s,
                paint,
            )
        }
    }

    private companion object {
        const val STROKE_RATIO = 0.07f
    }
}

/**
 * 가운데의 큰 마이크 버튼. 바깥 원(236)과 중간 원(176)은 연한 분홍이고 둘 다 2.4초 주기로 1에서 1.06배로 숨 쉰다.
 * 버튼(116)은 자주에서 분홍으로 가는 그라데이션이고 안에 흰 마이크를 그린다. 눌러야 하는 영역은 버튼 원이다.
 * 감소 모션이 켜져 있으면 숨 쉬지 않고 가만히 그린다.
 */
class MicOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()
    private val box = RectF()
    private val startUptime = SystemClock.uptimeMillis()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = dp(FRAME_DP).toInt()
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = dp(BUTTON_DP) / 2f
        val cx = w / 2f
        val cy = h / 2f
        buttonPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            context.getColor(R.color.onb_brand_plum),
            context.getColor(R.color.onb_brand_pink),
            Shader.TileMode.CLAMP,
        )
        ringPaint.color = context.getColor(R.color.onb_pink)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val animate = ValueAnimator.areAnimatorsEnabled()
        val t = (SystemClock.uptimeMillis() - startUptime) / 1000.0
        val outerScale = 1f + BREATHE_GAIN * breath(t).toFloat()
        // 중간 원은 위상을 0.3초 늦춰 두 원이 따로 숨 쉬는 것처럼 보이게 한다.
        val midScale = 1f + BREATHE_GAIN * breath(t - 0.3).toFloat()

        ringPaint.alpha = (255 * OUTER_ALPHA).toInt()
        canvas.drawCircle(cx, cy, dp(OUTER_DP) / 2f * (if (animate) outerScale else 1f), ringPaint)
        ringPaint.alpha = (255 * MIDDLE_ALPHA).toInt()
        canvas.drawCircle(cx, cy, dp(MIDDLE_DP) / 2f * (if (animate) midScale else 1f), ringPaint)

        canvas.drawCircle(cx, cy, dp(BUTTON_DP) / 2f, buttonPaint)
        drawMic(canvas, cx, cy)
        // 화면에 보일 때만 다시 그린다. 가려진 동안 매 프레임 그리면 배터리만 쓴다.
        if (animate && isShown) postInvalidateOnAnimation()
    }

    /** 0 에서 1 로 부드럽게 오가는 숨쉬기 곡선. 주기는 2.4초다. */
    private fun breath(seconds: Double): Double = (1 - Math.cos(2 * Math.PI * seconds / BREATHE_S)) / 2

    /** 흰 마이크: 몸통, 받침 곡선, 줄기, 밑줄. 버튼 지름 116 기준 비율이다. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(BUTTON_DP) / 116f
        glyphPaint.strokeWidth = 4f * u
        box.set(cx - 9f * u, cy - 16f * u, cx + 9f * u, cy + 16f * u)
        glyphPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(box, 9f * u, 9f * u, glyphPaint)
        glyphPaint.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(cx - 16f * u, cy + 2f * u)
        path.quadTo(cx - 16f * u, cy + 16f * u, cx, cy + 16f * u)
        path.quadTo(cx + 16f * u, cy + 16f * u, cx + 16f * u, cy + 2f * u)
        canvas.drawPath(path, glyphPaint)
        canvas.drawLine(cx, cy + 16f * u, cx, cy + 26f * u, glyphPaint)
        canvas.drawLine(cx - 10f * u, cy + 26f * u, cx + 10f * u, cy + 26f * u, glyphPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val inside = Math.hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()) <= dp(BUTTON_DP) / 2.0
        if (event.action == MotionEvent.ACTION_UP && inside) {
            performClick()
            return true
        }
        return inside || super.onTouchEvent(event)
    }

    private fun dp(v: Float) = v * density

    companion object {
        /** 칸 한 변. 바깥 원이 숨 쉬며 커지는 만큼(6%) 여유를 둬서 가장자리가 잘리지 않게 한다. */
        const val FRAME_DP = 252f
        const val OUTER_DP = 236f
        const val MIDDLE_DP = 176f
        const val BUTTON_DP = 116f
        const val OUTER_ALPHA = 0.12f
        const val MIDDLE_ALPHA = 0.22f
        const val BREATHE_GAIN = 0.06f
        const val BREATHE_S = 2.4
    }
}
