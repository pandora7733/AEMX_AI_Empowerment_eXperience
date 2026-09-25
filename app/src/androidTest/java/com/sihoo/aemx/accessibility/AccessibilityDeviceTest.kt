package com.sihoo.aemx.accessibility

import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.EditText
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sihoo.aemx.MainActivity
import com.sihoo.aemx.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-process UI/node tests. Live service execution is tested by the external device-tests module. */
@RunWith(AndroidJUnit4::class)
class AccessibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun preserveEnabledServices() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test fun serviceIsRegisteredWithSystemBindingPermission() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AemxAccessibilityService::class.java), PackageManager.GET_META_DATA,
        )
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
        assertTrue(info.metaData.getInt("android.accessibilityservice") != 0)
    }

    @Test fun launcherOpensJsonScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.action_plan_title)).perform(click())
            onView(withHint(R.string.action_plan_hint)).check(matches(isDisplayed()))
        }
    }

    @Test fun malformedJsonIsReportedOnScreen() {
        ActivityScenario.launch(ActionPlanActivity::class.java).use {
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText("not-json"), closeSoftKeyboard())
            onView(withText(R.string.action_plan_execute)).perform(scrollTo(), click())
            val expected = if (AemxAccessibilityService.isConnected()) {
                "Action Plan JSON 형식이 올바르지 않습니다."
            } else "AEMX 접근성 서비스를 먼저 활성화해 주세요."
            onView(withText(expected)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun realNodeTreeCanSetTextAndClick() {
        ActivityScenario.launch(ActionPlanActivity::class.java).use {
            onView(withHint(R.string.action_plan_hint)).check(matches(isDisplayed()))
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val root = checkNotNull(automation.rootInActiveWindow)
            val field = checkNotNull(NodeFinder.find(root, "실행할 JSON", editableOnly = true))
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "invalid-device-json")
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            onView(withHint(R.string.action_plan_hint)).check(matches(withText("invalid-device-json")))
            onView(withText(R.string.action_plan_execute)).perform(scrollTo())
            val updatedRoot = checkNotNull(automation.rootInActiveWindow)
            val button = checkNotNull(NodeFinder.find(updatedRoot, "JSON 실행"))
            assertTrue(checkNotNull(NodeFinder.clickableAncestor(button)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val expected = if (AemxAccessibilityService.isConnected()) {
                "Action Plan JSON 형식이 올바르지 않습니다."
            } else "AEMX 접근성 서비스를 먼저 활성화해 주세요."
            onView(withText(expected)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

}
