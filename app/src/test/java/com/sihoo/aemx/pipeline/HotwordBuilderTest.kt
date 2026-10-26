package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.stt.HotwordBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordBuilderTest {

    private val all: (Char) -> Boolean = { true }

    @Test
    fun `고정 핫워드는 그대로 앞에 두고 앱 라벨과 연락처를 낮은 점수로 붙인다`() {
        val text = HotwordBuilder.build(listOf("에이맥스 :3.0"), listOf("멜론"), listOf("배재준"), all)

        assertEquals(listOf("에이맥스 :3.0", "멜론 :1.5", "배재준 :1.5"), text.lines())
    }

    @Test
    fun `한글이 아니거나 모델에 없는 글자나 길이가 안 맞는 이름은 거른다`() {
        assertNull(HotwordBuilder.clean("Chrome", all))
        assertNull(HotwordBuilder.clean("앱2", all))
        assertNull(HotwordBuilder.clean("김", all))
        assertNull(HotwordBuilder.clean("가나다라마바사아자차카", all))
        assertNull(HotwordBuilder.clean("배재준\n해킹 :9", all))
        assertNull(HotwordBuilder.clean("배재준") { it != '준' })
        assertEquals("홍 길동", HotwordBuilder.clean("  홍   길동 ", all))
    }

    @Test
    fun `이미 있는 단어는 중복으로 넣지 않는다`() {
        val text = HotwordBuilder.build(listOf("유튜브 뮤직 :1.5"), listOf("유튜브뮤직", "멜론", "멜론"), emptyList(), all)

        assertEquals(listOf("유튜브 뮤직 :1.5", "멜론 :1.5"), text.lines())
    }

    @Test
    fun `연락처 개수에 상한을 둔다`() {
        // 서로 다른 두 글자 이름 300개.
        val names = (0 until 300).map { "${'가' + it / 50}${'가' + it % 50}" }
        val text = HotwordBuilder.build(emptyList(), emptyList(), names, all)

        assertEquals(HotwordBuilder.MAX_CONTACTS, text.lines().size)
        assertTrue(text.lines().all { it.endsWith(":1.5") })
    }
}
