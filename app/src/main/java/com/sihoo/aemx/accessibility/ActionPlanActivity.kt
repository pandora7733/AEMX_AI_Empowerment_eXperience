package com.sihoo.aemx.accessibility

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sihoo.aemx.R

/** Manual integration entry point until STT/LLM are joined to the main app. */
class ActionPlanActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = AemxAccessibilityService.status
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.action_plan_description)
        })
        val input = EditText(this).apply {
            setHint(R.string.action_plan_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            isSaveEnabled = false
            setText(resources.openRawResource(R.raw.sample_action_plan).bufferedReader().use { it.readText() })
        }
        content.addView(input)
        val submission = TextView(this)
        content.addView(Button(this).apply {
            setText(R.string.action_plan_execute)
            setOnClickListener {
                val result = AemxAccessibilityService.submitActionPlan(input.text.toString())
                submission.text = result.fold(
                    onSuccess = { getString(R.string.action_plan_submitted) },
                    onFailure = { it.message ?: getString(R.string.action_plan_failed) },
                )
            }
        })
        content.addView(Button(this).apply {
            setText(R.string.action_plan_cancel)
            setOnClickListener { AemxAccessibilityService.cancelActionPlan() }
        })
        content.addView(submission)
        status = TextView(this).apply { accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
