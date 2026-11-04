package com.sihoo.aemx.assistant.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.sihoo.aemx.R

/**
 * 글자 위치 계산. 실제 로고 이미지로 바뀌어 화면에서는 쓰지 않는다. 안드로이드 없이 테스트하는 순수 함수로만 남겨 둔다.
 * 글자의 외곽(getTextBounds 로 잰 bounds)을 원의 중심에 맞추는 계산이다.
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
 * 캡슐 왼쪽의 AEMX 로고. 실제 로고 이미지(ic_aemx_logo)를 칸에 맞춰 그린다. 장식이라 접근성에서는 제외한다.
 * 로고 뒤 배경은 이미지가 가진 연한 분홍 바탕이라 어두운 캡슐 위에서도 가장자리가 보인다.
 */
internal class AemxLogoView(context: Context) : View(context) {

    private val logo: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_aemx_logo)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect(0, 0, logo.width, logo.height)
    private val target = Rect()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        target.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(logo, source, target, paint)
    }
}
