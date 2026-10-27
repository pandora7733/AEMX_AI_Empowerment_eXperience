package com.sihoo.aemx.accessibility

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 시스템이 부르는 onInterrupt 는 돌고 있는 계획을 죽이지 않고, 사용자나 앱이 요청한 취소만 계획을 멈춘다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InterruptTest {

    /** onServiceConnected 는 protected 라 부를 수 없어서, 서비스 안의 실행기 자리에 직접 넣는다. */
    private fun serviceWithRunningPlan(): Pair<AemxAccessibilityService, ActionExecutor> {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val executor = ActionExecutor(service, service)
        AemxAccessibilityService::class.java.getDeclaredField("executor").apply {
            isAccessible = true
            set(service, executor)
        }
        executor.execute(ActionPlan("교보문고", listOf(ActionStep(ActionType.WAIT, "30"))))
        return service to executor
    }

    @Test
    fun `시스템 onInterrupt 는 실행 중인 계획을 취소하지 않는다`() {
        val (service, executor) = serviceWithRunningPlan()
        assertTrue(executor.isRunning)

        service.onInterrupt()

        assertTrue("시스템 요청으로 계획이 멈추면 안 된다", executor.isRunning)
    }

    @Test
    fun `요청한 취소는 계획을 멈춘다`() {
        val (service, executor) = serviceWithRunningPlan()

        service.cancelPlan()
        shadowLooper()

        assertFalse(executor.isRunning)
    }

    private fun shadowLooper() {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
