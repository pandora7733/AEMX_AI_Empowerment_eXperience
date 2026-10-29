package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchParser
import com.sihoo.aemx.pipeline.youtube.YouTubeShortcut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortcutTest {

    private fun plan(app: String, vararg steps: Pair<ActionType, Pair<String?, String?>>) = ActionPlan(
        app = app,
        steps = steps.mapIndexed { i, (action, tv) -> ActionStep(i, action, tv.first, tv.second) },
    )

    private val searchThenPlay = plan(
        "유튜브",
        ActionType.SEARCH to ("고양이 영상" to null),
        ActionType.PLAY_FIRST to ("다섯 번째 영상" to "5"),
    )

    private val utterance = "유튜브에서 고양이 영상 다섯 번째 영상 틀어줘"

    // ------------------------------------------------------------ 바꿀 계획인가

    @Test
    fun `유튜브 검색 뒤 N번째 재생은 바꿀 수 있다`() {
        assertEquals(YouTubeShortcut.Target("고양이 영상", 5), YouTubeShortcut.target(searchThenPlay, utterance))
    }

    @Test
    fun `순번이 없으면 첫 번째로 본다`() {
        val first = plan("유튜브", ActionType.SEARCH to ("고양이" to null), ActionType.PLAY_FIRST to ("첫 영상" to null))

        assertEquals(1, YouTubeShortcut.target(first, "유튜브에서 고양이 틀어줘")?.ordinal)
    }

    @Test
    fun `앞의 앱 열기가 남아 있어도 바꿀 수 있다`() {
        val withOpen = plan(
            "유튜브",
            ActionType.OPEN_APP to ("유튜브" to null),
            ActionType.SEARCH to ("고양이 영상" to null),
            ActionType.PLAY_FIRST to ("다섯 번째 영상" to "5"),
        )

        assertEquals(5, YouTubeShortcut.target(withOpen, utterance)?.ordinal)
    }

    @Test
    fun `유튜브 뮤직은 바꾸지 않는다`() {
        val music = plan("유튜브 뮤직", ActionType.SEARCH to ("비투비" to null), ActionType.PLAY_FIRST to ("첫 노래" to null))

        assertNull(YouTubeShortcut.target(music, "유튜브 뮤직에서 비투비 틀어줘"))
    }

    @Test
    fun `개인정보 명령은 바꾸지 않는다`() {
        assertNull(YouTubeShortcut.target(searchThenPlay, "카톡으로 고양이 영상 다섯 번째 영상 틀어줘"))
    }

    @Test
    fun `다른 동작이 섞인 계획은 바꾸지 않는다`() {
        val scrolled = plan(
            "유튜브",
            ActionType.SEARCH to ("고양이 영상" to null),
            ActionType.SCROLL to ("down" to null),
            ActionType.PLAY_FIRST to ("다섯 번째 영상" to "5"),
        )

        assertNull(YouTubeShortcut.target(scrolled, utterance))
        assertNull(YouTubeShortcut.target(plan("유튜브", ActionType.SEARCH to ("고양이" to null)), utterance))
    }

    @Test
    fun `검색어가 비었으면 바꾸지 않는다`() {
        val empty = plan("유튜브", ActionType.SEARCH to ("  " to null), ActionType.PLAY_FIRST to ("첫 영상" to null))

        assertNull(YouTubeShortcut.target(empty, utterance))
    }

    // ------------------------------------------------------------ 바꾸기

    @Test
    fun `N번째 영상 id 로 영상 열기 하나가 된다`() = runBlocking {
        var askedQuery: String? = null
        var askedCount = -1
        val rewritten = YouTubeShortcut.rewrite(searchThenPlay, utterance) { query, max ->
            askedQuery = query
            askedCount = max
            listOf("aaaaaa1", "bbbbbb2", "ccccc3", "ddddd4", "eeeee5", "fffff6")
        }

        assertEquals("고양이 영상", askedQuery)
        assertEquals(5, askedCount)
        assertEquals(listOf(ActionType.OPEN_VIDEO), rewritten.steps.map { it.action })
        assertEquals("eeeee5", rewritten.steps[0].target)
        assertEquals("유튜브", rewritten.app)
    }

    @Test
    fun `결과가 N개보다 적으면 원래 계획을 그대로 쓴다`() = runBlocking {
        val rewritten = YouTubeShortcut.rewrite(searchThenPlay, utterance) { _, _ -> listOf("aaaaaa1", "bbbbbb2") }

        assertSame(searchThenPlay, rewritten)
    }

    @Test
    fun `API 가 실패하면 원래 계획을 그대로 쓴다`() = runBlocking {
        val rewritten = YouTubeShortcut.rewrite(searchThenPlay, utterance) { _, _ -> null }

        assertSame(searchThenPlay, rewritten)
    }

    @Test
    fun `API 를 부르지 않는 계획은 원래 계획을 그대로 쓴다`() = runBlocking {
        var called = false
        val music = plan("유튜브 뮤직", ActionType.SEARCH to ("비투비" to null), ActionType.PLAY_FIRST to ("첫 노래" to null))

        val rewritten = YouTubeShortcut.rewrite(music, "유튜브 뮤직에서 비투비 틀어줘") { _, _ ->
            called = true
            listOf("aaaaaa1")
        }

        assertSame(music, rewritten)
        assertTrue("뮤직은 API 를 부르지 않는다", !called)
    }

    // ------------------------------------------------------------ 응답 읽기

    @Test
    fun `응답에서 영상 id 를 검색 순서대로 읽는다`() {
        val body = """{"items":[{"id":{"kind":"youtube#video","videoId":"abc123def"}},{"id":{"kind":"youtube#video","videoId":"xyz789uvw"}}]}"""

        assertEquals(listOf("abc123def", "xyz789uvw"), YouTubeSearchParser.videoIds(body))
    }

    @Test
    fun `항목이 없거나 형식이 깨졌으면 빈 목록이다`() {
        assertEquals(emptyList<String>(), YouTubeSearchParser.videoIds("""{"items":[]}"""))
        assertEquals(emptyList<String>(), YouTubeSearchParser.videoIds("이상한 글"))
        assertEquals(emptyList<String>(), YouTubeSearchParser.videoIds("""{"items":[{"id":{"kind":"youtube#channel"}}]}"""))
    }

    @Test
    fun `한도 초과 이유를 알아본다`() {
        val quota = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        val other = """{"error":{"code":403,"errors":[{"reason":"keyInvalid"}]}}"""

        assertEquals("quotaExceeded", YouTubeSearchParser.errorReason(quota))
        assertTrue(YouTubeSearchParser.isQuotaReason(YouTubeSearchParser.errorReason(quota)))
        assertTrue(!YouTubeSearchParser.isQuotaReason(YouTubeSearchParser.errorReason(other)))
        assertNull(YouTubeSearchParser.errorReason("아님"))
    }
}
