package com.k2fsa.sherpa.onnx

import android.Manifest
import android.content.Context
import android.content.res.AssetManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

/**
 * AEMX: 인식 가중치를 높일 단어 목록(핫워드).
 *
 * 한글을 그대로 적으면 된다. 모델과 같이 배포되는 `bpe.model`을 `bpeVocab`으로 지정해뒀기
 * 때문에(아래 `initModel()` 참고) sherpa-onnx가 알아서 토큰으로 변환한다. 새 앱을 지원할
 * 때는 이 목록에 앱 이름 한 줄만 추가하면 된다.
 *
 * 앱 이름·명령어처럼 **항상 똑같이 나오는 단어만** 넣는다. 책 제목은 넣지 않는다 —
 * 넣을 수 있는 책은 몇 권뿐인데 그 책들로 인식이 쏠려서 나머지 책이 더 틀리게 된다.
 * 책 제목은 모델 본래 성능으로 인식해야 하는 영역이다.
 */
private val HOTWORDS = listOf(
    "교보문고",
    "알라딘",
    "로그인",
    "로그아웃",
    "검색",
    "구매",
    "주문",
    "장바구니",
    "회원가입",
    "결제",
    "취소",
).joinToString("\n")

// To enable microphone in android emulator, use
//
// adb emu avd hostmicon

private fun assetExists(assetManager: AssetManager, path: String): Boolean {
    val dir = path.substringBeforeLast('/', "")
    val fileName = path.substringAfterLast('/')

    val files = assetManager.list(dir) ?: return false
    return files.contains(fileName)
}

