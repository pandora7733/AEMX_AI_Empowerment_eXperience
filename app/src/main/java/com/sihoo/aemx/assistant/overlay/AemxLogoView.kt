package com.sihoo.aemx.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.sihoo.aemx.R

/**
 * 글자 위치 계산. 글자 TextView 는 글꼴 줄간격 여백과 x-height 때문에 소문자 "a" 가 원 중앙에서 아래로
 * 처져 보인다. 그래서 글자의 실제 외곽(getTextBounds 로 잰 bounds)을 원의 중심에 맞춰 그린다.
 * 안드로이드 없이 테스트하려고 숫자만 받는 순수 함수로 뒀다.
 */
object LogoGeometry {

    /** 원 안에서 글자 크기. 원 지름 대비. */
    const val TEXT_SIZE_RATIO = 0.58f

    /** 글자를 기울이는 정도(skewX). 음수면 오른쪽으로 기운다. */
    const val SKEW = -0.2f

    /**
     * 글자 외곽(bounds, 그리는 원점 기준)의 중심이 [width]×[height] 의 중심에 오도록 하는 그리기 원점.
     * drawText(x, y) 의 y 는 기준선이라 bounds 가 기준선 위(top 이 음수)에 있다는 점까지 계산에 든다.
     */
    fun drawOrigin(left: Int, top: Int, right: Int, bottom: Int, width: Float, height: Float): Pair<Float, Float> {
        val x = width / 2f - (left + right) / 2f
        val y = height / 2f - (top + bottom) / 2f
        return x to y
    }
}

/**
 * 캡슐 왼쪽의 "a" 로고. 파랑→보라 그라데이션 원 위에 가운데 맞춘 기울인 "a" 를 그린다.
 * 장식이라 접근성에서는 제외한다.
 */
internal class AemxLogoView(context: Context) : View(context) {

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = context.getColor(R.color.aemx_logo_text)
    }
    private val bounds = Rect()
    private var originX = 0f
    private var originY = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        circle.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            context.getColor(R.color.aemx_logo), context.getColor(R.color.aemx_logo_end),
            Shader.TileMode.CLAMP
        )
        glyph.textSize = minOf(w, h) * LogoGeometry.TEXT_SIZE_RATIO
        glyph.getTextBounds(GLYPH, 0, GLYPH.length, bounds)
        val (x, y) = LogoGeometry.drawOrigin(bounds.left, bounds.top, bounds.right, bounds.bottom, w.toFloat(), h.toFloat())
        originX = x
        originY = y
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, minOf(cx, cy), circle)
        // 글자 외곽의 중심(= 원 중심)을 축으로 기울여서, 기울여도 가로세로 중심이 그대로다.
        canvas.save()
        canvas.translate(cx, cy)
        canvas.skew(LogoGeometry.SKEW, 0f)
        canvas.translate(-cx, -cy)
        canvas.drawText(GLYPH, originX, originY, glyph)
        canvas.restore()
    }

    private companion object {
        const val GLYPH = "a"
    }
}
