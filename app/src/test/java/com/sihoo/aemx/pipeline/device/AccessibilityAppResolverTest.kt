package com.sihoo.aemx.pipeline.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAppResolverTest {

    private val installed = mapOf("마켓컬리" to "com.kurly.android", "카톡" to "com.kakao.talk")
    private val resolver = AccessibilityAppResolver { installed[it.trim()] }

    @Test
    fun `풀린 앱은 패키지와 불린 이름을 함께 기억한다`() {
        assertEquals("com.kurly.android", resolver.resolvePackage(" 마켓컬리 "))
        assertEquals("마켓컬리", resolver.nameFor("com.kurly.android"))
    }

    @Test
    fun `못 푼 앱은 null 이고 이름도 기억하지 않는다`() {
        assertNull(resolver.resolvePackage("없는앱"))
        assertNull(resolver.nameFor("com.unknown"))
        assertNull(resolver.nameFor(null))
    }

    @Test
    fun `서비스가 풀 수 있는지는 리졸버 기준이다`() {
        assertTrue(resolver.isResolvable("카톡"))
        assertFalse(resolver.isResolvable("없는앱"))
    }
}
