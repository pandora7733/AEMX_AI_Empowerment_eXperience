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
    fun `검색은 앱 화면에서 이번 검색어 앞부분이 보여야 성공으로 본다`() {
        val step = inferencer.enrich(ActionStep(0, ActionType.SEARCH, "페이커 롤드컵 하이라이트 모음"), "유튜브")
        assertEquals("com.google.android.youtube", step.expect.packageName)
        assertEquals(listOf("페이커 롤드컵"), step.expect.anyText)
        assertEquals(false, step.expect.requireScreenChange)
    }

    @Test
    fun `앱을 모르면 패키지 없이 검색어만 확인한다`() {
        val step = inferencer.enrich(ActionStep(0, ActionType.SEARCH, "페이커"), "모르는앱")
        assertNull(step.expect.packageName)
        assertEquals(listOf("페이커"), step.expect.anyText)
    }
}
