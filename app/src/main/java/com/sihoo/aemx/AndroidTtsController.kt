package com.sihoo.aemx

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Android TTS lifecycle and playback wrapper shared by the UI and future LLM flow. */
internal class AndroidTtsController(
    context: Context,
    private val onStateChanged: (State) -> Unit,
) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private var isReady = false

    init {
        onStateChanged(State.INITIALIZING)
        textToSpeech = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = textToSpeech
        if (status != TextToSpeech.SUCCESS || engine == null) {
            onStateChanged(State.INIT_FAILED)
            return
        }

        when (engine.setLanguage(Locale.KOREAN)) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED,
            -> {
                onStateChanged(State.KOREAN_UNAVAILABLE)
                return
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onStateChanged(State.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                onStateChanged(State.DONE)
            }

            @Deprecated("Deprecated by the Android framework")
            override fun onError(utteranceId: String?) {
                onStateChanged(State.SPEAK_FAILED)
            }
        })

        isReady = true
        onStateChanged(State.READY)
    }

    fun speak(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) {
            onStateChanged(State.EMPTY_TEXT)
            return
        }

        val engine = textToSpeech
        if (!isReady || engine == null) {
            onStateChanged(State.INITIALIZING)
            return
        }

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            onStateChanged(State.SPEAK_FAILED)
        }
    }

    fun shutdown() {
        isReady = false
        textToSpeech?.run {
            stop()
            shutdown()
        }
        textToSpeech = null
    }

    enum class State {
        INITIALIZING,
        READY,
        SPEAKING,
        DONE,
        EMPTY_TEXT,
        INIT_FAILED,
        KOREAN_UNAVAILABLE,
        SPEAK_FAILED,
    }

    private companion object {
        const val UTTERANCE_ID = "aemx-korean-tts"
    }
}
