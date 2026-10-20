package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.pipeline.PipelineState
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionAnnouncerTest {

    private val texts = AnnouncementTexts(
        finished = "끝",
        failed = "실패",
        needsUser = "확인 필요",
        needsSetup = "준비 필요"
    )

    private val screen = ScreenSnapshot(packageName = "pkg", className = null, texts = emptyList())

    @Test
    fun `최종 상태마다 맞는 문구를 말한다`() {
        assertEquals("끝", announcementFor(PipelineState.Finished("완료"), texts))
        assertEquals("실패", announcementFor(PipelineState.Failed("이유"), texts))
        assertEquals("확인 필요", announcementFor(PipelineState.NeedsUser("캡차", screen), texts))
        assertEquals("준비 필요", announcementFor(PipelineState.NeedsSetup("서비스 꺼짐"), texts))
    }

    @Test
    fun `진행 중이거나 취소로 돌아온 Idle 은 말하지 않는다`() {
        assertNull(announcementFor(PipelineState.Idle, texts))
        assertNull(announcementFor(PipelineState.Listening("하이"), texts))
        assertNull(announcementFor(PipelineState.Planning("유튜브 틀어줘", 0), texts))
        assertNull(announcementFor(PipelineState.Verifying(ActionStep(index = 0, action = ActionType.WAIT, target = null)), texts))
    }
}
