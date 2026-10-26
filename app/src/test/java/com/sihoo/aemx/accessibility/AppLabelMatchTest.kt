package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppLabelMatchTest {

    private fun pick(query: String, vararg labels: String): AppLauncher.Resolution {
        val apps = labels.mapIndexed { index, label -> "pkg.$index" to AppLauncher.normalizeName(label) }
        return AppLauncher.pickByLabel(query, AppLauncher.normalizeName(query), apps)
    }

    @Test
    fun `멜론은 멜론티켓만 설치돼 있으면 열지 않는다`() {
        val result = pick("멜론", "멜론티켓")

        assertNull(result.packageName)
        assertNotNull(result.reason)
    }

    @Test
    fun `정확 일치가 있으면 비슷한 이름이 같이 있어도 그것을 연다`() {
        assertEquals("pkg.0", pick("쿠팡", "쿠팡", "쿠팡이츠").packageName)
    }

    @Test
    fun `정확 일치가 여럿이면 열지 않는다`() {
        assertNull(pick("멜론", "멜론", "멜론").packageName)
    }

    @Test
    fun `줄여 부른 이름은 라벨의 대부분이면 연다`() {
        assertEquals("pkg.0", pick("카카오", "카카오톡").packageName)
    }

    @Test
    fun `앱 이라는 말만 붙은 라벨은 같은 앱으로 본다`() {
        assertEquals("pkg.0", pick("토스", "토스 앱").packageName)
    }

    @Test
    fun `다른 서비스 이름이 붙은 라벨은 열지 않는다`() {
        assertNull(pick("네이버", "네이버 지도").packageName)
        assertNull(pick("유튜브", "유튜브 키즈").packageName)
    }

    @Test
    fun `후보가 둘이면 모호해서 열지 않는다`() {
        assertNull(pick("카카오", "카카오톡", "카카오맵").packageName)
    }

    @Test
    fun `표에 멜론이 있어 라벨 없이도 멜론 앱으로 풀린다`() {
        assertEquals("com.iloen.melon", AppLauncher.packageFor("멜론"))
        assertEquals("com.iloen.melon", AppLauncher.packageFor("Melon"))
    }
}
