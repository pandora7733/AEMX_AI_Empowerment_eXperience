package com.sihoo.aemx.assistant.overlay

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** 나타내기가 멈춰도 몸통이 최종 모양이 되는지, 사라지는 움직임과 충돌하지 않는지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CapsuleEnterFallbackTest {

    private fun newView() = View(RuntimeEnvironment.getApplication())

    @Test fun `나타내기 뒤 시간이 지나면 몸통은 최종 모양이 된다`() {
        val view = newView()
        OverlayMotion.enter(view, distancePx = 30f, reduced = false)
        assertEquals(0f, view.alpha, 0.001f)

        ShadowLooper.idleMainLooper(OverlayMotion.CAPSULE_IN_FALLBACK_MS, TimeUnit.MILLISECONDS)

        assertEquals(1f, view.alpha, 0.001f)
        assertEquals(1f, view.scaleX, 0.001f)
        assertEquals(0f, view.translationY, 0.001f)
    }

    @Test fun `사라지는 중이면 나타내기 뒤처리가 끼어들지 않는다`() {
        val view = newView()
        OverlayMotion.enter(view, distancePx = 30f, reduced = false)
        var ended = false
        OverlayMotion.exit(view, reduced = false) { ended = true }

        ShadowLooper.idleMainLooper(OverlayMotion.CAPSULE_IN_FALLBACK_MS + 100, TimeUnit.MILLISECONDS)

        assertTrue("사라지는 움직임이 끝까지 가야 한다", ended)
        assertEquals(0f, view.alpha, 0.001f)
    }

    @Test fun `시스템 애니메이션이 꺼져 있으면 바로 최종 모양이다`() {
        val view = newView()
        OverlayMotion.enter(view, distancePx = 30f, reduced = true)

        assertEquals(1f, view.alpha, 0.001f)
        assertEquals(0f, view.translationY, 0.001f)
    }
}
