package com.sihoo.aemx.pipeline.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.sihoo.aemx.TtsSilence
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * sherpa-onnx 를 [SpeechSource] 뒤에 감춘다.
 *
 * 인식 설정값은 `STT/SherpaOnnx` 검증 앱에서 귀로 듣고 맞춘 것을 그대로 가져왔다.
 * 그쪽을 고치면 여기도 같이 고쳐야 한다. 값 하나하나의 이유는 아래에 적어 두었다.
 */
class SherpaSpeechSource(
    private val context: Context,
    private val scope: CoroutineScope,
) : SpeechSource {

    private val _results = MutableSharedFlow<SpeechResult>(extraBufferCapacity = 32)
    override val results: Flow<SpeechResult> = _results.asSharedFlow()

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private val audioEffects = mutableListOf<AudioEffect>()
    private var listenJob: Job? = null
    private val vad = EnergyVad(threshold = VAD_THRESHOLD)

    /** 켜면 말소리가 있는 구간만 인식기에 넣는다. 조용할 때 CPU 를 쓰지 않는다. */
    @Volatile
    var useVad: Boolean = false

    @Volatile
    private var listening = false

    override fun isReady(): Boolean = recognizer != null && hasMicPermission()

    /**
     * 모델을 올린다. 120MB 를 읽어 들이므로 처음 한 번은 몇 초가 걸린다.
     * 화면이 멈추지 않도록 부르는 쪽에서 백그라운드로 부른다.
     */
    fun prepare() {
        if (recognizer != null) return
        recognizer = OnlineRecognizer(assetManager = context.assets, config = buildConfig())
        Log.i(TAG, "인식기 준비 완료")
    }

    override fun start() {
        if (listening) return
        if (!hasMicPermission()) {
            emit(SpeechResult.Error("마이크 권한이 없습니다."))
            return
        }
        val engine = recognizer ?: run {
            emit(SpeechResult.Error("인식기가 아직 준비되지 않았습니다."))
            return
        }
        if (!openMicrophone()) {
            emit(SpeechResult.Error("마이크를 열지 못했습니다."))
            return
        }

        listening = true
        audioRecord?.startRecording()
        listenJob = scope.launch(Dispatchers.IO) { listen(engine) }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        listenJob?.cancel()
        listenJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
    }

    /** 모델을 내린다. 화면이 사라질 때 부른다. */
    fun release() {
        stop()
        recognizer = null
    }

    private fun listen(engine: OnlineRecognizer) {
        // 핫워드는 스트림을 만들 때 넘긴다. 파일이 아니라 문자열로 준다.
        val stream = engine.createStream(HOTWORDS)
        val buffer = ShortArray((READ_INTERVAL_SECONDS * SAMPLE_RATE).toInt())
        // 말소리가 시작된 프레임은 이미 소리가 올라온 뒤라 첫 음절이 잘린다. 직전 프레임들을
        // 들고 있다가 문이 열릴 때 같이 넣는다.
        val preRoll = ArrayDeque<FloatArray>()
        var wasOpen = false
        vad.reset()
        var frames = 0
        var peakRms = 0.0

        try {
            while (listening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                // 앱이 말하는 소리가 마이크로 되돌아 들어온 것이다. 인식기에 넣지 않고, 말끝이
                // 이어 붙지 않게 지금까지 쌓인 것도 비운다.
                if (TtsSilence.isMuted()) {
                    engine.reset(stream)
                    vad.reset()
                    preRoll.clear()
                    wasOpen = false
                    continue
                }

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                if (useVad) {
                    val open = vad.accept(buffer, read)
                    peakRms = maxOf(peakRms, vad.lastRms)
                    // 1초마다 그 사이 가장 컸던 소리를 남긴다. 문턱값이 맞는지 보는 용도다.
                    if (++frames % 10 == 0) {
                        Log.d(TAG, "소리 크기 최대 %.4f (문턱 %.4f) 문=%s".format(peakRms, VAD_THRESHOLD, open))
                        peakRms = 0.0
                    }
                    if (!open) {
                        wasOpen = false
                        preRoll.addLast(samples)
                        if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        continue
                    }
                    if (!wasOpen) {
                        preRoll.forEach { stream.acceptWaveform(it, sampleRate = SAMPLE_RATE) }
                        preRoll.clear()
                        wasOpen = true
                    }
                }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }

                val text = engine.getResult(stream).textWithSpacing()
                if (engine.isEndpoint(stream)) {
                    engine.reset(stream)
                    // 한 발화가 끝났다. 이 값이 파이프라인을 돌리는 방아쇠다.
                    if (text.isNotBlank()) emit(SpeechResult.Final(text))
                } else if (text.isNotBlank()) {
                    emit(SpeechResult.Partial(text))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "인식 중 오류", e)
            emit(SpeechResult.Error(e.message ?: "인식에 실패했습니다."))
        } finally {
            stream.release()
        }
    }

    private fun emit(result: SpeechResult) {
        scope.launch { _results.emit(result) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openMicrophone(): Boolean {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) return false

        val record = AudioRecord(
            // VOICE_RECOGNITION 은 기기가 음성인식용 전처리를 붙여 주는 입력이다.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            // 16비트라 한 샘플이 2바이트다. 최소치의 두 배를 잡아 끊김을 줄인다.
            minBytes * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        enableAudioEffects(record.audioSessionId)
        return true
    }

    /**
     * 기기가 제공하는 오디오 전처리를 켠다. 지원 여부가 기기마다 달라 있는 것만 켠다.
     *
     * 에코 제거가 필요한 이유는 TTS 가 말하는 소리가 마이크로 다시 들어오기 때문이다.
     * 듣기와 말하기를 같이 하는 구조라, 없으면 자기 목소리를 명령으로 인식한다.
     */
    private fun enableAudioEffects(sessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            audioEffects += NoiseSuppressor.create(sessionId).apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            audioEffects += AutomaticGainControl.create(sessionId).apply { enabled = true }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            audioEffects += AcousticEchoCanceler.create(sessionId).apply { enabled = true }
        }
        Log.i(TAG, "오디오 전처리 ${audioEffects.size}개 켜짐")
    }

    private fun releaseAudioEffects() {
        audioEffects.forEach { it.release() }
        audioEffects.clear()
    }

    private fun buildConfig(): OnlineRecognizerConfig {
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = getModelConfig(type = KOREAN_STREAMING_ZIPFORMER)!!,
            // 말이 끝났다고 보는 무음 길이를 기본 1.4초에서 2.0초로 늘렸다. 천천히
            // 말하거나 중간에 쉬면 한 문장이 둘로 잘려서 "교보문고에서" / "로그인하고
            // 싶어" 로 따로 인식됐다.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 2.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            // 핫워드를 쓰려면 modified_beam_search 여야 한다. greedy_search 에서 바꿨다.
            decodingMethod = "modified_beam_search",
            // 유지할 후보 경로 수. 기본 4에서 늘렸다. 더 키우면 느려지기만 한다.
            maxActivePaths = 8,
            // 핫워드 가중치. 기본 1.5는 체감이 약했다. 너무 키우면 비슷한 발음을 전부
            // 핫워드로 끌어당겨 오히려 틀린다.
            hotwordsScore = 2.0f,
        )
        // 예제는 1스레드 고정인데, 디코딩이 실시간을 못 따라가면 인식이 밀린다.
        config.modelConfig.numThreads = 4
        // 핫워드를 글자 단위로 처리한다. 이 값이 비어 있으면 핫워드를 넘기는 순간
        // 라이브러리가 로그도 예외도 없이 프로세스를 끝낸다.
        config.modelConfig.modelingUnit = "cjkchar"
        return config
    }

    private companion object {
        const val TAG = "SherpaSpeechSource"

        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        const val VAD_THRESHOLD = 0.015

        /** 문이 열리기 전 몇 프레임을 들고 있을지. 100ms 단위라 0.3초다. */
        const val PRE_ROLL_FRAMES = 3

        /** 한 번에 읽어 들이는 길이. 100ms 마다 인식기에 넣는다. */
        const val READ_INTERVAL_SECONDS = 0.1

        /** `getModelConfig` 의 한국어 streaming zipformer(2024-06-16). */
        const val KOREAN_STREAMING_ZIPFORMER = 14

        /**
         * 인식 가중치를 높일 단어. 한글을 그대로 적으면 된다(`modelingUnit = "cjkchar"`).
         *
         * 앱 이름과 명령어처럼 항상 똑같이 나오는 단어만 넣는다. 책 제목은 넣지 않는다 —
         * 넣을 수 있는 책은 몇 권뿐인데 그 책들로 인식이 쏠려 나머지가 더 틀리게 된다.
         */
        val HOTWORDS = listOf(
            "교보문고", "알라딘", "유튜브", "네이버", "인스타그램",
            "로그인", "로그아웃", "검색", "구매", "주문",
            "장바구니", "회원가입", "결제", "취소", "재생",
            // 호출어는 학습 데이터에 없는 단어라 가중치를 따로 높게 준다(": 점수").
            "하이에이맥스 :3.5", "에이맥스 :3.0", "하이 :1.5",
        ).joinToString("\n")
    }
}

/**
 * 인식 결과를 토큰에서 직접 만든다.
 *
 * `OnlineRecognizerResult.text` 를 그대로 쓰면 띄어쓰기가 전부 사라진다. sherpa-onnx 가
 * 텍스트를 합친 뒤 `RemoveSpaceBetweenCjk()` 로 CJK 글자 사이 공백을 지우는데, 그 함수의
 * CJK 범위(0xA840~0xD7AF)에 한글 음절(U+AC00~U+D7A3)이 들어가기 때문이다. 중국어와
 * 일본어에는 맞고 한국어에는 틀리다.
 *
 * 토큰 배열에는 어절 경계가 공백으로 남아 있어서 이어붙이면 띄어쓰기가 살아난다.
 * 띄어쓰기가 없으면 뒤에 붙는 LLM 이 명령을 해석하기 어려워진다.
 */
private fun OnlineRecognizerResult.textWithSpacing(): String = tokens.joinToString("").trim()
