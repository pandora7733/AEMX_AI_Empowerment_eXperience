package com.sihoo.aemx.accessibility

import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** 열기·검색·탭·입력 뒤 정착 대기를 건너뛰는지(step 단위 호출자), 재생 등은 그대로 기다리는지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SettleSkipTest {
    private class Events : ActionExecutor.Listener {
        val started = mutableListOf<Int>()
        override fun onStepStarted(index: Int, step: ActionStep) { started += index }
        override fun onCompleted(plan: ActionPlan, evidence: String?) {}
        override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {}
    }

    private fun node() = AccessibilityNodeInfo.obtain().apply {
        text = "검색창"
        packageName = "mok.android"
        isEnabled = true
        isVisibleToUser = true
        isEditable = true
        isClickable = true
    }

    private fun plan() = ActionPlan("교보문고", listOf(
        ActionStep(ActionType.TAP, "검색창"),
        ActionStep(ActionType.WAIT, "1"),
    ))

    @Test fun `step 단위 호출자는 탭 뒤 정착을 기다리지 않고 바로 다음 step 으로 간다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(node())
        val events = Events()

        ActionExecutor(service, events).execute(plan(), settleAfterStep = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0, 1), events.started)
    }

    @Test fun `기본값은 탭 뒤 정착 300ms 를 기다린 뒤 다음 step 으로 간다`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(node())
        val events = Events()

        ActionExecutor(service, events).execute(plan())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0), events.started)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(listOf(0, 1), events.started)
    }
}
