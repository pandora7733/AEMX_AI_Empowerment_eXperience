package com.sihoo.aemx.pipeline.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.flow.toList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 온디바이스 LLM(`:llm` 모듈의 [InferenceEngine])을 파이프라인의 [PlanGenerator] 로 감싼다.
 *
 * 엔진은 토큰을 Flow 로 흘려주므로 여기서 문자열로 모은 뒤 파서에 넘긴다.
 *
 * ## 준비 비용
 *
 * 모델이 1.2GB 라 APK 에 넣지 않고 최초 1회 내려받는다. [prepare] 는 다운로드 + 로딩 +
 * 시스템 프롬프트 처리까지 하며, 실기기에서 시스템 프롬프트 처리에만 15초 안팎 걸린다.
 * 화면 쪽에서 진행 상황을 사용자에게 보여 줘야 한다.
 */
class LlamaPlanGenerator(
    private val context: Context
) : PlanGenerator {

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(context.applicationContext) }
    private var prepared = false

    override fun isReady(): Boolean = prepared && engine.state.value.isModelLoaded

    /**
     * 모델을 내려받고(없으면) 로드한 뒤 시스템 프롬프트를 넣는다.
     *
     * @param onProgress 0~100 진행률과 사람이 읽을 상태 문구. 다운로드가 필요 없으면
     *                   로딩 단계부터 보고한다.
     */
    suspend fun prepare(onProgress: (Int, String) -> Unit = { _, _ -> }) {
        if (prepared) return

        val modelFile = ensureModelDownloaded(onProgress)

        onProgress(100, "모델 로딩 중...")
        engine.loadModel(modelFile.path)

        onProgress(100, "시스템 프롬프트 처리 중...")
        engine.setSystemPrompt(AemxPrompts.systemPrompt(context))

        prepared = true
        onProgress(100, "준비 완료")
    }

    override suspend fun generate(request: PlanRequest): PlanResult {
        if (!isReady()) return PlanResult.EngineError("모델이 아직 준비되지 않았다")

        val prompt = buildPrompt(request)
        Log.i(TAG, "프롬프트(${request.mode}): $prompt")

        val raw = try {
            engine.sendUserPrompt(prompt).toList().joinToString("")
        } catch (e: Exception) {
            return PlanResult.EngineError(e.message ?: e::class.java.simpleName)
        }

        Log.i(TAG, "응답: $raw")

        return when (val parsed = ActionPlanParser.parse(raw)) {
            is ParseResult.Success -> {
                // 처음 명령에서만 바로잡는다. 재분석은 화면을 보고 앱을 바꿀 수 있어서 그대로 둔다.
                val plan = if (request.mode == PlanRequest.Mode.INITIAL) {
                    AppMentionGuard.apply(request.utterance, parsed.plan)
                } else {
                    parsed.plan
                }
                PlanResult.Success(plan, raw, parsed.warnings)
            }
            is ParseResult.Failure -> PlanResult.Malformed(raw, parsed.reason)
        }
    }

    /**
     * 모드에 따라 모델에게 넘길 문장을 만든다.
     *
     * 시스템 프롬프트는 "명령 하나 -> 계획 하나" 만 가르친다. 재분석 규칙은 **반드시
     * 여기서** 요청과 함께 붙여야 하고, 시스템 프롬프트로 옮기면 안 된다.
     *
     * 한 번 시스템 프롬프트에 재분석 설명과 예시를 넣어 봤다가, 1.7B 모델이 명령의 앱이
     * 아니라 형식으로 매칭하는 바람에 "유튜브에서 고양이 영상 틀어줘"에 재분석 예시의
     * 답을 그대로 뱉었다. 예시의 앱을 바꿔도 같은 증상이 재현됐고, 일반 명령만 있던
     * 프롬프트는 3/3 정확했다. 작은 모델에 한 프롬프트로 두 가지 답 형식을 가르치지 않는다.
     */
    private fun buildPrompt(request: PlanRequest): String = when (request.mode) {
        PlanRequest.Mode.INITIAL -> request.utterance

        PlanRequest.Mode.REPLAN -> buildString {
            appendLine("원래 명령: ${request.utterance}")
            appendLine()
            appendLine("지금까지 한 것:")
            request.history.forEach { appendLine("- ${it.toPromptLine()}") }
            appendLine()
            request.reason?.let { appendLine("문제: $it") }
            request.screen?.let { appendLine(describeScreen(it)) }
            appendLine()
            appendLine("현재 화면에서 이어서 할 일을 새 계획으로 만들어 주세요.")
            appendLine("- 이미 성공한 단계는 넣지 마세요.")
            appendLine("- 누르려던 것이 없으면 위에 보이는 것 중 같은 역할을 하는 것을 고르세요.")
            appendLine("- 목적과 관계없는 것은 누르지 마세요.")
            append("- 쓸 만한 것이 없으면 scroll 로 더 찾아보세요.")
        }

        PlanRequest.Mode.REPAIR_JSON -> buildString {
            appendLine("직전 응답이 형식에 맞지 않았습니다: ${request.parseError}")
            appendLine()
            appendLine("원래 명령: ${request.utterance}")
            append("설명 없이 JSON만 다시 출력해 주세요.")
        }
    }

    private fun describeScreen(screen: ScreenSnapshot): String = buildString {
        appendLine("현재 화면:")
        screen.packageName?.let { appendLine("- 앱: $it") }
        if (screen.isDialog) appendLine("- 팝업이 떠 있음")
        if (screen.texts.isNotEmpty()) {
            appendLine("- 보이는 글자: ${screen.texts.take(MAX_SCREEN_TEXTS).joinToString(", ")}")
        }
        if (screen.clickableLabels.isNotEmpty()) {
            append("- 누를 수 있는 것: ${screen.clickableLabels.take(MAX_SCREEN_TEXTS).joinToString(", ")}")
        }
    }

    private suspend fun ensureModelDownloaded(onProgress: (Int, String) -> Unit): File {
        val modelsDir = File(context.filesDir, DIRECTORY_MODELS).apply { if (!exists()) mkdirs() }
        val modelFile = File(modelsDir, MODEL_FILE_NAME)
        if (modelFile.exists() && modelFile.length() > 0) return modelFile

        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.part")
        var lastError: IOException? = null

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadTo(tmpFile, onProgress)
                check(tmpFile.renameTo(modelFile)) { "받은 모델 파일 이름을 바꾸지 못했다" }
                Log.i(TAG, "모델 다운로드 완료: ${modelFile.path}")
                return modelFile
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "다운로드 끊김 (${tmpFile.length()}바이트까지 받음), 재시도 ${attempt + 1}", e)
                onProgress(0, "연결이 끊겼습니다. 이어받는 중...")
            }
        }

        throw lastError ?: IOException("모델을 받지 못했다")
    }

    /**
     * 받다 만 부분이 있으면 이어서 받는다.
     *
     * 1.2GB 를 모바일 네트워크로 받다 보면 한 번쯤 끊긴다. 이어받기가 없으면 그때마다
     * 처음부터 다시 받게 되어 사실상 끝나지 않는다.
     *
     * 다 받은 뒤 크기를 반드시 확인한다. 연결이 중간에 조용히 닫히면 읽기가 정상 종료처럼
     * 보여서, 확인하지 않으면 **잘린 파일을 완성본으로 착각**하고 모델 로딩에서 실패한다.
     */
    private fun downloadTo(tmpFile: File, onProgress: (Int, String) -> Unit) {
        val already = if (tmpFile.exists()) tmpFile.length() else 0L
        Log.i(TAG, "모델 다운로드 시작 (${already}바이트부터): $MODEL_URL")

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
            connect()
        }

        // 206 이면 이어받기가 받아들여진 것이고, 아니면 서버가 처음부터 다시 주는 것이다.
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val totalBytes = connection.contentLengthLong.let { if (resumed) it + already else it }
        var downloaded = if (resumed) already else 0L
        var lastPercent = -1

        connection.inputStream.use { input ->
            FileOutputStream(tmpFile, resumed).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    if (totalBytes > 0) {
                        val percent = (downloaded * 100 / totalBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(
                                percent,
                                "모델 다운로드 중... $percent% " +
                                    "(${downloaded / 1_000_000}MB / ${totalBytes / 1_000_000}MB)"
                            )
                        }
                    }
                }
            }
        }

        if (totalBytes > 0 && tmpFile.length() != totalBytes) {
            throw IOException("받은 크기가 맞지 않는다: ${tmpFile.length()} / $totalBytes")
        }
    }

    companion object {
        private const val TAG = "LlamaPlanGenerator"

        private const val MAX_SCREEN_TEXTS = 20

        /** 끊겨도 이어받으므로 재시도는 이어받기 횟수다. */
        private const val DOWNLOAD_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        private const val DIRECTORY_MODELS = "models"
        private const val MODEL_FILE_NAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
    }
}
