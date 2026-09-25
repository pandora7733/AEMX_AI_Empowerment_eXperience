package com.sihoo.aemx.accessibility

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialSettingsDeviceTest {
    @Test fun secretsAreNotRestoredAndScreenCaptureIsBlocked() {
        ActivityScenario.launch(CredentialSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            onView(withHint("아이디")).perform(replaceText("synthetic-only"), closeSoftKeyboard())
            onView(withHint("비밀번호")).perform(replaceText("synthetic-secret"), closeSoftKeyboard())
            scenario.recreate()
            onView(withHint("아이디")).check(matches(withText("")))
            onView(withHint("비밀번호")).check(matches(withText("")))
            onView(withText("로그인 정보 저장")).perform(scrollTo(), click())
            onView(withText("아이디와 비밀번호를 모두 입력해 주세요.")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
}
