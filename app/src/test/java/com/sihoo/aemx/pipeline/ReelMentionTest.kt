package com.sihoo.aemx.pipeline

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.llm.AppMentionGuard
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** "인스타 릴스에서 다음 영상" 처럼 클라우드가 낸 앱 이름이 인스타그램으로 맞춰지는지. */
class ReelMentionTest {

    @Test fun `인스타 릴스 지목은 인스타그램 계획으로 맞춘다`() {
        val plan = ActionPlan(
            "인스타 릴스",
            listOf(
                ActionStep(0, ActionType.OPEN_APP, "인스타 릴스"),
                ActionStep(1, ActionType.TAP, "릴스"),
                ActionStep(2, ActionType.NEXT_VIDEO, "다음"),
            ),
        )

        val fixed = AppMentionGuard.apply("인스타 릴스에서 다음 영상 넘겨줘", plan)

        // 계획의 이름은 그대로 두고, 열기 대상도 그 이름에 맞춘다. 이름은 AppLauncher 가 인스타그램으로 푼다.
        assertEquals("인스타 릴스", fixed.app)
        assertEquals("인스타 릴스", fixed.steps[0].target)
        assertEquals(AppLauncher.INSTAGRAM_PACKAGE, AppLauncher.packageFor(fixed.app))
    }
}
