package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.llm.CloudRoutingPolicy
import com.sihoo.aemx.pipeline.llm.CloudRoutingPolicy.Route
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRoutingPolicyTest {

    private fun plan(app: String, vararg actions: ActionType) = ActionPlan(
        app = app,
        steps = actions.mapIndexed { i, a -> ActionStep(i, a, "x") },
    )

    @Test
    fun `유튜브 영상 재생은 클라우드로 보낸다`() {
        val p = plan("유튜브", ActionType.OPEN_APP, ActionType.SEARCH, ActionType.PLAY_FIRST)
        assertEquals(Route.CLOUD, CloudRoutingPolicy.route("유튜브에서 고양이 영상 틀어줘", p))
    }

    @Test
    fun `앱 열기와 검색만 있으면 로컬에서 처리한다`() {
        val p = plan("네이버", ActionType.OPEN_APP, ActionType.SEARCH)
        assertEquals(Route.LOCAL, CloudRoutingPolicy.route("네이버에서 서울 날씨 검색해줘", p))
    }

    @Test
    fun `메시지 보내기는 절대 클라우드로 보내지 않는다`() {
        val p = plan("카카오톡", ActionType.OPEN_APP, ActionType.SEND_MESSAGE, ActionType.TAP)
        assertEquals(Route.LOCAL, CloudRoutingPolicy.route("엄마한테 늦는다고 보내줘", p))
    }

    @Test
    fun `개인정보 앱이면 어려운 동작이 있어도 로컬이다`() {
        val p = plan("문자", ActionType.OPEN_APP, ActionType.TAP, ActionType.INPUT_TEXT)
        assertEquals(Route.LOCAL, CloudRoutingPolicy.route("문자 앱 열어서 입력해줘", p))
    }

    @Test
    fun `발화에 연락처 말이 있으면 계획이 유튜브여도 로컬이다`() {
        val p = plan("유튜브", ActionType.PLAY_FIRST)
        assertEquals(Route.LOCAL, CloudRoutingPolicy.route("엄마 전화번호 말고 노래 틀어줘", p))
    }

    @Test
    fun `개인정보 화면은 재분석에도 올리지 않는다`() {
        assertFalse(CloudRoutingPolicy.mayUploadScreen("엄마한테 보내줘", "카카오톡"))
        assertFalse(CloudRoutingPolicy.mayUploadScreen("노래 틀어줘", "메시지"))
        assertFalse(CloudRoutingPolicy.mayUploadScreen("노래 틀어줘", "com.kakao.talk"))
        assertFalse(CloudRoutingPolicy.mayUploadScreen("노래 틀어줘", "com.samsung.android.messaging"))
        assertTrue(CloudRoutingPolicy.mayUploadScreen("고양이 영상 틀어줘", "유튜브"))
        assertTrue(CloudRoutingPolicy.mayUploadScreen("고양이 영상 틀어줘", "com.google.android.youtube"))
    }
}
