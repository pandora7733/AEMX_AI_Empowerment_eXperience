package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.stt.SpeakerMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerMathTest {

    @Test
    fun `같은 방향이면 1 반대면 -1 직각이면 0 이다`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, SpeakerMath.cosine(a, floatArrayOf(2f, 4f, 6f)), 1e-6f)
        assertEquals(-1f, SpeakerMath.cosine(a, floatArrayOf(-1f, -2f, -3f)), 1e-6f)
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
    }

    @Test
    fun `영벡터는 0으로 본다`() {
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test
    fun `평균은 길이가 1이고 크기가 큰 쪽으로 쏠리지 않는다`() {
        val average = SpeakerMath.averageNormalized(
            listOf(floatArrayOf(10f, 0f), floatArrayOf(0f, 1f)),
        )
        assertEquals(1.0, Math.sqrt(average.sumOf { (it * it).toDouble() }), 1e-6)
        assertEquals(average[0], average[1], 1e-6f)
    }

    @Test
    fun `저장했다가 읽으면 같은 값이다`() {
        val original = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(original, SpeakerMath.decode(SpeakerMath.encode(original)), 0f)
    }

    @Test
    fun `깨진 저장값은 null 이다`() {
        assertNull(SpeakerMath.decode("abc,1"))
        assertNull(SpeakerMath.decode(""))
        assertNull(SpeakerMath.decode(null))
    }
}
