package com.sihoo.aemx.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionPlanParserTest {
    @Test
    fun `rejects coercion cross app actions and invalid durations`() {
        val invalid = listOf(
            """{"app":"교보문고","steps":[{"action":"tap","target":12}]}""",
            """{"app":"교보문고","steps":[{"action":"open_app","target":"알라딘"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"1.5s"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"-5"}]}""",
            """{"app":"교보문고","steps":[{"action":"wait","value":"9999999999999999999s"}]}""",
            """{"app":"교보문고","steps":[{"action":"scroll","target":"left"}]}""",
            """{"app":"인스타그램","steps":[{"action":"search","target":"날씨"}]}""",
            """{"app":"네이버","steps":[{"action":"search","target":"날씨","value":"forbidden"}]}""",
            """{"app":"구글","steps":[{"action":"play_first","target":"첫 영상"}]}""",
            """{"app":"네이버","steps":[{"action":"next_video","target":"다음 영상"}]}""",
        )
        invalid.forEach { json ->
            assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
        }
    }

    @Test
    fun `wait uses explicit units and bounded default`() {
        assertEquals(1000L, WaitDuration.millis(""))
        assertEquals(2000L, WaitDuration.millis("2"))
        assertEquals(250L, WaitDuration.millis("250ms"))
        assertEquals(30000L, WaitDuration.millis("30s"))
        assertThrows(IllegalArgumentException::class.java) { WaitDuration.millis("31s") }
    }

    @Test
    fun `rejects excessive step counts and preserves input whitespace`() {
        val steps = List(51) { """{"action":"tap","target":"검색"}""" }.joinToString(",")
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("""{"app":"교보문고","steps":[$steps]}""")
        }
        val plan = ActionPlanParser.parse("""{"app":"교보문고","steps":[{"action":"input_text","target":"검색","value":" a b "}]}""")
        assertEquals(" a b ", plan.steps.single().value)
    }

    @Test
    fun `parses steps in source order`() {
        val json = """
            {
              "app": "교보문고",
              "steps": [
                {"action":"open_app","target":"교보문고"},
                {"action":"tap","target":"검색창"},
                {"action":"input_text","target":"검색창","value":"데미안"},
                {"action":"wait","target":"1"}
              ]
            }
        """.trimIndent()

        val plan = ActionPlanParser.parse(json)

        assertEquals("교보문고", plan.app)
        assertEquals(
            listOf(ActionType.OPEN_APP, ActionType.TAP, ActionType.INPUT_TEXT, ActionType.WAIT),
            plan.steps.map { it.action },
        )
        assertEquals("데미안", plan.steps[2].value)
    }

    @Test
    fun `rejects unknown action`() {
        val json = """{"app":"교보문고","steps":[{"action":"delete","target":"책"}]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `requires value for input text`() {
        val json = """{"app":"알라딘","steps":[{"action":"input_text","target":"검색창"}]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `parses search playback and next video actions`() {
        val youtube = ActionPlanParser.parse(
            """{"app":"유튜브","steps":[{"action":"search","target":"오늘 뉴스"},{"action":"play_first","target":"첫 번째 동영상"},{"action":"next_video","target":"다음 동영상","value":"5s"}]}""",
        )
        assertEquals(
            listOf(ActionType.SEARCH, ActionType.PLAY_FIRST, ActionType.NEXT_VIDEO),
            youtube.steps.map { it.action },
        )
        assertEquals("오늘 뉴스", youtube.steps.first().target)
        assertEquals("5s", youtube.steps.last().value)

        val instagram = ActionPlanParser.parse(
            """{"app":"인스타","steps":[{"action":"next_video","target":"다음 릴스"}]}""",
        )
        assertEquals(ActionType.NEXT_VIDEO, instagram.steps.single().action)
    }

    @Test
    fun `open_app passes for an app that is not in the fixed table`() {
        val plan = ActionPlanParser.parse(
            """{"app":"마켓컬리","steps":[{"action":"open_app","target":"마켓컬리"}]}""",
        )
        assertEquals("마켓컬리", plan.app)
        // 이름이 다르면 같은 앱이 아니다.
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("""{"app":"마켓컬리","steps":[{"action":"open_app","target":"쿠팡"}]}""")
        }
    }

    @Test
    fun `play_first accepts an ordinal value and rejects other values`() {
        val ok = ActionPlanParser.parse("""{"app":"유튜브","steps":[{"action":"play_first","target":"두 번째 동영상","value":"2"}]}""")
        assertEquals("2", ok.steps.single().value)
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("""{"app":"유튜브","steps":[{"action":"play_first","target":"첫 번째 동영상","value":"빨리"}]}""")
        }
    }

    @Test
    fun `parses youtube music search and play_first`() {
        val plan = ActionPlanParser.parse(
            """{"app":"유튜브 뮤직","steps":[{"action":"search","target":"아이유"},{"action":"play_first","target":"첫 번째 노래"}]}""",
        )
        assertEquals(listOf(ActionType.SEARCH, ActionType.PLAY_FIRST), plan.steps.map { it.action })
    }

    @Test
    fun `rejects an empty step list`() {
        val json = """{"app":"교보문고","steps":[]}"""
        assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows(ActionPlanParseException::class.java) {
            ActionPlanParser.parse("not-json")
        }
    }
}
