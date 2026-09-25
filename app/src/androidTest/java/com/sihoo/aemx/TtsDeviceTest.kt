package com.sihoo.aemx

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsDeviceTest {
    @Test fun koreanSpeechCompletesAndEmptyInputIsRejected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitStatus(scenario, R.string.tts_status_ready)
            onView(withId(R.id.tts_speech_input)).perform(replaceText(""), closeSoftKeyboard())
            onView(withText(R.string.tts_speak)).perform(scrollTo(), click())
            awaitStatus(scenario, R.string.tts_status_empty)
            onView(withId(R.id.tts_speech_input)).perform(replaceText("안녕하세요. 기능 검수 중입니다."), closeSoftKeyboard())
            onView(withText(R.string.tts_speak)).perform(scrollTo(), click())
            awaitStatus(scenario, R.string.tts_status_done)
        }
    }

    private fun awaitStatus(scenario: ActivityScenario<MainActivity>, resource: Int) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = contains(it.window.decorView, it.getString(resource)) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("TTS did not reach expected state resource=$resource")
    }

    private fun contains(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it), text) }
    }
}
