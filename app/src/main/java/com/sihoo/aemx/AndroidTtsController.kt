package com.sihoo.aemx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** Android TTS lifecycle and playback wrapper shared by the UI and future LLM flow. */
internal class AndroidTtsController(
    context: Context,
    private val stateListener: (State) -> Unit,
) : TextToSpeech.OnInitListener {

    /**
     * 상태를 알린다. 부르는 쪽 대부분이 상태를 보지 않아서, 엔진이 없거나 한국어가 안 되는 실패가 겉으로 드러나지 않았다.
     * 그래서 바뀔 때마다 로그에 남기고 [TtsStatus] 에 적어 둔다. 말하는 내용은 남기지 않는다.
     */
    private fun onStateChanged(state: State) {
        if (TtsStatus.record(state)) Log.i(TAG, "TTS 상태: ${TtsStatus.label(state, TtsStatus.engine)}")
        stateListener(state)
    }
    private var textToSpeech: TextToSpeech? = null
    private var isReady = false

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusTracker = SpeechFocusTracker()
    private val pendingSpeech = PendingSpeech()
    private var focusRequest: AudioFocusRequest? = null

    init {
        onStateChanged(State.INITIALIZING)
        textToSpeech = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = textToSpeech
        if (status != TextToSpeech.SUCCESS || engine == null) {
            // 기기에 쓸 수 있는 TTS 엔진이 없으면 여기로 온다(엔진이 설치되지 않았거나 사용 중지됨).
            Log.w(TAG, "TTS 엔진을 켜지 못했다(status=$status). 기기에 TTS 엔진이 설치돼 있고 켜져 있는지 확인한다")
            pendingSpeech.clear()
            onStateChanged(State.INIT_FAILED)
            return
        }
        TtsStatus.engine = runCatching { engine.defaultEngine }.getOrNull()

        val language = engine.setLanguage(Locale.KOREAN)
        when (language) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED,
            -> {
                Log.w(TAG, "TTS 엔진(${TtsStatus.engine})이 한국어를 말하지 못한다(setLanguage=$language). 한국어 음성 데이터를 받아야 한다")
                pendingSpeech.clear()
                onStateChanged(State.KOREAN_UNAVAILABLE)
                return
            }
        }

        // 기본 설정은 미디어 스트림이라 영상(릴스 등) 소리와 겹쳐 묻힌다. 접근성 안내 용도로
        // 지정하면 TalkBack 같은 접근성 서비스의 음성과 같은 취급을 받아, 포커스를 잡았을 때
        // 다른 앱 소리가 줄어든다. 도우미 앱의 안내는 화면 보조 음성이라 이쪽이 맞다.
        // (USAGE_ASSISTANT 는 대화형 비서용이라 고르지 않았다.)
        engine.setAudioAttributes(SPEECH_ATTRIBUTES)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                TtsSilence.begin()
                onStateChanged(State.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                TtsSilence.end()
                releaseFocus(utteranceId)
                onStateChanged(State.DONE)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                TtsSilence.end()
                releaseFocus(utteranceId)
            }

            @Deprecated("Deprecated by the Android framework")
            override fun onError(utteranceId: String?) {
                TtsSilence.end()
                releaseFocus(utteranceId)
                onStateChanged(State.SPEAK_FAILED)
            }
        })

        isReady = true
        onStateChanged(State.READY)
        // 엔진이 준비되기 전에 들어온 말이 있으면 지금 한다. 앱을 새로 설치한 뒤 첫 명령은
        // 끝 안내가 엔진 초기화보다 먼저 와서 버려졌다.
        pendingSpeech.take()?.let { speak(it) }
    }

    fun speak(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) {
            onStateChanged(State.EMPTY_TEXT)
            return
        }

        val engine = textToSpeech
        if (!isReady || engine == null) {
            // 초기화 중이면 마지막 한 문장만 들고 있다가 준비되면 말한다(onInit).
            if (engine != null) pendingSpeech.hold(text)
            onStateChanged(State.INITIALIZING)
            return
        }

        // onStart 는 엔진이 소리를 내기 직전에야 온다. 그 사이에 들어오는 소리까지 막으려고
        // 말하라고 시킨 시점에 먼저 표시한다.
        TtsSilence.begin()
        val utteranceId = acquireFocus()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            TtsSilence.end()
            releaseFocus(utteranceId)
            onStateChanged(State.SPEAK_FAILED)
        }
    }

    /**
     * 말하기 전에 오디오 포커스를 잡아 다른 앱 소리를 줄인다. 이미 잡고 있으면 다시 요청하지 않는다.
     *
     * QUEUE_FLUSH 로 앞 문장을 끊고 새로 말하므로 앞 문장의 onStop 이 늦게 오거나 안 와도 포커스가
     * 꼬이지 않게, 새 발화를 시작할 때 이전 발화 기록을 비운다([SpeechFocusTracker.begin]).
     */
    private fun acquireFocus(): String {
        val utteranceId = "$UTTERANCE_ID_PREFIX${nextUtterance++}"
        if (focusTracker.begin(utteranceId)) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(SPEECH_ATTRIBUTES)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        }
        return utteranceId
    }

    /** 말이 끝났거나 끊기거나 실패했을 때 부른다. 남은 발화가 없을 때만 포커스를 돌려준다. */
    private fun releaseFocus(utteranceId: String?) {
        if (utteranceId == null || !focusTracker.finish(utteranceId)) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun shutdown() {
        TtsSilence.end()
        focusTracker.clear()
        pendingSpeech.clear()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
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

    private var nextUtterance = 0

    private companion object {
        const val TAG = "AemxTts"
        const val UTTERANCE_ID_PREFIX = "aemx-korean-tts-"

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/**
 * 음성 안내(TTS)가 지금 쓸 수 있는 상태인지. 디버깅 패널이 읽는다.
 *
 * 컨트롤러가 여러 개(접근성 서비스, 파이프라인) 있어도 같은 기기 엔진을 쓰므로 마지막으로 알려진 것 하나만 둔다.
 * 말하는 중과 끝남은 자주 바뀌고 준비된 것과 같은 뜻이라 준비됨으로 묶는다.
 */
internal object TtsStatus {

    @Volatile
    var state: AndroidTtsController.State? = null
        private set

    /** 연결된 엔진의 패키지 이름. 연결되기 전이나 실패했으면 null. */
    @Volatile
    var engine: String? = null

    /** 상태를 적는다. 화면에 보이는 뜻이 바뀌었으면 true(로그를 한 번만 남기려는 것이다). */
    fun record(next: AndroidTtsController.State): Boolean {
        // 빈 문장을 말하라고 한 것은 엔진 상태가 아니다.
        if (next == AndroidTtsController.State.EMPTY_TEXT) return false
        val changed = label(next, engine) != label(state, engine)
        state = next
        return changed
    }

    fun label(state: AndroidTtsController.State?, engine: String?): String = when (state) {
        null -> "아직 쓰지 않음"
        AndroidTtsController.State.INITIALIZING -> "엔진 연결 중"
        AndroidTtsController.State.READY,
        AndroidTtsController.State.SPEAKING,
        AndroidTtsController.State.DONE,
        AndroidTtsController.State.EMPTY_TEXT,
        -> "준비됨" + (engine?.let { " ($it)" } ?: "")
        AndroidTtsController.State.INIT_FAILED -> "엔진 없음 (기기에 TTS 엔진을 설치하거나 켜야 함)"
        AndroidTtsController.State.KOREAN_UNAVAILABLE ->
            "한국어 음성 없음" + (engine?.let { " ($it)" } ?: "") + " (한국어 음성 데이터를 받아야 함)"
        AndroidTtsController.State.SPEAK_FAILED -> "말하기 실패" + (engine?.let { " ($it)" } ?: "")
    }
}

/**
 * 말하는 중인 발화를 세어 오디오 포커스를 언제 요청하고 돌려줄지 정한다.
 *
 * 안드로이드에 의존하지 않아 단위 테스트가 된다. 콜백은 TTS 엔진 스레드에서 오므로 동기화한다.
 */
internal class SpeechFocusTracker {
    private val active = mutableSetOf<String>()

    /**
     * 새 발화를 시작한다. 포커스를 새로 요청해야 하면 true.
     *
     * 이미 말하는 발화가 있으면 QUEUE_FLUSH 로 끊기는 것이므로 기록을 비우고 새 것만 남긴다.
     * 이때 포커스는 이미 잡혀 있어 다시 요청하지 않는다.
     */
    @Synchronized
    fun begin(id: String): Boolean {
        val wasIdle = active.isEmpty()
        active.clear()
        active += id
        return wasIdle
    }

    /**
     * 발화가 끝났다(완료, 중단, 오류). 포커스를 돌려줘야 하면 true.
     * 이미 비워진 앞 발화의 늦은 콜백은 무시한다.
     */
    @Synchronized
    fun finish(id: String): Boolean = active.remove(id) && active.isEmpty()

    @Synchronized
    fun clear() = active.clear()
}

/**
 * TTS 엔진이 준비되기 전에 말하라고 한 문장을 보관한다.
 *
 * 문장이 여러 번 들어와도 마지막 하나만 남긴다. 말하기는 QUEUE_FLUSH 라 앞 문장을 끊고 새로
 * 말하므로, 준비가 늦었다고 쌓아서 한꺼번에 말하면 오히려 지난 안내를 늘어놓게 된다.
 * 안드로이드에 의존하지 않아 단위 테스트가 된다.
 */
internal class PendingSpeech {
    private var text: String? = null

    @Synchronized
    fun hold(value: String) {
        text = value
    }

    /** 보관한 문장을 꺼내고 비운다. 없으면 null. */
    @Synchronized
    fun take(): String? = text.also { text = null }

    @Synchronized
    fun clear() {
        text = null
    }
}
