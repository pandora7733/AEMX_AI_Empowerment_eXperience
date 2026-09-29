package com.example.llama

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * AEMX LLM 의도분석 데모.
 *
 * 자연어 명령(STT 결과를 흉내낸 텍스트 입력)을 받아서
 * 실행계획 JSON을 생성하는지 확인하는 참고용 화면.
 * 모델은 앱에 내장하지 않고 최초 실행 시 다운로드한다 (LLM/README.md 참고).
 */
class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        statusTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        userActionFab.isEnabled = false
        userInputEt.hint = "모델 준비 중..."

        userActionFab.setOnClickListener { handleUserInput() }

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            prepareModel()
        }
    }

    /**
     * 모델 파일이 없으면 다운로드하고, 로드한 뒤 시스템 프롬프트를 설정한다.
     */
    private suspend fun prepareModel() {
        try {
            val modelFile = ensureModelDownloaded()
            withContext(Dispatchers.Main) { userInputEt.hint = "모델 로딩 중..." }
            engine.loadModel(modelFile.path)
            engine.setSystemPrompt(SYSTEM_PROMPT)

            withContext(Dispatchers.Main) {
                statusTv.text = "모델 준비 완료. 명령을 입력하세요.\n예: \"네이버에서 서울 날씨 찾아줘\""
                userInputEt.hint = "명령을 입력하세요"
                userInputEt.isEnabled = true
                isModelReady = true
                userActionFab.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "모델 준비 실패", e)
            withContext(Dispatchers.Main) {
                statusTv.text = "모델 준비 실패: ${e.message}"
            }
        }
    }

    /**
     * 앱 내부 저장공간에 모델 파일이 있는지 확인하고, 없으면 다운로드한다.
     * (용량 문제로 APK에는 포함하지 않음 — LLM/README.md 참고)
     */
    private suspend fun ensureModelDownloaded(): File = withContext(Dispatchers.IO) {
        val modelsDir = File(filesDir, DIRECTORY_MODELS).apply { if (!exists()) mkdir() }
        val modelFile = File(modelsDir, MODEL_FILE_NAME)

        if (modelFile.exists() && modelFile.length() > 0) {
            Log.i(TAG, "모델 파일 이미 존재함: ${modelFile.path}")
            return@withContext modelFile
        }

        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.part")
        Log.i(TAG, "모델 다운로드 시작: $MODEL_URL")

        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connect()

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        var lastReportedPercent = -1

        connection.inputStream.use { input ->
            FileOutputStream(tmpFile).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    if (totalBytes > 0) {
                        val percent = (downloadedBytes * 100 / totalBytes).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            withContext(Dispatchers.Main) {
                                statusTv.text = "모델 다운로드 중... $percent% " +
                                    "(${downloadedBytes / 1_000_000}MB / ${totalBytes / 1_000_000}MB)"
                            }
                        }
                    }
                }
            }
        }

        tmpFile.renameTo(modelFile)
        Log.i(TAG, "모델 다운로드 완료: ${modelFile.path}")
        modelFile
    }

    /**
     * 사용자 명령을 엔진에 전달하고, 생성되는 실행계획을 메시지 목록에 표시한다.
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "명령을 입력하세요", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val MODEL_FILE_NAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"

        // LLM/system_prompt.txt 와 동일한 내용 (PC에서 검증한 최종본).
        // 프롬프트를 수정하면 반드시 두 곳(여기, system_prompt.txt) 모두 갱신할 것.
        private const val SYSTEM_PROMPT = """당신은 AEMX 앱의 의도분석 모듈입니다. 사용자의 한국어 명령을 받아 실행 가능한 단계별 작업 계획을 JSON으로만 출력하세요. 다른 설명은 절대 하지 마세요.

중요: 검색어의 의미를 해석하거나 바꾸지 말고 사용자가 말한 핵심 단어를 그대로 사용하세요. 로그인, 아이디, 비밀번호, 결제 액션은 생성하지 마세요.

출력 형식:
{"app":"앱 이름","steps":[{"action":"동작종류","target":"대상","value":"선택 값"}]}

action 종류:
- open_app: 앱 실행. target은 app과 같은 앱 이름
- tap: 현재 화면의 버튼/요소 누르기. target은 화면에 보일 정확한 이름
- input_text: target 입력 필드에 value 입력. 교보문고/알라딘 화면 검색에 사용
- search: 앱의 검색 화면을 직접 열기. 네이버/구글/유튜브만 지원하며 검색어는 target에 넣고 value는 쓰지 않음
- play_first: 유튜브 검색 결과의 첫 번째 재생 가능한 동영상 실행. target은 "첫 번째 동영상"
- next_video: 유튜브 Shorts 또는 인스타그램 릴스에서 위로 넘겨 다음 영상으로 이동. target은 "다음 동영상" 또는 "다음 릴스". 사용자가 대기 시간을 명시한 경우에만 value에 30초 이하의 정수 초(예: "10s")를 넣음
- scroll: target을 up/down/forward/backward 중 하나로 지정
- wait: target 또는 value에 30초 이하의 정수 초나 ms/s 단위 지정

지원 앱: 교보문고, 알라딘, 네이버, 구글, 유튜브, 인스타그램

예시 입력: "네이버에서 서울 날씨 찾아줘"
예시 출력: {"app":"네이버","steps":[{"action":"open_app","target":"네이버"},{"action":"search","target":"서울 날씨"}]}

예시 입력: "구글에서 오늘 주요 뉴스 검색해줘"
예시 출력: {"app":"구글","steps":[{"action":"open_app","target":"구글"},{"action":"search","target":"오늘 주요 뉴스"}]}

예시 입력: "유튜브에서 고양이 영상 검색해서 첫 번째 영상 틀어줘"
예시 출력: {"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"search","target":"고양이 영상"},{"action":"play_first","target":"첫 번째 동영상"}]}

예시 입력: "유튜브 쇼츠에서 10초 뒤 다음 영상으로 넘겨줘"
예시 출력: {"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"tap","target":"Shorts"},{"action":"next_video","target":"다음 동영상","value":"10s"}]}

예시 입력: "인스타 릴스 다음 영상 보여줘"
예시 출력: {"app":"인스타그램","steps":[{"action":"open_app","target":"인스타그램"},{"action":"tap","target":"릴스"},{"action":"next_video","target":"다음 릴스"}]}

예시 입력: "교보문고에서 토지 검색해줘"
예시 출력: {"app":"교보문고","steps":[{"action":"open_app","target":"교보문고"},{"action":"tap","target":"검색창"},{"action":"input_text","target":"검색창","value":"토지"},{"action":"tap","target":"검색"}]}"""
    }
}
