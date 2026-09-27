package com.sihoo.aemx.accessibility

import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class CredentialLoginTest {
    private fun node(id: String, secret: Boolean = false, appPackage: String = "mok.android") = AccessibilityNodeInfo.obtain().apply {
        packageName = appPackage
        viewIdResourceName = id
        isVisibleToUser = true
        isEnabled = true
        isEditable = true
        isPassword = secret
    }
    private fun form(appPackage: String = "mok.android"): Triple<AccessibilityNodeInfo, AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val root = node("root", appPackage = appPackage).apply { isEditable = false }
        val id = node("$appPackage:id/userId", appPackage = appPackage)
        val password = node("password", true, appPackage)
        shadowOf(root).addChild(id)
        shadowOf(root).addChild(password)
        return Triple(root, id, password)
    }

    @Test fun `unique login form maps ID and password and rejects unsafe screens`() {
        val (root, id, password) = form()
        assertEquals(id, CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
        assertEquals(password, CredentialNodeFinder.find(root, "교보문고", CredentialField.PASSWORD))
        assertNull(CredentialNodeFinder.find(root, "알라딘", CredentialField.PASSWORD))
        password.isPassword = false
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.PASSWORD))
        password.isPassword = true
        shadowOf(root).addChild(node("confirmPassword", true))
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
    }

    @Test fun `ambiguous IDs and foreign child nodes are rejected`() {
        val (root, id, _) = form()
        id.packageName = "other.app"
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
        id.packageName = "mok.android"
        shadowOf(root).addChild(node("loginId"))
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
    }

    @Test fun `Kyobo unlabeled web fields require exactly one login button and one ID field`() {
        val (root, id, password) = form()
        id.viewIdResourceName = null
        password.viewIdResourceName = null
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
        val login = node("loginBtn").apply { isEditable = false; isClickable = true }
        shadowOf(root).addChild(login)
        assertEquals(id, CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
        assertEquals(password, CredentialNodeFinder.find(root, "교보문고", CredentialField.PASSWORD))
        shadowOf(root).addChild(node("extraField"))
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
    }

    @Test fun `Aladin simulated unlabeled form requires one visible login button`() {
        val (root, id, password) = form("kr.co.aladin.third_shop")
        id.viewIdResourceName = null
        password.viewIdResourceName = null
        assertNull(CredentialNodeFinder.find(root, "알라딘", CredentialField.ID))
        val login = node("submit", appPackage = "kr.co.aladin.third_shop").apply {
            isEditable = false
            isClickable = true
            className = "android.widget.Button"
            text = "로그인"
        }
        shadowOf(root).addChild(login)
        assertEquals(id, CredentialNodeFinder.find(root, "알라딘", CredentialField.ID))
        assertEquals(password, CredentialNodeFinder.find(root, "알라딘", CredentialField.PASSWORD))
        assertNull(CredentialNodeFinder.find(root, "교보문고", CredentialField.ID))
        shadowOf(root).addChild(node("another", appPackage = "kr.co.aladin.third_shop").apply {
            isEditable = false
            isClickable = true
            className = "android.widget.Button"
            text = "로그인"
        })
        assertNull(CredentialNodeFinder.find(root, "알라딘", CredentialField.ID))
    }

    @Test fun `credentials are delivered in order and cancellation ignores late reads`() {
        val service = Robolectric.buildService(AemxAccessibilityService::class.java).create().get()
        val (root, id, password) = form()
        shadowOf(service).setRootInActiveWindow(root)
        val entered = mutableListOf<String>()
        listOf(id, password).forEach { node -> shadowOf(node).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                entered += args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()
            }
            true
        } }
        var completed = false
        val listener = object : ActionExecutor.Listener {
            override fun onStepStarted(index: Int, step: ActionStep) = Unit
            override fun onCompleted(plan: ActionPlan) { completed = true }
            override fun onFailed(index: Int, step: ActionStep, message: String) { fail(message) }
        }
        val provider = object : CredentialProvider {
            override fun getCredential(app: String, field: CredentialField) = if (field == CredentialField.ID) "demo-id" else "demo-secret"
        }
        val plan = ActionPlan("교보문고", listOf(ActionStep(ActionType.INPUT_CREDENTIAL, "id"),
            ActionStep(ActionType.INPUT_CREDENTIAL, "password")))
        ActionExecutor(service, provider, listener, Executor { it.run() }).execute(plan)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf("demo-id", "demo-secret"), entered)
        assertTrue(completed)
        entered.clear()
        val queued = mutableListOf<Runnable>()
        val executor = ActionExecutor(service, provider, listener, Executor { queued += it })
        executor.execute(plan)
        executor.cancel()
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(entered.isEmpty())
    }
}
