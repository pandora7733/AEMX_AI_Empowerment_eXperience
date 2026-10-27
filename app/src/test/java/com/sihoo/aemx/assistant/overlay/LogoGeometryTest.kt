package com.sihoo.aemx.assistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoGeometryTest {

    /** 그린 뒤 글자 외곽의 중심이 어디인지(원점 + bounds 의 중심). */
    private fun center(origin: Pair<Float, Float>, l: Int, t: Int, r: Int, b: Int) =
        (origin.first + (l + r) / 2f) to (origin.second + (t + b) / 2f)

    @Test
    fun `글자 외곽의 중심이 뷰의 중심에 온다`() {
        // 기준선 위에 있는 소문자(top 이 음수)와 약간 오른쪽으로 치우친 bounds.
        val l = 2; val t = -22; val r = 24; val b = 1
        val origin = LogoGeometry.drawOrigin(l, t, r, b, width = 80f, height = 80f)

        val (cx, cy) = center(origin, l, t, r, b)
        assertEquals(40f, cx, 0.001f)
        assertEquals(40f, cy, 0.001f)
    }

    @Test
    fun `기준선 기준 bounds 라 y 는 글자 높이의 절반만큼 아래로 내려온다`() {
        // top=-20, bottom=0 (기준선 위에 20px). 중심은 -10 이므로 원점은 중심보다 10 아래에 있어야 한다.
        val origin = LogoGeometry.drawOrigin(0, -20, 20, 0, width = 100f, height = 100f)
        assertEquals(50f - 10f, origin.first, 0.001f)
        assertEquals(60f, origin.second, 0.001f)
    }

    @Test
    fun `bounds 가 원점 왼쪽으로 삐져나와도 가로 중심을 맞춘다`() {
        val l = -3; val t = -18; val r = 17; val b = 2
        val origin = LogoGeometry.drawOrigin(l, t, r, b, width = 60f, height = 40f)
        val (cx, cy) = center(origin, l, t, r, b)
        assertEquals(30f, cx, 0.001f)
        assertEquals(20f, cy, 0.001f)
    }

    @Test
    fun `뷰 크기가 달라도 중심은 항상 뷰의 중심이다`() {
        for (size in listOf(24f, 40f, 96f, 150f)) {
            val l = 1; val t = (-size * 0.4f).toInt(); val r = (size * 0.35f).toInt(); val b = 2
            val origin = LogoGeometry.drawOrigin(l, t, r, b, size, size)
            val (cx, cy) = center(origin, l, t, r, b)
            assertEquals("size=$size", size / 2f, cx, 0.001f)
            assertEquals("size=$size", size / 2f, cy, 0.001f)
        }
    }
}
