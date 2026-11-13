package com.sihoo.aemx.pipeline.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.sihoo.aemx.pipeline.PipelineRedaction
import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
    private val context: Context,
    engineFactory: (Context) -> InferenceEngine = { AiChat.getInferenceEngine(it.applicationContext) }
) : PlanGenerator {

    private val engine: InferenceEngine by lazy { engineFactory(context) }

    /** 프롬프트와 응답에는 사용자 문장과 화면 글자가 들어 있어 디버그 빌드에서만 원문을 남긴다(#16). */
    private val debuggable = PipelineRedaction.isDebuggable(context)

    /** 테스트에서 모델 준비(다운로드, 로딩)를 건너뛰고 생성 경로만 확인하려고 열어 둔다. */
    internal var prepared = false

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

        // 엔진은 프로세스 하나에 하나뿐이다. 최근 앱 목록에서 앱을 지워도 접근성 서비스 때문에
        // 프로세스가 살아 있어서, 새 Activity 가 엔진이 이미 모델을 올린 상태로 prepare 를 부른다.
        // 그때 loadModel 을 또 부르면 "Cannot load model in ModelReady!" 로 실패한다.
        // 상태를 읽은 뒤 동작하기까지 엔진이 바뀔 수 있어서(새 프로세스에서는 초기화가 비동기로 끝난다)
        // 상태 예외가 나면 상태를 다시 읽어 한 번만 맞는 경로로 다시 해 본다.
        var attempt = 0
        while (true) {
            try {
                prepareOnce(modelFile, onProgress)
                break
            } catch (e: IllegalStateException) {
                if (++attempt >= MAX_PREPARE_ATTEMPTS) throw e
                Log.w(TAG, "엔진 상태가 바뀌어 상태를 다시 읽고 한 번 더 한다: ${e.message}")
            }
        }

        prepared = true
        onProgress(100, "준비 완료")
    }

    private suspend fun prepareOnce(modelFile: File, onProgress: (Int, String) -> Unit) {
        val state = settleState()
        when (val step = nextStep(state, enginePrimed)) {
            EngineStep.REUSE -> {
                // 모델과 시스템 프롬프트가 이미 들어 있다. 프롬프트는 APK 에 들어 있어 앱을 새로
                // 설치하면 프로세스가 죽으므로, 살아 있는 엔진의 프롬프트가 낡았을 수는 없다.
                // 지난 명령의 대화 기록만 비운다.
                Log.i(TAG, "엔진에 모델이 이미 올라 있어 다시 올리지 않는다")
                engine.resetConversation()
            }
            EngineStep.WAIT -> throw IllegalStateException("엔진이 아직 초기화되지 않았다")
            EngineStep.LOAD, EngineStep.UNLOAD_THEN_LOAD -> {
                if (step == EngineStep.UNLOAD_THEN_LOAD) {
                    // 올라가 있지만 시스템 프롬프트까지 끝났는지 모르거나(준비 도중 끊김) 오류 상태다.
                    // 내리기 직전에 다시 읽어, 그사이 Initialized 가 됐으면 내리지 않는다.
                    if (engine.state.value !is InferenceEngine.State.Initialized) {
                        Log.i(TAG, "엔진 상태 ${state.javaClass.simpleName}: 내리고 다시 올린다")
                        withContext(Dispatchers.IO) { engine.cleanUp() }
                    }
                }
                enginePrimed = false
                onProgress(100, "모델 로딩 중...")
                engine.loadModel(modelFile.path)

                onProgress(100, "시스템 프롬프트 처리 중...")
                engine.setSystemPrompt(AemxPrompts.systemPrompt(context))
                enginePrimed = true
            }
        }
    }

    /** 이전 생성이나 로딩이 아직 끝나지 않았다면 끝나기를 기다린다(최대 [SETTLE_TIMEOUT_MS]). */
    private suspend fun settleState(): InferenceEngine.State {
        if (!engine.state.value.isBusy()) return engine.state.value
        return withTimeoutOrNull(SETTLE_TIMEOUT_MS) { engine.state.first { !it.isBusy() } }
            ?: throw IllegalStateException("엔진이 다른 작업 중이라 모델을 준비할 수 없다: ${engine.state.value.javaClass.simpleName}")
    }

    override suspend fun generate(request: PlanRequest): PlanResult {
        if (!isReady()) return PlanResult.EngineError("모델이 아직 준비되지 않았다")

        // 명령마다 대화 기록이 쌓이면 프롬프트 처리가 느려지고 이전 명령의 영향을 받는다(#15).
        // 시스템 프롬프트는 남기고 그 뒤만 비우므로 시스템 프롬프트를 다시 처리하지 않는다.
        if (shouldResetConversation(request.mode)) {
            try {
                engine.resetConversation()
            } catch (e: Exception) {
                return PlanResult.EngineError(e.message ?: e::class.java.simpleName)
            }
        }

        val prompt = buildPrompt(request)
        Log.i(TAG, "프롬프트(${request.mode}): ${if (debuggable) prompt else PipelineRedaction.maskValue(prompt)}")

        val raw = try {
            engine.sendUserPrompt(prompt).toList().joinToString("")
        } catch (e: Exception) {
            return PlanResult.EngineError(e.message ?: e::class.java.simpleName)
        }

        Log.i(TAG, "응답: ${if (debuggable) raw else PipelineRedaction.maskValue(raw)}")

        return when (val parsed = ActionPlanParser.parse(raw)) {
            is ParseResult.Success -> {
                // 모드와 상관없이 건다. 재분석 요청에도 원래 문장(request.utterance)이 들어 있고,
                // 가드는 사용자가 문장 앞에서 앱을 직접 지목했을 때만 움직인다. 지목이 없는 문장의
                // 재분석은 화면을 보고 앱을 바꿀 수 있으므로 결과를 그대로 둔다.
                val plan = AppMentionGuard.apply(request.utterance, parsed.plan)
                // "OO 켜서 … 검색해줘" 의 OO 가 바로 검색을 못 하는 앱이면 다른 앱으로 검색하지 않고 끝낸다.
                AppMentionGuard.searchRefusal(request.utterance, plan)?.let { return PlanResult.Refused(it) }
                // 보낼 내용이 사용자가 말한 글에 없으면 계획을 버려 다시 받는다. 메시지는 되돌릴 수 없다.
                // 잡담이나 받는 사람이 없는 말에 모델이 지어낸 send_message 는 다시 묻지 않고 끝낸다.
                // 다시 물으면 모델이 또 지어낸다.
                // 받는 사람을 발화에서 못 뽑거나 둘이면 누구에게 보낼지 확실하지 않으니 보내지 않는다(클라우드 경로와 같은 안내).
                SendMessageGuard.recipientRefusal(request.utterance, plan)?.let { return PlanResult.Refused(it) }
                MessageChannelGuard.refusal(request.utterance, plan)?.let { return PlanResult.Refused(it) }
                // 보내 달라는 말인데 send_message 가 없으면 tap 같은 엉뚱한 계획이다. 첫 계획은 한 번 더 받고,
                // 재분석이나 형식 재요청에서도 또 그러면 더 묻지 않고 끝낸다.
                if (MessageChannelGuard.asksToSend(request.utterance) &&
                    plan.steps.none { it.action == ActionType.SEND_MESSAGE }
                ) {
                    return if (request.mode == PlanRequest.Mode.INITIAL) {
                        PlanResult.Malformed(raw, "보내기 요청인데 send_message 가 없다")
                    } else {
                        PlanResult.Refused("메시지 보내기 계획을 만들지 못해 보내지 않았어요")
                    }
                }
                // 수단이 다른 앱으로 나가면 되돌릴 수 없다. 지원하지 않는 수단(인스타 등)은 다시 묻지 않고 끝낸다.
                // Malformed 는 JSON 재요청을 타므로 재시도 없이 바로 끝나는 Refused 로 낸다.
                val channelChecked = when (val channel = MessageChannelGuard.apply(request.utterance, plan)) {
                    is MessageChannelGuard.Result.Ok -> channel.plan
                    is MessageChannelGuard.Result.Unsupported -> return PlanResult.Refused(channel.reason)
                }
                val checked = SendMessageGuard.check(request.utterance, channelChecked)
                    ?: return PlanResult.Malformed(raw, "send_message 의 value 가 사용자가 말한 내용에 없다")
                // "두 번째 영상 틀어줘" 를 모델이 play_first 두 번으로 내면 하나로 합치고 순번을 value 로 채운다.
                // 장바구니 담기는 사용자가 담으라고 분명히 말했을 때만 남긴다(말하지 않았으면 뺀다).
                val ordered = PlayOrdinalGuard.apply(request.utterance, checked)
                PlanResult.Success(CartGuard.apply(request.utterance, ordered), raw, parsed.warnings)
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
        val marker = File(modelsDir, "$MODEL_FILE_NAME.verified")
        if (modelFile.exists()) {
            // 이미 받아 둔 파일. 크기와 표시 파일만 보고, 표시가 없으면 한 번만 해시를 계산한다.
            val ok = withContext(Dispatchers.IO) {
                ModelIntegrity.verify(modelFile, MODEL_SIZE, MODEL_SHA256, marker) {
                    onProgress(100, "모델 파일 확인 중...")
                }
            }
            if (ok) return modelFile
            Log.w(TAG, "받아 둔 모델이 기대한 파일과 달라 지우고 다시 받는다")
            modelFile.delete()
            marker.delete()
        }

        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.part")
        var lastError: IOException? = null

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadTo(tmpFile, onProgress)
                check(tmpFile.renameTo(modelFile)) { "받은 모델 파일 이름을 바꾸지 못했다" }
                val ok = withContext(Dispatchers.IO) {
                    ModelIntegrity.verify(modelFile, MODEL_SIZE, MODEL_SHA256, marker) {
                        onProgress(100, "모델 파일 확인 중...")
                    }
                }
                if (!ok) {
                    // 다시 받아도 같은 파일이 올 가능성이 커서 재시도 대상(IOException)이 아니다.
                    modelFile.delete()
                    marker.delete()
                    throw IllegalStateException("받은 모델의 SHA-256 이 기대한 값과 다르다")
                }
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
        /** 모델과 시스템 프롬프트를 끝까지 올린 적이 있는지. 엔진처럼 프로세스에 하나라 정적으로 둔다. */
        @Volatile
        private var enginePrimed = false

        private const val SETTLE_TIMEOUT_MS = 60_000L

        internal enum class EngineStep { LOAD, REUSE, UNLOAD_THEN_LOAD, WAIT }

        private const val MAX_PREPARE_ATTEMPTS = 2

        /**
         * 엔진 상태로 prepare 에서 할 일을 정한다.
         * 엔진이 모델 로딩과 시스템 프롬프트 처리까지 끝낸 채 대기 중([primed])이면 다시 올리지 않는다.
         * 아직 초기화 전(Uninitialized)이면 기다려야 하고(내리기를 부르면 그사이 Initialized 가 되어 실패한다),
         * 처음 상태(Initialized)면 그냥 올리고, 그 밖에 모델이 올라가 있거나 오류면 내렸다가 올린다.
         */
        internal fun nextStep(state: InferenceEngine.State, primed: Boolean): EngineStep = when {
            state is InferenceEngine.State.Uninitialized -> EngineStep.WAIT
            state is InferenceEngine.State.ModelReady && primed -> EngineStep.REUSE
            state is InferenceEngine.State.Initialized -> EngineStep.LOAD
            else -> EngineStep.UNLOAD_THEN_LOAD
        }

        private fun InferenceEngine.State.isBusy(): Boolean =
            this is InferenceEngine.State.Uninitialized ||
                this is InferenceEngine.State.Initializing ||
                this is InferenceEngine.State.LoadingModel ||
                this is InferenceEngine.State.UnloadingModel ||
                this is InferenceEngine.State.Benchmarking ||
                this is InferenceEngine.State.ProcessingSystemPrompt ||
                this is InferenceEngine.State.ProcessingUserPrompt ||
                this is InferenceEngine.State.Generating

        /**
         * 요청 전에 대화 기록을 비울지.
         *
         * INITIAL 은 새 명령이라 비운다. REPLAN 도 비운다. 프롬프트에 원래 명령, 지금까지 한 것,
         * 문제, 현재 화면이 다 들어 있어서 이전 대화가 없어도 되고, 남기면 재분석이 이어질수록
         * 문맥이 길어져 응답이 느려지며 직전 답의 형식에 끌린다.
         * REPAIR_JSON 은 비우지 않는다. 프롬프트에 깨진 응답이 없어서 기록에 남은 직전 답을
         * 봐야 형식만 고칠 수 있다.
         */
        internal fun shouldResetConversation(mode: PlanRequest.Mode): Boolean =
            mode != PlanRequest.Mode.REPAIR_JSON

        private const val TAG = "LlamaPlanGenerator"

        private const val MAX_SCREEN_TEXTS = 20

        /** 끊겨도 이어받으므로 재시도는 이어받기 횟수다. */
        private const val DOWNLOAD_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        private const val DIRECTORY_MODELS = "models"
        private const val MODEL_FILE_NAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"

        /**
         * 모델 저장소의 커밋. `main` 은 바뀔 수 있어 이 커밋으로 고정한다(#16).
         * 값은 huggingface API(`/api/models/bartowski/Qwen_Qwen3-1.7B-GGUF`)와 resolve 응답의
         * X-Repo-Commit 에서 확인했다(2026-10-20).
         */
        private const val MODEL_COMMIT = "dcb19155b962dbb6389f4691a982043a8e651022"
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/$MODEL_COMMIT/Qwen_Qwen3-1.7B-Q4_K_M.gguf"

        /** 위 커밋의 파일 크기와 SHA-256(LFS 해시, resolve 응답의 X-Linked-ETag 와 같다). */
        private const val MODEL_SIZE = 1_282_439_584L
        private const val MODEL_SHA256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb"
    }
}
