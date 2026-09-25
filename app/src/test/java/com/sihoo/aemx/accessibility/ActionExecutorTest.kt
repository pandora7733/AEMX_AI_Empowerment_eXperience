package com.sihoo.aemx.accessibility

import android.os.Looper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ActionExecutorTest {
    private class Events : ActionExecutor.Listener {
        val started = mutableListOf<Int>()
        var completed = false
        var failed = false
        override fun onStepStarted(index: Int, step: ActionStep) { started += index }
        override fun onCompleted(plan: ActionPlan) { completed = true }
        override fun onFailed(index: Int, step: ActionStep, message: String) { failed = true }
    }

    private fun node(label: String = "검색창", pkg: String = "mok.android") =
        AccessibilityNodeInfo.obtain().apply {
            text = label
            packageName = pkg
            isEnabled = true
            isVisibleToUser = true
            isEditable = true
            isClickable = true
        }

    @Test fun `tap input and wait preserve order and do not replay successful actions`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node()
        shadowOf(service).setRootInActiveWindow(root)
        val actions = mutableListOf<Int>()
        shadowOf(root).setOnPerformActionListener { action, args ->
            actions += action
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                assertEquals("데미안", args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            }
            true
        }
        val events = Events()
        val executor = ActionExecutor(service, UnconfiguredCredentialProvider, events)
        executor.execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.TAP, "검색창"),
            ActionStep(ActionType.INPUT_TEXT, "검색창", "데미안"),
            ActionStep(ActionType.WAIT, "1"),
        )))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0), events.started)
        executor.onAccessibilityTreeChanged()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(0, 1, 2), events.started)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_SET_TEXT), actions)
        assertTrue(events.completed)
    }

    @Test fun `wrong package times out without acting or advancing`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node(pkg = "another.app")
        shadowOf(service).setRootInActiveWindow(root)
        var acted = false
        shadowOf(root).setOnPerformActionListener { _, _ -> acted = true; true }
        val events = Events()
        ActionExecutor(service, UnconfiguredCredentialProvider, events).execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.TAP, "검색창"), ActionStep(ActionType.WAIT, "1"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertFalse(acted)
        assertTrue(events.failed)
        assertEquals(listOf(0), events.started)
    }

    @Test fun `cancel prevents delayed work and duplicate submissions are rejected`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val events = Events()
        val executor = ActionExecutor(service, UnconfiguredCredentialProvider, events)
        val plan = ActionPlan("교보문고", listOf(ActionStep(ActionType.WAIT, "2"), ActionStep(ActionType.WAIT, "1")))
        executor.execute(plan)
        assertThrows(IllegalStateException::class.java) { executor.execute(plan) }
        executor.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(0), events.started)
        assertFalse(events.completed)
        assertFalse(executor.isRunning)
    }

    @Test fun `missing credentials fail closed`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        shadowOf(service).setRootInActiveWindow(node("id"))
        val events = Events()
        ActionExecutor(service, UnconfiguredCredentialProvider, events).execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.INPUT_CREDENTIAL, "id"), ActionStep(ActionType.TAP, "검색창"),
        )))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.failed)
        assertEquals(listOf(0), events.started)
    }

    @Test fun `missing app fails instead of advancing`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        assertTrue(AppLauncher(service).launch("교보문고").isFailure)
    }

    @Test fun `open app launches once and waits for its window`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("mok.android")
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "mok.android.MainActivity"
                packageName = "mok.android"
                applicationInfo = ApplicationInfo().apply { packageName = "mok.android" }
            }
        }
        shadowOf(service.packageManager).addResolveInfoForIntent(launch, info)
        val events = Events()
        val executor = ActionExecutor(service, UnconfiguredCredentialProvider, events)
        executor.execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.OPEN_APP, "교보문고"), ActionStep(ActionType.WAIT, "0"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(events.completed)
        assertEquals(listOf(0), events.started)
        shadowOf(service).setRootInActiveWindow(node())
        executor.onAccessibilityTreeChanged()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(events.completed)
        val app = shadowOf(RuntimeEnvironment.getApplication())
        assertEquals("mok.android", app.nextStartedActivity.component?.packageName)
        assertNull(app.nextStartedActivity)
    }

    @Test @Config(sdk = [24, 28])
    fun `node lookup ignores hidden and partial labels and supports parent clicks`() {
        val root = node("container")
        val label = node("로그인").apply { isClickable = false; isEditable = false }
        shadowOf(root).addChild(label)
        assertEquals(label, NodeFinder.find(root, "로그인"))
        assertEquals(root, NodeFinder.clickableAncestor(label))
        assertNull(NodeFinder.find(root, "로그"))
        assertNull(NodeFinder.find(root, "로그인", editableOnly = true))
        label.isVisibleToUser = false
        assertNull(NodeFinder.find(root, "로그인"))
    }

    @Test fun `duplicate labels require disambiguation and IDs are supported`() {
        val root = node("container")
        val first = node("로그인").apply { viewIdResourceName = "mok.android:id/login" }
        shadowOf(root).addChild(first)
        shadowOf(root).addChild(node("로그인"))
        assertNull(NodeFinder.find(root, "로그인"))
        assertEquals(first, NodeFinder.find(root, "mok.android:id/login"))
    }
}
