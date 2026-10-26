package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.AppMentionGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppMentionGuardTest {

    private fun plan(app: String, query: String) = ActionPlan(
        app = app,
        steps = listOf(
            ActionStep(0, ActionType.OPEN_APP, app),
            ActionStep(1, ActionType.SEARCH, query),
        ),
    )

    @Test
    fun `검색어에 다른 앱 이름이 있어도 앞에서 지목한 앱을 따른다`() {
        // 실기기에서 모델이 앱을 유튜브로 냈던 경우.
        val fixed = AppMentionGuard.apply("네이버에 유투브 검색해줘", plan("유튜브", "유투브"))

        assertEquals("네이버", fixed.app)
        assertEquals("네이버", fixed.steps[0].target)
        assertEquals("유투브", fixed.steps[1].target)
    }

    @Test
    fun `이미 맞으면 그대로 둔다`() {
        val original = plan("네이버", "날씨")
        assertSame(original, AppMentionGuard.apply("네이버에서 날씨 알려줘", original))
    }

    @Test
    fun `조사 없이 앱 이름만 나오면 건드리지 않는다`() {
        val original = plan("인스타그램", "릴스")
        assertSame(original, AppMentionGuard.apply("인스타 릴스 다음 영상 보여줘", original))
    }

    @Test
    fun `문장 앞이 아니면 건드리지 않는다`() {
        val original = plan("유튜브", "고양이")
        assertSame(original, AppMentionGuard.apply("고양이를 네이버에서 찾아줘", original))
    }

    @Test
    fun `에서와 에를 구분해 읽는다`() {
        assertEquals("구글", AppMentionGuard.leadingApp("구글에서 뉴스 검색해줘"))
        assertEquals("구글", AppMentionGuard.leadingApp("구글에 뉴스 검색해줘"))
        assertEquals("인스타그램", AppMentionGuard.leadingApp("인스타그램에서 릴스 보여줘"))
        assertEquals(null, AppMentionGuard.leadingApp("구글 뉴스 검색해줘"))
    }

    @Test
    fun `재분석 계획도 지목한 앱으로 바로잡는다`() {
        // 재분석 요청의 원래 문장은 그대로 "네이버에서 유튜브 검색해줘" 이고, 모델이 앱을 유튜브로 바꿔 낸 경우.
        val fixed = AppMentionGuard.apply("네이버에서 유튜브 검색해줘", plan("유튜브", "유튜브"))

        assertEquals("네이버", fixed.app)
        assertEquals("네이버", fixed.steps[0].target)
        assertEquals("유튜브", fixed.steps[1].target)
    }

    @Test
    fun `지목이 없는 문장은 재분석에서 앱이 바뀌어도 그대로 둔다`() {
        val replanned = plan("크롬", "유튜브")
        assertSame(replanned, AppMentionGuard.apply("유튜브 검색해줘", replanned))
    }

    @Test
    fun `유튜브 뮤직은 유튜브보다 먼저 읽는다`() {
        assertEquals("유튜브 뮤직", AppMentionGuard.leadingApp("유튜브 뮤직에서 아이유 노래 틀어줘"))
        assertEquals("유튜브 뮤직", AppMentionGuard.leadingApp("유튜브뮤직에서 아이유 노래 틀어줘"))
        assertEquals("유튜브", AppMentionGuard.leadingApp("유튜브에서 고양이 영상 틀어줘"))
    }

    @Test
    fun `모델이 유튜브 뮤직을 유튜브로 내면 바로잡는다`() {
        // 폰의 모델이 "유튜브뮤직에서 ..." 를 유튜브로 낸 경우.
        val fixed = AppMentionGuard.apply("유튜브뮤직에서 아이유 노래 틀어줘", plan("유튜브", "아이유 노래"))

        assertEquals("유튜브 뮤직", fixed.app)
        assertEquals("유튜브 뮤직", fixed.steps[0].target)
        assertEquals("아이유 노래", fixed.steps[1].target)
    }

    @Test
    fun `카톡 쿠팡 문자 별칭을 읽는다`() {
        assertEquals("카카오톡", AppMentionGuard.leadingApp("카톡에서 엄마 찾아줘"))
        assertEquals("카카오톡", AppMentionGuard.leadingApp("카카오톡에서 엄마 찾아줘"))
        assertEquals("쿠팡", AppMentionGuard.leadingApp("쿠팡에서 물티슈 찾아줘"))
        assertEquals("메시지", AppMentionGuard.leadingApp("문자에서 엄마 찾아줘"))
        assertEquals(null, AppMentionGuard.leadingApp("쿠팡 열어줘"))
    }

    private fun openPlan(app: String) = ActionPlan(app = app, steps = listOf(ActionStep(0, ActionType.OPEN_APP, app)))

    @Test
    fun `넷플릭스 열어줘인데 계획이 쿠팡이면 넷플릭스로 바로잡는다`() {
        val fixed = AppMentionGuard.apply("넷플릭스 열어줘", openPlan("쿠팡"))

        assertEquals("넷플릭스", fixed.app)
        assertEquals("넷플릭스", fixed.steps[0].target)
    }

    @Test
    fun `앱 열기 말투의 조사와 앱 글자를 걷어 낸다`() {
        assertEquals("넷플릭스", AppMentionGuard.apply("넷플릭스를 열어줘", openPlan("쿠팡")).app)
        assertEquals("넷플릭스", AppMentionGuard.apply("넷플릭스 앱 켜줘", openPlan("쿠팡")).app)
        assertEquals("인스타그램", AppMentionGuard.apply("인스타 열어줘", openPlan("쿠팡")).app)
    }

    @Test
    fun `앱 열기 말이어도 계획에 다른 동작이 있으면 건드리지 않는다`() {
        val original = plan("쿠팡", "물티슈")
        assertSame(original, AppMentionGuard.apply("넷플릭스 열어서 찾아줘", original))
    }

    @Test
    fun `이미 맞는 앱 열기 계획은 그대로 둔다`() {
        val original = openPlan("넷플릭스")
        assertSame(original, AppMentionGuard.apply("넷플릭스 열어줘", original))
    }

    @Test
    fun `앱은 맞고 open_app target 만 깨졌으면 target 을 앱에 맞춘다`() {
        // 폰의 모델이 target 에 태국 문자를 섞어 냈던 경우.
        val broken = ActionPlan(app = "넷플릭스", steps = listOf(ActionStep(0, ActionType.OPEN_APP, "넷플ิก스")))
        val fixed = AppMentionGuard.apply("넷플릭스 열어줘", broken)

        assertEquals("넷플릭스", fixed.app)
        assertEquals("넷플릭스", fixed.steps[0].target)
    }

    @Test
    fun `지목이 없는 문장도 open_app target 이 앱과 다르면 맞춘다`() {
        val broken = ActionPlan(app = "유튜브", steps = listOf(ActionStep(0, ActionType.OPEN_APP, "유튜ㅂ"), ActionStep(1, ActionType.SEARCH, "고양이")))
        val fixed = AppMentionGuard.apply("유튜브 고양이 검색해줘", broken)

        assertEquals("유튜브", fixed.app)
        assertEquals("유튜브", fixed.steps[0].target)
        assertEquals("고양이", fixed.steps[1].target)
    }

    @Test
    fun `열어줘를 검색어로 붙인 계획은 open_app 하나로 바꾼다`() {
        // 폰의 모델이 "멜론 열어줘" 에 search "열어줘" 를 붙여 냈던 경우.
        val bad = ActionPlan("멜론", listOf(ActionStep(0, ActionType.OPEN_APP, "멜론"), ActionStep(1, ActionType.SEARCH, "열어줘")))
        val fixed = AppMentionGuard.apply("멜론 열어줘", bad)

        assertEquals("멜론", fixed.app)
        assertEquals(1, fixed.steps.size)
        assertEquals(ActionType.OPEN_APP, fixed.steps[0].action)
        assertEquals("멜론", fixed.steps[0].target)
    }

    @Test
    fun `열기 뒤에 다른 요청이 붙은 말은 건드리지 않는다`() {
        val original = plan("유튜브", "고양이")
        assertSame(original, AppMentionGuard.apply("유튜브 열어서 고양이 검색해줘", original))
        assertEquals(null, AppMentionGuard.openOnlyApp("유튜브 열어서 고양이 검색해줘"))
        assertEquals("멜론", AppMentionGuard.openOnlyApp("멜론 좀 실행해줘"))
        assertEquals("멜론", AppMentionGuard.openOnlyApp("멜론 앱을 켜줘."))
    }

    @Test
    fun `이미 open_app 하나뿐인 계획은 그대로 둔다`() {
        val original = openPlan("멜론")
        assertSame(original, AppMentionGuard.apply("멜론 열어줘", original))
    }
}