private fun copyAssetToInternalStorage(path: String, context: Context): String {
    val targetRoot = context.filesDir
    val outFile = File(targetRoot, path)

    if (!assetExists(context.assets, path = path)) {
        outFile.parentFile?.mkdirs()
        Log.i(TAG, "$path does not exist, return ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    if (outFile.exists()) {
        val assetSize = context.assets.open(path).use { it.available() }
        if (outFile.length() == assetSize.toLong()) {
            Log.i(TAG, "$targetRoot/$path already exists, skip copying, return $targetRoot/$path")
            return outFile.absolutePath
        }
    }

    outFile.parentFile?.mkdirs()

    context.assets.open(path).use { input: InputStream ->
        FileOutputStream(outFile).use { output: OutputStream ->
            input.copyTo(output)
        }
    }
    Log.i(TAG, "Copied $path to $targetRoot/$path")

    return outFile.absolutePath
}

private fun copyAssetListToInternalStorage(paths: String, context: Context): String {
    if (paths.isBlank()) return paths

    return paths.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { copyAssetToInternalStorage(it, context) }
        .joinToString(",")
}

class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private lateinit var recognizer: OnlineRecognizer
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private var recordingThread: Thread? = null
    private val audioEffects = mutableListOf<AudioEffect>()

    // AEMX: MIC(일반 녹음) 대신 VOICE_RECOGNITION 사용. 기기의 음성인식 전용 오디오
    // 경로를 타서 잡음 억제/이득 조절이 음성인식에 맞게 적용됨. 원래 예제는 MIC였음.
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var idx: Int = 0
    private var lastText: String = ""

    @Volatile
    private var isRecording: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        Log.i(TAG, "Start to initialize model")
        initModel()
        Log.i(TAG, "Finished initializing model")

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            textView.text = ""
            lastText = ""
            idx = 0

            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false
            audioRecord!!.stop()
            releaseAudioEffects()
            audioRecord!!.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            Log.i(TAG, "Stopped recording")
        }
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")
        // AEMX: 스트림마다 핫워드를 넘겨줌 (파일 대신 문자열로 전달, HOTWORDS 주석 참고)
        val stream = recognizer.createStream(HOTWORDS)

        val interval = 0.1 // i.e., 100 ms
        val bufferSize = (interval * sampleRateInHz).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val isEndpoint = recognizer.isEndpoint(stream)
                var text = recognizer.getResult(stream).text

                // For streaming parformer, we need to manually add some
                // paddings so that it has enough right context to
                // recognize the last word of this segment
                if (isEndpoint && recognizer.config.modelConfig.paraformer.encoder.isNotBlank()) {
                    val tailPaddings = FloatArray((0.8 * sampleRateInHz).toInt())
                    stream.acceptWaveform(tailPaddings, sampleRate = sampleRateInHz)
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream)
                    }
                    text = recognizer.getResult(stream).text
                }

                var textToDisplay = lastText

                if (text.isNotBlank()) {
                    textToDisplay = if (lastText.isBlank()) {
                        "${idx}: $text"
                    } else {
                        "${lastText}\n${idx}: $text"
                    }
                }

                if (isEndpoint) {
                    recognizer.reset(stream)
                    if (text.isNotBlank()) {
                        lastText = "${lastText}\n${idx}: $text"
                        textToDisplay = lastText
                        idx += 1
                    }
                }

                runOnUiThread {
                    textView.text = textToDisplay
                }
            }
        }
        stream.release()
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )

        enableAudioEffects(audioRecord!!.audioSessionId)
        return true
    }

    /**
     * AEMX: 기기가 제공하는 오디오 전처리를 켠다. 지원 여부는 기기마다 달라서 있는 것만 켠다.
     *
     * - 잡음 억제: 주변 소음이 있는 환경에서의 인식률
     * - 자동 이득 조절: 사용자가 작게 말하거나 마이크에서 멀 때의 입력 크기
     * - 에코 제거: TTS가 말하는 소리가 마이크로 다시 들어오는 것 (STT와 TTS를 같이 쓰는
     *   구조라 필요함 - TTS/README.md의 "녹음·재생 순서" 항목 참고)
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
        Log.i(TAG, "Enabled ${audioEffects.size} audio effects")
    }

    private fun releaseAudioEffects() {
        audioEffects.forEach { it.release() }
        audioEffects.clear()
    }

    private fun initModel() {
        // Please change getModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models
        val type = 14
        var ruleFsts : String?
        ruleFsts = null

        val useHr = false
        val hr =  HomophoneReplacerConfig(
            // Used only when useHr is true
            // Please download the following 3 files from
            // https://github.com/k2-fsa/sherpa-onnx/releases/tag/hr-files
            //
            // dict and lexicon.txt can be shared by different apps
            //
            // replace.fst is specific for an app
            lexicon = "lexicon.txt",
            ruleFsts = "replace.fst",
        )

        Log.i(TAG, "Select model type $type")
        var config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getModelConfig(type = type)!!,
            // lmConfig = getOnlineLMConfig(type = type),
            // AEMX: 말이 끝났다고 판단하는 무음 길이를 기본값(1.4초)에서 2.0초로 늘림.
            // 고령층/장애인 사용자가 천천히 말하거나 중간에 쉬면 한 문장이 두 개로
            // 잘려서 "교보문고에서" / "로그인하고 싶어" 로 따로 인식되는 문제가 있음.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 2.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            // AEMX: 핫워드(HOTWORDS)를 쓰려면 modified_beam_search가 필수라 greedy_search에서 변경.
            // 후보를 여러 개 유지해서 디코딩이 조금 느려지지만, 명령어 인식률이 더 중요함.
            decodingMethod = "modified_beam_search",
            // AEMX: 유지할 후보 경로 수. 기본 4에서 늘려 정확도를 조금 더 확보함.
            // 더 키우면 느려지기만 하고 정확도 이득은 거의 없어짐.
            maxActivePaths = 8,
            // AEMX: 핫워드 가중치. 기본 1.5는 체감이 약해서 올림. 너무 키우면 비슷한 발음을
            // 전부 핫워드로 끌어당겨서 오히려 틀리므로, 인식 결과 보고 조절할 것.
            hotwordsScore = 2.0f,
        )

        // AEMX: 원래 예제는 1스레드 고정. 요즘 폰은 코어가 남아돌고, 디코딩이 실시간을
        // 못 따라가면 인식이 밀리므로 늘림.
        config.modelConfig.numThreads = 4

        // AEMX: 핫워드를 한글 그대로 쓰기 위한 설정. 이게 없으면 HOTWORDS를 tokens.txt의
        // 토큰으로 직접 쪼개서 적어야 해서, 앱을 추가할 때마다 변환 작업이 필요해진다.
        // bpe.model은 모델과 같이 배포되며 setup_assets.sh가 assets에 복사해둔다.
        config.modelConfig.modelingUnit = "bpe"
        config.modelConfig.bpeVocab =
            "sherpa-onnx-streaming-zipformer-korean-2024-06-16/bpe.model"

        if (ruleFsts != null) {
            config.ruleFsts = ruleFsts
        }

        if (useHr) {
            config.hr = hr
        }

        var assetManager: AssetManager? = application.assets
        if (config.modelConfig.provider == "qnn") {
            Log.i(TAG, "nativelibdir: ${applicationInfo.nativeLibraryDir}")
            OnlineRecognizer.prependAdspLibraryPath(applicationInfo.nativeLibraryDir)

            val transducer = config.modelConfig.transducer
            val qnnConfig = transducer.qnnConfig

            if (qnnConfig.backendLib.isEmpty()) {
                throw IllegalArgumentException("You should provide libQnnHtp.so for qnn")
            }

            config.modelConfig.tokens =
                copyAssetToInternalStorage(config.modelConfig.tokens, this)

            if (transducer.encoder.isNotEmpty()) {
                transducer.encoder =
                    copyAssetToInternalStorage(transducer.encoder, this)
            }

            if (transducer.decoder.isNotEmpty()) {
                transducer.decoder =
                    copyAssetToInternalStorage(transducer.decoder, this)
            }

            if (transducer.joiner.isNotEmpty()) {
                transducer.joiner =
                    copyAssetToInternalStorage(transducer.joiner, this)
            }

            if (qnnConfig.contextBinary.isNotEmpty()) {
                qnnConfig.contextBinary =
                    copyAssetListToInternalStorage(qnnConfig.contextBinary, this)
            }

            if (config.hr.lexicon.isNotEmpty()) {
                config.hr.lexicon = copyAssetToInternalStorage(config.hr.lexicon, this)
            }

            if (config.hr.ruleFsts.isNotEmpty()) {
                config.hr.ruleFsts = copyAssetToInternalStorage(config.hr.ruleFsts, this)
            }

            assetManager = null
        }

        recognizer = OnlineRecognizer(
            assetManager = assetManager,
            config = config,
        )
    }
}
