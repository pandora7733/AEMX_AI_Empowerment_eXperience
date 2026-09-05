package com.sihoo.aemx

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private lateinit var speechInput: EditText
    private lateinit var speakButton: Button
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        textToSpeech = TextToSpeech(this, this)
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

            speechInput = EditText(this@MainActivity).apply {
                setHint(R.string.tts_text_label)
                setText(R.string.tts_sample_text)
                setSelectAllOnFocus(true)
            }
            addView(speechInput, matchWidthWrapHeight().apply { topMargin = spacing })

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
        }
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onInit(status: Int) {
        val engine = textToSpeech
        if (status != TextToSpeech.SUCCESS || engine == null) {
            showUnavailable(R.string.tts_status_init_failed)
            return
        }

        when (engine.setLanguage(Locale.KOREAN)) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED,
            -> {
                showUnavailable(R.string.tts_status_korean_unavailable)
                return
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                updateStatus(R.string.tts_status_speaking)
            }

            override fun onDone(utteranceId: String?) {
                updateStatus(R.string.tts_status_done)
            }

            @Deprecated("Deprecated by the Android framework")
            override fun onError(utteranceId: String?) {
                updateStatus(R.string.tts_status_speak_failed)
            }
        })

        speakButton.isEnabled = true
        statusView.setText(R.string.tts_status_ready)
    }

    private fun speak() {
        val text = speechInput.text.toString().trim()
        if (text.isEmpty()) {
            statusView.setText(R.string.tts_status_empty)
            return
        }

        val result = textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UTTERANCE_ID,
        )
        if (result == TextToSpeech.ERROR) {
            statusView.setText(R.string.tts_status_speak_failed)
        }
    }

    private fun showUnavailable(@StringRes messageResId: Int) {
        speakButton.isEnabled = false
        statusView.setText(messageResId)
    }

    private fun updateStatus(@StringRes messageResId: Int) {
        runOnUiThread { statusView.setText(messageResId) }
    }

    override fun onDestroy() {
        textToSpeech?.run {
            stop()
            shutdown()
        }
        textToSpeech = null
        super.onDestroy()
    }

    private companion object {
        const val UTTERANCE_ID = "aemx-korean-tts"
    }
}
