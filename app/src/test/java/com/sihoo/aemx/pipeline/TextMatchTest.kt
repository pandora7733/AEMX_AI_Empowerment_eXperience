package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.model.TextMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {

    @Test
    fun `공백과 대소문자를 무시하고 부분일치한다`() {
        assertTrue(TextMatch.contains("손흥민하이라이트 - YouTube", "손흥민 하이라이트"))
        assertTrue(TextMatch.contains("Son Heung-min HIGHLIGHTS", "son heung-min highlights"))
        assertFalse(TextMatch.contains("고양이 영상", "손흥민 하이라이트"))
        assertFalse(TextMatch.contains("아무 글", " "))
    }

    @Test
    fun `이번 검색어가 보이면 통과하고 옛 결과 화면은 통과하지 않는다`() {
        val oldResults = listOf("고양이 영상 - YouTube", "고양이 영상 3분 12초")
        val newResults = listOf("손흥민 하이라이트 - YouTube", "손흥민 하이라이트 3분")

        assertFalse(TextMatch.queryVisible(oldResults, "손흥민 하이라이트"))
        assertTrue(TextMatch.queryVisible(newResults, "손흥민 하이라이트"))
    }

    @Test
    fun `검색어는 앞 8글자만 보고 긴 검색어도 잘려 보이면 맞는다`() {
        assertTrue(TextMatch.queryVisible(listOf("손흥민 하이라이트 세 번째"), "손흥민 하이라이트 세 번째 영상 틀어줘"))
        assertFalse(TextMatch.queryVisible(emptyList(), "손흥민 하이라이트"))
        assertFalse(TextMatch.queryVisible(listOf("아무 글"), "   "))
    }

    @Test
    fun `스냅샷의 containsText 도 같은 규칙을 쓴다`() {
        val snap = ScreenSnapshot(packageName = "p", className = null, texts = listOf("손흥민 하이라이트"))

        assertTrue(snap.containsText("손흥민하이라이트"))
        assertFalse(snap.containsText("고양이"))
    }
}
