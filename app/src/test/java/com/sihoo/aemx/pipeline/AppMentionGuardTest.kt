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
}
