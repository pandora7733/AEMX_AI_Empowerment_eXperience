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
            is ParseResult.Success -> PlanResult.Success(parsed.plan, raw, parsed.warnings)
            is ParseResult.Failure -> PlanResult.Malformed(raw, parsed.reason)
        }
    }

    /**
     * 모드에 따라 모델에게 넘길 문장을 만든다.
     *
     * 시스템 프롬프트는 "명령 하나 -> 계획 하나" 만 가르쳐 뒀으므로, 재분석·복구 요청은
     * 여기서 상황을 덧붙여 설명한다.
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
            append("현재 화면에서 이어서 할 일을 새 계획으로 만들어 주세요. 이미 끝낸 단계는 넣지 마세요.")
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
        val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
        val modelFile = File(modelsDir, MODEL_FILE_NAME)
        if (modelFile.exists() && modelFile.length() > 0) return modelFile

        Log.i(TAG, "모델 다운로드 시작: $MODEL_URL")
        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.part")

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connect()
        }

        val totalBytes = connection.contentLengthLong
        var downloaded = 0L
        var lastPercent = -1

        connection.inputStream.use { input ->
            FileOutputStream(tmpFile).use { output ->
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

        check(tmpFile.renameTo(modelFile)) { "받은 모델 파일 이름을 바꾸지 못했다" }
        Log.i(TAG, "모델 다운로드 완료: ${modelFile.path}")
        return modelFile
    }

    companion object {
        private const val TAG = "LlamaPlanGenerator"

        private const val MAX_SCREEN_TEXTS = 20

        private const val MODEL_FILE_NAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
    }
}
