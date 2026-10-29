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

    @Test
    fun `발화만 보고 어려운 명령은 클라우드로 보낸다`() {
        val hard = listOf(
            "유튜브에서 고양이 영상 틀어줘",
            "유튜브에서 손흥민 하이라이트 검색해서 세 번째 영상 틀어줘",
            "유튜브 쇼츠 보여줘",
            "인스타 릴스에서 다음 영상 넘겨줘",
            "알라딘에서 해리포터 검색해줘",
            "쿠팡 켜서 생수 2리터 검색해",
            "유투브에서 아이브 노래 틀어 줘",
            "음 그 유튜브 뮤직에서 비투비 그리워하다 좀 틀어봐",
            "알라딘 열고 검색창에 데미안 쳐줘",
        )
        hard.forEach { assertEquals(it, Route.CLOUD, CloudRoutingPolicy.routeUtterance(it)) }
    }

    @Test
    fun `발화만 보고 간단한 명령은 기기에서 처리한다`() {
        val simple = listOf(
            "네이버에서 서울 날씨 찾아줘",
            "구글에서 오늘 뉴스 검색해줘",
            "유튜브 열어줘",
            "플레이스토어 열어줘",
            "플레이 스토어 열어줘",
        )
        simple.forEach { assertEquals(it, Route.LOCAL, CloudRoutingPolicy.routeUtterance(it)) }
    }

    @Test
    fun `발화만 보고 개인정보 명령은 어려운 말이 있어도 기기에서 처리한다`() {
        val personal = listOf(
            "배재준한테 카톡으로 안녕이라고 보내줘",
            "문자로 엄마한테 늦는다고 보내줘",
            "카톡으로 유튜브 영상 틀어 달라고 보내줘",
            "엄마한테 사진 보내줘",
        )
        personal.forEach { assertEquals(it, Route.LOCAL, CloudRoutingPolicy.routeUtterance(it)) }
    }
}
