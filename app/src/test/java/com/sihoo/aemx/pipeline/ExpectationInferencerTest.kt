package com.sihoo.aemx.pipeline

import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.UnchangedOk
import com.sihoo.aemx.pipeline.port.AppResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectationInferencerTest {
    private val resolver = object : AppResolver {
        override fun resolvePackage(appName: String): String? =
            if (appName == "유튜브") "com.google.android.youtube" else null
    }
    private val inferencer = ExpectationInferencer(resolver)

    @Test
    fun `검색은 앱과 검색어 앞부분을 이미 떠 있는 결과의 증거로 단다`() {
        val step = inferencer.enrich(ActionStep(0, ActionType.SEARCH, "페이커 롤드컵 하이라이트 모음"), "유튜브")
        assertEquals(true, step.expect.requireScreenChange)
        assertEquals(UnchangedOk("com.google.android.youtube", listOf("페이커 롤드컵")), step.expect.unchangedOk)
    }

    @Test
    fun `앱을 모르면 이미 떠 있는 결과로 봐 주지 않는다`() {
        val step = inferencer.enrich(ActionStep(0, ActionType.SEARCH, "페이커"), "모르는앱")
        assertNull(step.expect.unchangedOk)
        assertEquals(true, step.expect.requireScreenChange)
    }
}
