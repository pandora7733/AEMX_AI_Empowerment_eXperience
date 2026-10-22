package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.ActionPlanParseException
import com.sihoo.aemx.accessibility.ActionPlanParser
import com.sihoo.aemx.pipeline.port.ActionExecutor.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.sihoo.aemx.accessibility.ActionType as A11yType

/**
 * 오케스트레이터 명령이 접근성 서비스의 파서를 통과하는 계획으로 바뀌는지 본다.
 * 파서는 서비스 쪽 진짜 코드를 그대로 쓴다.
 */
class StepPlanFactoryTest {

    private fun parse(command: Command, app: String) =
        ActionPlanParser.parse(StepPlanFactory.build(command, app).json)

    @Test
    fun `앱 실행은 open_app 이고 target 이 앱 이름이다`() {
        val plan = parse(Command.LaunchApp("com.google.android.youtube"), "유튜브")
        assertEquals("유튜브", plan.app)
        assertEquals(A11yType.OPEN_APP, plan.steps.single().action)
        assertEquals("유튜브", plan.steps.single().target)
    }

    @Test
    fun `고정 표에 없는 설치된 앱도 서비스 파서가 받는다`() {
        val plan = parse(Command.LaunchApp("com.kurly.android"), "마켓컬리")
        assertEquals("마켓컬리", plan.app)
        assertEquals(A11yType.OPEN_APP, plan.steps.single().action)
        assertEquals("마켓컬리", plan.steps.single().target)
    }

    @Test
    fun `누르기와 입력은 라벨과 값을 그대로 옮긴다`() {
        val tap = parse(Command.Tap("로그인"), "알라딘").steps.single()
        assertEquals(A11yType.TAP, tap.action)
        assertEquals("로그인", tap.target)

        val input = parse(Command.InputText("검색창", "해리포터"), "알라딘").steps.single()
        assertEquals(A11yType.INPUT_TEXT, input.action)
        assertEquals("검색창", input.target)
        assertEquals("해리포터", input.value)
    }

    @Test
    fun `검색어는 value 가 아니라 target 에 들어간다`() {
        val step = parse(Command.Search("유튜브", "노트북 추천"), "유튜브").steps.single()
        assertEquals(A11yType.SEARCH, step.action)
        assertEquals("노트북 추천", step.target)
        assertNull(step.value)
    }

    @Test
    fun `대상이 없는 동작도 파서를 통과한다`() {
        assertEquals(A11yType.PLAY_FIRST, parse(Command.PlayFirst, "유튜브").steps.single().action)

        val next = parse(Command.NextVideo("10s"), "유튜브").steps.single()
        assertEquals(A11yType.NEXT_VIDEO, next.action)
        assertEquals("10s", next.value)

        assertNull(parse(Command.NextVideo(null), "유튜브").steps.single().value)
    }

    @Test
    fun `스크롤 방향이 up 과 down 으로 바뀐다`() {
        assertEquals("up", parse(Command.Scroll(Command.Direction.UP), "네이버").steps.single().target)
        assertEquals("down", parse(Command.Scroll(Command.Direction.DOWN), "네이버").steps.single().target)
    }

    @Test
    fun `입력란 이름이 없으면 만들지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) {
            StepPlanFactory.build(Command.InputText(null, "abc"), "네이버")
        }
    }

    @Test
    fun `서비스가 지원하지 않는 조합은 서비스 파서가 거절한다`() {
        // play_first 는 유튜브에서만 된다. 이 거절이 제출 실패로 올라와 재분석 대상이 된다.
        assertThrows(ActionPlanParseException::class.java) { parse(Command.PlayFirst, "네이버") }
    }

    @Test
    fun `패키지명에서 서비스 앱 이름을 거꾸로 찾는다`() {
        assertEquals("유튜브", AppNames.forPackage("com.google.android.youtube"))
        assertEquals("알라딘", AppNames.forPackage("kr.co.aladin.third_shop"))
        assertNull(AppNames.forPackage("com.android.launcher"))
        assertNull(AppNames.forPackage(null))
    }
}
