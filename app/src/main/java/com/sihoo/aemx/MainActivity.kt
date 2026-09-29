package com.sihoo.aemx

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import com.sihoo.aemx.accessibility.AemxAccessibilityService

class MainActivity : AppCompatActivity() {
    private lateinit var ttsController: AndroidTtsController
    private lateinit var speechInput: TextInputEditText
    private lateinit var speakButton: Button
    private lateinit var statusView: TextView
    private lateinit var accessibilityStatusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.ScrollView(this).apply { addView(createContentView()) })
        ttsController = AndroidTtsController(applicationContext, ::handleTtsState)
    }

    private fun createContentView(): LinearLayout {
        val padding = dpToPx(24)
        val spacing = dpToPx(12)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@MainActivity).apply {
                setText(R.string.tts_title)
                setTextAppearance(MaterialR.style.TextAppearance_MaterialComponents_Headline5)
            }, matchWidthWrapHeight())

            addView(TextView(this@MainActivity).apply {
                setText(R.string.tts_description)
            }, matchWidthWrapHeight().apply { topMargin = spacing })

            val speechInputLayout = TextInputLayout(
                this@MainActivity,
                null,
                com.google.android.material.R.attr.textInputOutlinedStyle,
            ).apply {
                hint = getString(R.string.tts_text_label)
                helperText = getString(R.string.tts_edit_hint)
                endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            }

            speechInput = TextInputEditText(speechInputLayout.context).apply {
                id = R.id.tts_speech_input
                setText(R.string.tts_sample_text)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                minLines = 2
                maxLines = 5
                isSingleLine = false
                setHorizontallyScrolling(false)
                showSoftInputOnFocus = true
                setSelection(text?.length ?: 0)
                setOnClickListener { showKeyboard() }
            }
            speechInputLayout.addView(
                speechInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(speechInputLayout, matchWidthWrapHeight().apply { topMargin = spacing })

            speakButton = Button(this@MainActivity).apply {
                setText(R.string.tts_speak)
                isEnabled = false
                setOnClickListener { speak() }
            }
            addView(speakButton, matchWidthWrapHeight().apply { topMargin = spacing })

            statusView = TextView(this@MainActivity).apply {
                setText(R.string.tts_status_initializing)
                accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(statusView, matchWidthWrapHeight().apply { topMargin = spacing })

            addView(Button(this@MainActivity).apply {
                setText(R.string.accessibility_settings)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }, matchWidthWrapHeight().apply { topMargin = spacing * 2 })

            accessibilityStatusView = TextView(this@MainActivity).apply {
                accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(accessibilityStatusView, matchWidthWrapHeight().apply { topMargin = spacing })
            addView(Button(this@MainActivity).apply {
                setText(R.string.action_plan_title)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, com.sihoo.aemx.accessibility.ActionPlanActivity::class.java))
                }
            }, matchWidthWrapHeight().apply { topMargin = spacing })
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityStatusView.isInitialized) {
            accessibilityStatusView.setText(
                if (isAccessibilityServiceEnabled()) R.string.accessibility_status_enabled
                else R.string.accessibility_status_disabled,
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (AemxAccessibilityService.isConnected()) return true
        val expected = ComponentName(this, AemxAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun showKeyboard() {
        speechInput.requestFocus()
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.showSoftInput(speechInput, 0)
    }

    private fun speak() {
        ttsController.speak(speechInput.text.toString())
    }

    private fun handleTtsState(state: AndroidTtsController.State) {
        val messageResId = when (state) {
            AndroidTtsController.State.INITIALIZING -> R.string.tts_status_initializing
            AndroidTtsController.State.READY -> R.string.tts_status_ready
            AndroidTtsController.State.SPEAKING -> R.string.tts_status_speaking
            AndroidTtsController.State.DONE -> R.string.tts_status_done
            AndroidTtsController.State.EMPTY_TEXT -> R.string.tts_status_empty
            AndroidTtsController.State.INIT_FAILED -> R.string.tts_status_init_failed
            AndroidTtsController.State.KOREAN_UNAVAILABLE -> R.string.tts_status_korean_unavailable
            AndroidTtsController.State.SPEAK_FAILED -> R.string.tts_status_speak_failed
        }
        val isEngineAvailable = state !in setOf(
            AndroidTtsController.State.INITIALIZING,
            AndroidTtsController.State.INIT_FAILED,
            AndroidTtsController.State.KOREAN_UNAVAILABLE,
        )
        runOnUiThread {
            speakButton.isEnabled = isEngineAvailable
            statusView.setText(messageResId)
        }
    }

    override fun onDestroy() {
        if (::ttsController.isInitialized) {
            ttsController.shutdown()
        }
        super.onDestroy()
    }
}
