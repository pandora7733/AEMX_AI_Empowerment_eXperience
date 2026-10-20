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
        var failureMessage: String? = null
        var failureScreen: ScreenDescription? = null
        override fun onStepStarted(index: Int, step: ActionStep) { started += index }
        override fun onCompleted(plan: ActionPlan) { completed = true }
        override fun onFailed(
            index: Int,
            step: ActionStep,
            message: String,
            screen: ScreenDescription,
        ) {
            failed = true
            failureMessage = message
            failureScreen = screen
        }
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
        val executor = ActionExecutor(service, events)
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
        ActionExecutor(service, events).execute(ActionPlan("교보문고", listOf(
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
        val executor = ActionExecutor(service, events)
        val plan = ActionPlan("교보문고", listOf(ActionStep(ActionType.WAIT, "2"), ActionStep(ActionType.WAIT, "1")))
        executor.execute(plan)
        assertThrows(IllegalStateException::class.java) { executor.execute(plan) }
        executor.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(0), events.started)
        assertFalse(events.completed)
        assertFalse(executor.isRunning)
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
        val executor = ActionExecutor(service, events)
        executor.execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.OPEN_APP, "교보문고"), ActionStep(ActionType.WAIT, "0"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(events.completed)
        assertEquals(listOf(0), events.started)
        shadowOf(service).setRootInActiveWindow(node())
        executor.onAccessibilityTreeChanged()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(events.completed)
        val app = shadowOf(RuntimeEnvironment.getApplication())
        assertEquals("mok.android", app.nextStartedActivity.component?.packageName)
        assertNull(app.nextStartedActivity)
    }

    @Test fun `Aladin launch reports a closed app instead of advancing`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val appPackage = "kr.co.aladin.third_shop"
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(appPackage)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "$appPackage.MainActivity"
                packageName = appPackage
                applicationInfo = ApplicationInfo().apply { packageName = appPackage }
            }
        }
        shadowOf(service.packageManager).addResolveInfoForIntent(launch, info)
        shadowOf(service).setRootInActiveWindow(node("AEMX", "com.sihoo.aemx"))
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("알라딘", listOf(
            ActionStep(ActionType.OPEN_APP, "알라딘"), ActionStep(ActionType.WAIT, "0"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        shadowOf(service).setRootInActiveWindow(node("splash", appPackage))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(events.completed)
        shadowOf(service).setRootInActiveWindow(node("AEMX", "com.sihoo.aemx"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertTrue(events.failed)
        assertFalse(events.completed)
        assertEquals(listOf(0), events.started)
        assertEquals("앱 화면이 열리지 않았습니다. 앱이 종료되거나 접근이 차단되었는지 확인해 주세요.", events.failureMessage)
        assertEquals(appPackage, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component?.packageName)
    }

    @Test
    fun `node lookup ignores hidden and partial labels and supports parent clicks`() {
        val root = node("container")
        val label = node("확인").apply { isClickable = false; isEditable = false }
        shadowOf(root).addChild(label)
        assertEquals(label, NodeFinder.find(root, "확인"))
        assertEquals(root, NodeFinder.clickableAncestor(label))
        assertNull(NodeFinder.find(root, "확"))
        assertNull(NodeFinder.find(root, "확인", editableOnly = true))
        label.isVisibleToUser = false
        assertNull(NodeFinder.find(root, "확인"))
    }

    @Test fun `duplicate labels require disambiguation and IDs are supported`() {
        val root = node("container")
        val first = node("검색").apply { viewIdResourceName = "mok.android:id/search" }
        shadowOf(root).addChild(first)
        shadowOf(root).addChild(node("검색"))
        assertNull(NodeFinder.find(root, "검색"))
        assertEquals(first, NodeFinder.find(root, "mok.android:id/search"))
    }

    @Test fun `scroll uses requested direction and ignores hidden containers`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node("root").apply { isEditable = false }
        val hidden = node("hidden").apply { isScrollable = true; isVisibleToUser = false }
        val content = node("content").apply { isScrollable = true }
        shadowOf(root).addChild(hidden)
        shadowOf(root).addChild(content)
        shadowOf(service).setRootInActiveWindow(root)
        shadowOf(hidden).setOnPerformActionListener { _, _ -> fail("Hidden container used"); false }
        val actions = mutableListOf<Int>()
        shadowOf(content).setOnPerformActionListener { action, _ -> actions += action; true }
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.SCROLL, "down"), ActionStep(ActionType.SCROLL, "up"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), actions)
        assertTrue(events.completed)
    }

    @Test fun `rejected UI action times out without advancing`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node("검색창")
        shadowOf(service).setRootInActiveWindow(root)
        var attempts = 0
        shadowOf(root).setOnPerformActionListener { _, _ -> attempts++; false }
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("교보문고", listOf(
            ActionStep(ActionType.INPUT_TEXT, "검색창", "데미안"), ActionStep(ActionType.TAP, "검색창"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue(attempts > 1)
        assertTrue(events.failed)
        assertFalse(events.completed)
        assertEquals(listOf(0), events.started)
    }

    @Test fun `play first selects a visible clickable video result`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node("results", AppLauncher.YOUTUBE_PACKAGE).apply { isEditable = false }
        // 실기기의 검색 결과 항목은 resource-id 없이 content-desc 꼬리말("동영상 재생")로만 구분된다.
        val video = node("오늘의 날씨 영상", AppLauncher.YOUTUBE_PACKAGE).apply {
            isEditable = false
            contentDescription = "오늘의 날씨 영상 - 3분 12초 - 채널 채널로 이동 - 동영상 재생"
        }
        shadowOf(root).addChild(video)
        shadowOf(service).setRootInActiveWindow(root)
        var clicked = false
        shadowOf(video).setOnPerformActionListener { action, _ ->
            clicked = action == AccessibilityNodeInfo.ACTION_CLICK
            clicked
        }
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.PLAY_FIRST, "첫 번째 동영상"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(clicked)
        assertTrue(events.completed)
    }

    @Test fun `next video scrolls forward after its optional delay`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val root = node("Shorts", AppLauncher.YOUTUBE_PACKAGE).apply {
            isEditable = false
            isScrollable = true
        }
        shadowOf(service).setRootInActiveWindow(root)
        val actions = mutableListOf<Int>()
        shadowOf(root).setOnPerformActionListener { action, _ -> actions += action; true }
        val events = Events()
        ActionExecutor(service, events).execute(ActionPlan("유튜브", listOf(
            ActionStep(ActionType.NEXT_VIDEO, "다음 동영상", "250ms"),
        )))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(actions.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions)
        assertTrue(events.completed)
    }
}
