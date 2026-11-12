package com.sihoo.aemx.pipeline.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import com.sihoo.aemx.R
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.accessibility.RecipientFallback
import com.sihoo.aemx.AndroidTtsController
import com.sihoo.aemx.assistant.AssistantStore
import com.sihoo.aemx.assistant.SendDecision
import com.sihoo.aemx.debug.SpeechTrace
import com.sihoo.aemx.pipeline.PipelineConfig
import com.sihoo.aemx.pipeline.PipelineEvent
import com.sihoo.aemx.pipeline.PipelineLogEntry
import com.sihoo.aemx.pipeline.PipelineOrchestrator
import com.sihoo.aemx.pipeline.PipelineState
import com.sihoo.aemx.pipeline.PlanStepPruner
import com.sihoo.aemx.pipeline.device.AccessibilityAppResolver
import com.sihoo.aemx.pipeline.device.AccessibilityScreenObserver
import com.sihoo.aemx.pipeline.device.AccessibilityStepExecutor
import com.sihoo.aemx.pipeline.device.AnnouncementTexts
import com.sihoo.aemx.pipeline.device.AppNames
import com.sihoo.aemx.pipeline.device.ServicePlanBridge
import com.sihoo.aemx.pipeline.device.ServiceScreenSource
import com.sihoo.aemx.pipeline.device.announcementFor
import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.fake.ScriptedActionExecutor
import com.sihoo.aemx.pipeline.llm.CloudSettings
import com.sihoo.aemx.pipeline.llm.CommandOutcome
import com.sihoo.aemx.pipeline.llm.CommandTiming
import com.sihoo.aemx.pipeline.llm.HybridPlanGenerator
import com.sihoo.aemx.pipeline.llm.LlamaPlanGenerator
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.stt.MicStatus
import com.sihoo.aemx.pipeline.stt.SherpaSpeechSource
import com.sihoo.aemx.pipeline.stt.SpeakerVerifier
import com.sihoo.aemx.pipeline.stt.WakeWordSpeechSource
import com.sihoo.aemx.pipeline.youtube.YouTubeSearch
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchClient
import com.sihoo.aemx.pipeline.youtube.YouTubeShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.sihoo.aemx.accessibility.KAKAO_LOOKUP_TIMEOUT_MS
import com.sihoo.aemx.pipeline.RecipientLookup
import com.sihoo.aemx.pipeline.model.ActionType
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 호출어 → 음성 인식 → 계획 → 실행 → 말하기 → 오버레이 상태까지의 파이프라인 코어.
 *
 * 프로세스에 하나만 있고(object), 개발 화면과 앱 본체 서비스([VoiceService])가 같이 쓴다. 화면은 이 객체의
 * 흐름([status] 등)을 보여 주고 명령을 넘길 뿐이다. 그래서 화면을 닫아도 듣기와 실행은 계속된다.
 * 안전 로직(SendMessageGuard, 개인정보 차단, 취소 처리)은 이 코어가 부르는 쪽에 그대로 있고 여기서 바꾸지 않는다.
 */
object PipelineCore {

    private const val TAG = "PipelineCore"

    private lateinit var appContext: Context
    private lateinit var settings: PipelineSettings
    private lateinit var cloudSettings: CloudSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false

    // ---------------------------------------------------------------- 화면이 보는 상태

    private val _status = MutableStateFlow("")
    /** 상태 한 줄. 화면의 상태 문장이다. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<PipelineLogEntry>>(emptyList())
    val log: StateFlow<List<PipelineLogEntry>> = _log.asStateFlow()

    private val _planJson = MutableStateFlow("")
    /** 실기기 경로가 만든 실행 계획 JSON. 화면의 로그 칸에 보여 준다. */
    val planJson: StateFlow<String> = _planJson.asStateFlow()

    private val _route = MutableStateFlow("")
    /** 계획을 어디서 만들었는지(로컬·클라우드) 한 줄. */
    val route: StateFlow<String> = _route.asStateFlow()

    /** 최근 실행 기록 한 줄. 명령 내용은 화면에만 보이고 로그에는 글자 수만 남는다. */
    data class RecentRun(val command: String, val success: Boolean, val seconds: Double)

    private val _recent = MutableStateFlow<List<RecentRun>>(emptyList())
    /** 최근 실행 두 개(새것이 앞). 메모리에만 있어서 앱을 끄면 빈다. */
    val recent: StateFlow<List<RecentRun>> = _recent.asStateFlow()

    /** 지금 도는 명령의 글과 시작 시각. 끝나면 최근 실행에 넣는다. */
    private var runningCommand: String? = null
    private var runningStartedAt: Long = 0L

    private val _timing = MutableStateFlow("")
    /** 마지막 명령의 시간 한 줄. */
    val timing: StateFlow<String> = _timing.asStateFlow()

    private val _listening = MutableStateFlow(false)
    /** 듣고 있는지. 듣기 버튼 문구와 서비스 상태가 이것을 본다. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 마이크를 여는 중인지. 준비가 끝나기 전에 다시 여는 것을 막는다. */
    @Volatile
    private var preparingMic = false

    private val _runEnabled = MutableStateFlow(true)
    /** 실행 버튼을 누를 수 있는지. 명령이 도는 동안과 LLM 준비 중에는 false. */
    val runEnabled: StateFlow<Boolean> = _runEnabled.asStateFlow()

    private val _heard = MutableStateFlow<String?>(null)
    /** 마지막으로 들은 최종 문장. 개발 화면의 명령칸에 넣는다. */
    val heard: StateFlow<String?> = _heard.asStateFlow()

    private val _modelReady = MutableStateFlow(false)
    /** 온디바이스 LLM 이 올라와 쓸 수 있는지. 첫 실행 안내의 "모델 준비" 항목이 본다. */
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _modelMessage = MutableStateFlow("")
    /** 모델 내려받기와 준비 진행 문장. */
    val modelMessage: StateFlow<String> = _modelMessage.asStateFlow()

    private val _modelFailed = MutableStateFlow(false)
    /** 마지막 모델 내려받기가 실패했는지. 첫 실행 안내의 "다시 받기"가 본다. */
    val modelFailed: StateFlow<Boolean> = _modelFailed.asStateFlow()

    /** 지금 모델을 받고 있는지. 같은 요청이 겹치지 않게 막는다. */
    @Volatile
    private var modelPreparing = false

    /** 실기기 직접 경로가 계획을 만들고 보내는 동안 참. 그 코루틴이 끝나는 순간 거짓으로 돌아간다. */
    @Volatile
    private var directBusy = false

    private val _enrolling = MutableStateFlow(false)
    /** 목소리 등록 중인지. 등록 버튼을 막는다. */
    val enrolling: StateFlow<Boolean> = _enrolling.asStateFlow()

    // ---------------------------------------------------------------- 명령 처리

    private var orchestrator: PipelineOrchestrator? = null
    private var deviceOrchestrator: PipelineOrchestrator? = null
    private var deviceExecutor: AccessibilityStepExecutor? = null
    private var announcer: AndroidTtsController? = null
    private var collectJob: Job? = null
    private var llmPlanner: LlamaPlanGenerator? = null
    private var hybridPlanner: HybridPlanGenerator? = null

    /** 지금 도는 명령의 시각. 계획 콜백은 백그라운드 스레드에서 오므로 volatile. */
    @Volatile
    private var currentTiming: CommandTiming? = null

    /** [currentTiming] 의 끝 신호가 어느 경로에서 올지. 두 경로가 같은 타이머를 공유하지 않게 한다. */
    private enum class TimingPath { ORCHESTRATOR, DIRECT }

    @Volatile
    private var timingPath: TimingPath? = null

    /** 음성 최종 결과의 말 끝 시각과 문장. 같은 문장으로 명령을 실행할 때만 쓴다. */
    private var pendingSpeech: Pair<String, Long>? = null

    /** 듣기 한 번의 runId. 듣기 사건(ListeningStarted 등)이 같은 세션끼리 묶이게 한다. */
    private var voiceRunId: String? = null

    /** 보내기 확인을 기다리는 중의 답 상자. null 이면 확인 대기 중이 아니다. */
    @Volatile
    private var pendingSend: CompletableDeferred<String?>? = null

    /** 확인 화면에서 고를 수 있는 받는 사람 후보. 비어 있으면 고르지 않는다. 음성 이름 고르기에 쓴다. */
    @Volatile
    private var pendingChoices: List<String> = emptyList()

    /** 개발 화면과 adb 훅이 끈 보내기 확인. 프로세스가 끝나면 사라지고 설정([PipelineSettings.confirmSend])이 다시 쓰인다. */
    @Volatile
    private var sendConfirmOverride: Boolean? = null

    /** 시연 확인 화면의 실행 번호. 답을 받아도 실제로 보내지 않는다. */
    private var demoRunId: String? = null

    /** 보내기 확인이 켜져 있는지. 시험 훅 값이 있으면 그것이 우선한다. */
    val sendConfirmEnabled: Boolean
        get() = sendConfirmOverride ?: settings.confirmSend

    private val verifier by lazy { SpeakerVerifier(appContext) }
    private val enrolledVoices = ArrayList<FloatArray>()
    private var speech: SherpaSpeechSource? = null
    private var gate: WakeWordSpeechSource? = null

    /** 명령 하나를 실행한다. 실행 버튼, 음성 자동 실행, 호출 흐름이 모두 이 길을 탄다. */
    fun runCommand(command: String) {
        if (settings.orchestrator) {
            runViaOrchestrator(command)
            return
        }
        if (settings.realExecutor) {
            runOnDevice(command)
            return
        }
        // 진짜 LLM 은 준비 비용이 커서 매번 다시 만들지 않고 계속 쓴다.
        if (!settings.realLlm) resetPipeline()
        orchestrator?.submit(command)
    }

    /** 명령을 실행하는 중인지. 오케스트레이터가 바쁘거나 실제 기기 경로가 계획을 만드는 중이면 true. */
    fun isRunning(): Boolean = busyReason() != null

    /** 명령을 받지 못하는 이유. 막힌 것이 없으면 null. 자동 실행이 건너뛸 때 로그에 남긴다. */
    private fun busyReason(): String? = RunGate.busyReason(
        modelPreparing = modelPreparing,
        directRunning = directBusy,
        orchestratorBusy = orchestrator?.isBusy == true,
        planRunning = AemxAccessibilityService.isPlanRunning(),
    )

    /** 실행 버튼 표시를 지금 실제 상태로 맞춘다. 명령이 끝나는 순간에도 부른다([RunGate]). */
    private fun syncRunEnabled() {
        _runEnabled.value = !RunGate.locked(modelPreparing, directBusy, orchestrator?.isBusy == true)
    }

    /** 마이크가 꺼져 있고 여는 중도 아니다. 화면이나 접근성이 다시 켜 볼지 정할 때 쓴다. */
    fun isMicIdle(): Boolean = !_listening.value && !preparingMic

    /** 명령의 시작 시각을 잡는다. 음성이면 말 끝 판정 시각부터, 글 입력이면 지금부터 잰다. */
    private fun beginTiming(command: String, path: TimingPath) {
        val speech = pendingSpeech?.takeIf { it.first == command }
        pendingSpeech = null
        timingPath = path
        val startedAt = speech?.second ?: SystemClock.elapsedRealtime()
        runningCommand = command
        runningStartedAt = startedAt
        currentTiming = CommandTiming(
            startMs = startedAt,
            fromSpeech = speech != null,
        )
    }

    /** 같은 경로에서 온 끝 신호만 받아 한 줄을 로그와 화면에 남긴다. 계획 전의 신호는 타이머가 무시한다. */
    private fun endTiming(path: TimingPath, outcome: CommandOutcome) {
        if (timingPath != path) return
        val line = currentTiming?.finish(SystemClock.elapsedRealtime(), outcome) ?: return
        Log.i("AemxTiming", line)
        _timing.value = line
        // 최근 실행은 메모리에만 두고 화면에만 보여 준다. 앱을 끄면 사라진다.
        val command = runningCommand
        if (command != null) {
            val seconds = (SystemClock.elapsedRealtime() - runningStartedAt) / 1000.0
            _recent.value = (listOf(RecentRun(command, outcome == CommandOutcome.SUCCESS, seconds)) + _recent.value).take(RECENT_LIMIT)
        }
    }

    /** 오케스트레이터가 끝 상태에 닿으면 명령 한 줄을 남긴다. */
    private fun recordOrchestratorEnd(state: PipelineState) {
        val outcome = when (state) {
            is PipelineState.Finished -> CommandOutcome.SUCCESS
            is PipelineState.Failed -> CommandOutcome.FAILED
            is PipelineState.NeedsUser -> CommandOutcome.STOPPED
            is PipelineState.NeedsSetup -> CommandOutcome.REFUSED
            else -> return
        }
        endTiming(TimingPath.ORCHESTRATOR, outcome)
    }

    /**
     * 가짜 파트들을 새로 만들어 붙인다. 가짜 LLM 은 준비된 응답을 순서대로 쓰고 가짜 실행모듈은 대본을 따라가므로,
     * 한 번 돌고 나면 둘 다 바닥난다. 실행할 때마다 새로 조립해야 같은 시나리오를 반복해서 볼 수 있다.
     */
    fun resetPipeline() {
        collectJob?.cancel()
        val fresh = buildOrchestrator(planner = null)
        orchestrator = fresh
        collectJob = subscribe(fresh, announceEnds = false)
    }

    /** 오케스트레이터 하나의 흐름을 구독한다. 상태·로그·사건을 화면과 오버레이로 넘긴다. */
    private fun subscribe(orch: PipelineOrchestrator, announceEnds: Boolean) = scope.launch {
        launch { orch.state.collect { render(it) } }
        if (announceEnds) {
            // StateFlow 는 구독하자마자 마지막 값을 준다. 지난 명령의 끝 상태를 다시 말하지 않게 건너뛴다.
            launch { orch.state.drop(1).collect { announce(it) } }
        }
        launch { orch.log.collect { entries -> _log.value = entries } }
        launch { orch.events.collect { AssistantStore.shared.post(it) } }
    }

    /**
     * 온디바이스 LLM 을 준비해서 파이프라인에 꽂는다. 모델이 1.2GB 라 처음에는 내려받는다(Wi-Fi 권장).
     * 준비가 끝날 때까지 실행 버튼을 막고 진행 상황을 상태줄에 보여 준다.
     */
    private fun prepareRealLlm() {
        // 이미 받고 있으면 두 번 받지 않는다(다시 받기 버튼을 여러 번 눌러도 안전하다).
        if (modelPreparing) return
        modelPreparing = true
        _modelFailed.value = false
        syncRunEnabled()
        scope.launch {
            val planner = llmPlanner ?: LlamaPlanGenerator(appContext).also { llmPlanner = it }
            try {
                withContext(Dispatchers.IO) {
                    planner.prepare { _, message ->
                        _status.value = message
                        _modelMessage.value = message
                    }
                }
                collectJob?.cancel()
                val fresh = buildOrchestrator(planner = planner)
                orchestrator = fresh
                collectJob = subscribe(fresh, announceEnds = false)
                _status.value = appContext.getString(R.string.pipeline_llm_ready)
                _modelReady.value = true
            } catch (e: Exception) {
                Log.e(TAG, "LLM 준비 실패", e)
                // 내려받기 실패는 일시적일 수 있다. 설정을 끄지 않고, 화면에서 다시 받게 둔다.
                val message = appContext.getString(R.string.pipeline_llm_failed, e.message ?: "")
                _status.value = message
                _modelMessage.value = message
                _modelReady.value = false
                _modelFailed.value = true
            } finally {
                modelPreparing = false
                syncRunEnabled()
            }
        }
    }

    /** 모델 내려받기를 다시 시도한다. 이미 받는 중이거나 이미 준비됐으면 아무 일도 하지 않는다. */
    fun retryModel() {
        if (_modelReady.value) return
        prepareRealLlm()
    }

    /** 실제 LLM 을 켜거나 끈다. 끄면 가짜 파트로 돌아간다. */
    fun setRealLlm(enabled: Boolean) {
        settings.realLlm = enabled
        if (enabled) prepareRealLlm() else resetPipeline()
    }

    /**
     * 말한 명령을 LLM 에 넣고, 나온 계획을 접근성 서비스에 넘겨 실제로 기기를 조작한다. 오케스트레이터를 거치지
     * 않는다(이슈 #11 이 맞추는 중). 화면 검증과 재분석은 이 길에서는 돌지 않는다.
     */
    private fun runOnDevice(command: String) {
        val planner = llmPlanner
        if (planner == null || !planner.isReady()) {
            _status.value = appContext.getString(R.string.pipeline_device_needs_llm)
            return
        }
        if (!AemxAccessibilityService.isConnected()) {
            _status.value = appContext.getString(R.string.pipeline_device_needs_service)
            return
        }

        directBusy = true
        syncRunEnabled()
        _status.value = appContext.getString(R.string.pipeline_device_planning)
        beginTiming(command, TimingPath.DIRECT)
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                planFor(planner).generate(PlanRequest(utterance = command, mode = PlanRequest.Mode.INITIAL))
            }
            when (result) {
                is PlanResult.Success -> {
                    // 오케스트레이터 경로와 같은 후처리(시간만 드는 앱 열기 제거, 유튜브 API 로 영상 열기)를 거친 뒤 보낸다.
                    val pruned = PlanStepPruner.prune(result.plan)
                    val plan = youtubeSearchOrNull()?.let { YouTubeShortcut.rewrite(pruned, command, it) } ?: pruned
                    // 이 경로에는 보내기 확인 화면이 없다. 확인을 켜 두었으면 메시지 보내기는 실행하지 않는다.
                    if (sendConfirmEnabled && plan.steps.any { it.action == ActionType.SEND_MESSAGE }) {
                        _status.value = appContext.getString(R.string.send_needs_orchestrator)
                        endTiming(TimingPath.DIRECT, CommandOutcome.REFUSED)
                        return@launch
                    }
                    val json = plan.toWireJson()
                    Log.i(TAG, "실기기 실행 계획: $json")
                    _planJson.value = json
                    AemxAccessibilityService.submitActionPlan(json).fold(
                        onSuccess = { _status.value = appContext.getString(R.string.pipeline_device_running) },
                        onFailure = {
                            _status.value = appContext.getString(R.string.pipeline_device_failed, it.message ?: "")
                            endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                        },
                    )
                }
                is PlanResult.Malformed -> {
                    _status.value = appContext.getString(R.string.pipeline_device_failed, result.reason)
                    endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                }
                is PlanResult.EngineError -> {
                    _status.value = appContext.getString(R.string.pipeline_device_failed, result.message)
                    endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                }
                is PlanResult.Refused -> {
                    _status.value = appContext.getString(R.string.pipeline_device_failed, result.message)
                    endTiming(TimingPath.DIRECT, CommandOutcome.REFUSED)
                }
            }
        }.invokeOnCompletion {
            directBusy = false
            syncRunEnabled()
        }
    }

    /**
     * 말한 명령을 오케스트레이터를 거쳐 실제 기기에서 실행한다(이슈 #11). step 마다 화면이 기대대로 바뀌었는지
     * 검사하고, 어긋나면 현재 화면을 넣어 LLM 에 다시 묻는다. 진짜 LLM 과 접근성 서비스가 연결돼 있어야 한다.
     */
    private fun runViaOrchestrator(command: String) {
        val planner = llmPlanner
        if (planner == null || !planner.isReady()) {
            _status.value = appContext.getString(R.string.pipeline_device_needs_llm)
            return
        }
        if (!AemxAccessibilityService.isConnected()) {
            _status.value = appContext.getString(R.string.pipeline_device_needs_service)
            return
        }

        if (announcer == null) announcer = AndroidTtsController(appContext) { }
        val device = deviceOrchestrator ?: buildDeviceOrchestrator(planFor(planner)).also { deviceOrchestrator = it }
        // 다른 경로가 orchestrator 를 갈아 끼웠을 수 있으니, 이쪽이 아니면 구독을 옮긴다.
        if (orchestrator !== device) {
            collectJob?.cancel()
            orchestrator = device
            collectJob = scope.launch {
                launch {
                    device.state.collect {
                        recordOrchestratorEnd(it)
                        render(it)
                    }
                }
                // 지난 명령의 끝 상태를 다시 말하지 않게 건너뛴다.
                launch { device.state.drop(1).collect { announce(it) } }
                launch { device.events.collect { AssistantStore.shared.post(it) } }
                launch { device.log.collect { entries -> _log.value = entries } }
            }
        }
        beginTiming(command, TimingPath.ORCHESTRATOR)
        device.submit(command)
    }

    /**
     * 로컬 계획 생성기를 클라우드 하이브리드로 감싼다. 설정(켜짐·키)은 명령마다 읽으므로 화면에서 바꾸면
     * 다음 명령부터 바로 적용된다. 키가 없으면 로컬과 똑같이 동작한다.
     */
    private fun planFor(local: PlanGenerator): PlanGenerator =
        hybridPlanner ?: HybridPlanGenerator(
            local = local,
            cloud = { cloudSettings.generatorOrNull() },
            log = { Log.i("CloudPlan", it) },
            onRoute = { report ->
                // 계획이 나온 시각. 끝 신호보다 먼저 일어나므로(같은 흐름에서 호출) 순서가 어긋나지 않는다.
                currentTiming?.planned(SystemClock.elapsedRealtime(), report)
                _route.value = report.summary()
            },
        ).also { hybridPlanner = it }

    /**
     * 유튜브 검색을 YouTube Data API 로 미리 푸는 통로. 설정이 꺼졌거나 키가 없으면 null(화면 검색 경로).
     * 시간은 명령의 타이머에 "유튜브 API" 로 붙인다.
     */
    private fun youtubeSearchOrNull(): YouTubeSearch? {
        if (!cloudSettings.youtubeApiEnabled || !cloudSettings.hasYoutubeKey) return null
        val client = YouTubeSearchClient(
            apiKey = cloudSettings.youtubeKey,
            log = { Log.i("YouTubeApi", it) },
        )
        return { query, maxResults ->
            val started = SystemClock.elapsedRealtime()
            val ids = client.search(query, maxResults)
            currentTiming?.youtubeApi(SystemClock.elapsedRealtime() - started, hit = ids != null)
            ids
        }
    }

    /** 명령이 끝나 최종 상태가 되거나 재분석에 들어가면 말한다. 오케스트레이터 경로에서만 불린다. */
    private fun announce(state: PipelineState) {
        val texts = AnnouncementTexts(
            finished = appContext.getString(R.string.pipeline_announce_finished),
            failed = appContext.getString(R.string.pipeline_announce_failed),
            needsUser = appContext.getString(R.string.pipeline_announce_needs_user),
            needsSetup = appContext.getString(R.string.pipeline_announce_needs_setup),
            replanning = appContext.getString(R.string.pipeline_announce_replanning),
        )
        val text = announcementFor(state, texts) ?: return
        announcer?.speak(text)
    }

    /** 진짜 LLM + 접근성 서비스 어댑터로 오케스트레이터를 조립한다. */
    private fun buildDeviceOrchestrator(planner: PlanGenerator): PipelineOrchestrator {
        val screenObserver = AccessibilityScreenObserver(ServiceScreenSource())
        // 같은 리졸버를 오케스트레이터와 실행기가 나눠 써서, 풀린 앱 이름을 서비스에 그대로 넘긴다.
        val appResolver = AccessibilityAppResolver(appContext)
        val executor = AccessibilityStepExecutor(
            bridge = ServicePlanBridge,
            foregroundPackage = { screenObserver.capture()?.packageName },
            nameForPackage = { pkg -> AppNames.forPackage(pkg) ?: appResolver.nameFor(pkg) },
            isSupportedApp = appResolver::isResolvable,
            // step 이 끝나면 화면이 원래 걸어 둔 콜백으로 되돌린다. 앱 본체는 화면과 무관하게 항상 받는다.
            restoreObserver = { deviceObserver },
        )
        deviceExecutor = executor
        return PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = screenObserver,
            appResolver = appResolver,
            scope = scope,
            logSink = { Log.i("AemxOrchestrator", it) },
            youtubeSearch = { youtubeSearchOrNull() },
            confirmSend = { recipient, content, forced, choices -> awaitSendConfirmation(recipient, content, forced, choices) },
            confirmRequired = { sendConfirmEnabled },
            recipientLookup = { spoken -> lookupKakaoFriends(spoken) },
            hasExactContact = { spoken -> hasExactContact(spoken) },
            onRunEnded = { syncRunEnabled() },
        )
    }

    /**
     * 가짜 파트로 파이프라인을 조립한다. 가짜 LLM 은 시스템 프롬프트의 예시 출력을 그대로 돌려주고, 가짜 실행모듈은
     * 명령을 받을 때마다 미리 정해 둔 화면으로 넘어간다. 두 번째 명령에서는 일부러 화면이 바뀌지 않게 해서
     * **재분석 경로가 눈에 보이도록** 해 뒀다.
     */
    private fun buildOrchestrator(planner: PlanGenerator?): PipelineOrchestrator {
        val observer = FakeScreenObserver(
            ScreenSnapshot(
                packageName = "com.android.launcher",
                className = "Home",
                texts = listOf("홈 화면")
            )
        )

        fun youtube(vararg texts: String) = ScreenSnapshot(
            packageName = "com.example.fake.youtube",
            className = "YoutubeScreen",
            texts = texts.toList()
        )

        // 시나리오: "유튜브에서 고양이 영상 틀어줘" = open_app -> search -> play_first.
        // 첫 영상을 눌렀는데 아무 반응이 없어(3번째 = null) 재분석이 돌고, 새 계획의 play_first 로 끝까지 진행된다.
        val executor = ScriptedActionExecutor(
            observer = observer,
            screensAfterEach = listOf(
                youtube("유튜브", "홈", "Shorts"),
                youtube("고양이", "고양이 영상 모음", "고양이 브이로그"),
                null,
                youtube("고양이 영상 모음", "재생 중", "댓글")
            )
        )

        val effectivePlanner = planner ?: FakePlanGenerator(
            responses = listOf(
                """{"app": "유튜브", "steps": [{"action": "open_app", "target": "유튜브"}, {"action": "search", "target": "고양이"}, {"action": "play_first"}]}""",
                """{"app": "유튜브", "steps": [{"action": "play_first"}]}"""
            ),
            // 실기기 추론이 20~35초 걸리는 것을 짧게나마 흉내 낸다.
            latencyMs = 800
        )

        return PipelineOrchestrator(
            planner = effectivePlanner,
            executor = executor,
            screenObserver = observer,
            appResolver = FakeAppResolver(),
            scope = scope,
            config = PipelineConfig(quietPeriodMs = 300),
            logSink = { Log.i("AemxOrchestrator", it) },
        )
    }

    private fun render(state: PipelineState) {
        _status.value = when (state) {
            is PipelineState.Idle -> "대기 중"
            is PipelineState.Listening -> "듣는 중... ${state.partial}"
            is PipelineState.Planning ->
                if (state.replanCount > 0) "화면을 다시 분석하는 중 (${state.replanCount}번째)"
                else "실행계획을 만드는 중..."
            is PipelineState.Executing ->
                "실행 중 (${state.position}/${state.total}) ${state.step.describe()}"
            is PipelineState.Verifying -> "화면 확인 중..."
            is PipelineState.NeedsUser -> "사람이 처리해야 함: ${state.reason}"
            is PipelineState.NeedsSetup -> "준비 필요: ${state.reason}"
            is PipelineState.Finished -> "완료: ${state.message}"
            is PipelineState.Failed -> "실패: ${state.reason}"
        }
        syncRunEnabled()
    }

    /** 접근성 서비스가 계획 하나를 끝냈을 때 상태를 바꾼다. 앱 본체는 화면과 무관하게 항상 받는다. */
    private val deviceObserver: (PlanOutcome) -> Unit = { outcome ->
        endTiming(
            TimingPath.DIRECT,
            when (outcome) {
                is PlanOutcome.Completed -> CommandOutcome.SUCCESS
                is PlanOutcome.Failed -> CommandOutcome.FAILED
            },
        )
        _status.value = when (outcome) {
            is PlanOutcome.Completed -> appContext.getString(R.string.pipeline_device_done)
            is PlanOutcome.Failed -> appContext.getString(
                R.string.pipeline_device_step_failed,
                outcome.stepIndex + 1,
                outcome.message,
            )
        }
    }

    // ---------------------------------------------------------------- 보내기 확인

    /**
     * 메시지를 보내기 전에 사용자의 확인을 기다린다. 질문을 소리로 읽고, 화면의 보내기·취소나 음성 "응"·"아니"로 답한다.
     * 15초 안에 답이 없으면 보내지 않는다. 확인을 기다리는 동안은 호출어 문을 열어 짧은 답을 듣는다.
     * 중단 요청은 이 코루틴을 취소하므로 중단이 우선한다.
     */
    private suspend fun awaitSendConfirmation(
        recipient: String,
        content: String,
        forced: Boolean,
        choices: List<String>,
    ): String? {
        // 받는 사람을 바꿨거나 고를 후보가 있으면([forced], [choices]) 확인 설정이 꺼져 있어도 묻는다.
        if (!forced && choices.isEmpty() && !sendConfirmEnabled) return recipient
        val answer = CompletableDeferred<String?>()
        pendingSend = answer
        pendingChoices = choices
        if (announcer == null) announcer = AndroidTtsController(appContext) { }
        // 후보가 하나면 확인 글을 읽는다. 여럿이면 고르라고 짧게 묻고 후보 이름을 읽는다.
        val question = if (choices.isEmpty()) {
            appContext.getString(R.string.send_confirm_question, recipient, content)
        } else {
            appContext.getString(R.string.send_confirm_pick, choices.joinToString(", "))
        }
        announcer?.speak(question)
        val gateBefore = gate
        gateBefore?.required = false
        try {
            // 시간이 지나거나 취소하면 null 이다. null 이면 보내지 않는다.
            return withTimeoutOrNull(SEND_CONFIRM_TIMEOUT_MS) { answer.await() }
        } finally {
            pendingSend = null
            pendingChoices = emptyList()
            gateBefore?.required = settings.wakeWord
        }
    }

    /** 말한 받는 사람이 연락처에 정확히 있는지. 있으면 카톡 검색 없이 그대로 보낸다(기존 동작). 권한이 없으면 없는 것으로 본다. */
    private fun hasExactContact(spoken: String): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return RecipientFallback.pick(spoken, contactDisplayNames()) == RecipientFallback.Pick.Exact
    }

    /**
     * 카톡 검색으로 받는 사람 후보 이름을 모은다. 보내지 않는다([AemxAccessibilityService.lookupKakaoRecipients]).
     * 로그에는 이름 대신 결과 개수만 남긴다. 시간이 넘으면 실패로 돌려준다.
     */
    private suspend fun lookupKakaoFriends(spoken: String): RecipientLookup {
        val result = CompletableDeferred<RecipientLookup>()
        val started = AemxAccessibilityService.lookupKakaoRecipients(
            spoken,
            onNames = { names ->
                Log.i("AemxKakaoLookup", "카톡 검색 결과 ${names.size}개")
                result.complete(RecipientLookup.Names(names))
            },
            onFailed = { reason -> result.complete(RecipientLookup.Unavailable(reason)) },
        )
        if (!started) return RecipientLookup.Unavailable("접근성 서비스가 연결되어 있지 않거나 다른 계획이 실행 중이다")
        return try {
            withTimeout(KAKAO_LOOKUP_TIMEOUT_MS + LOOKUP_GRACE_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            RecipientLookup.Unavailable("카톡 검색이 시간 안에 끝나지 않았다")
        }
    }

    private fun contactDisplayNames(): List<String> {
        val names = ArrayList<String>()
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { names += it }
        }
        return names
    }

    /** 확인 화면의 답 한 번. 보내기면 고른 이름을, 취소면 null 을 기다리는 쪽에 넘긴다. 확인 대기 중이 아니면 아무 일도 없다. */
    private fun answerPendingSend(decision: SendDecision) {
        pendingSend?.complete(if (decision.send) decision.recipient else null)
    }

    /**
     * 음성 답을 읽는다. 후보가 있으면 이름을 말해 고를 수 있다("김정우"). 그 밖의 답은 "응"/"아니"다.
     * 후보가 여럿이면 고르기 전 "응"은 보내지 않고 고르라고 안내한다. 답이 아니면 안내만 하고 명령으로 넘기지 않는다.
     */
    private fun onSendVoiceAnswer(text: String) {
        val choices = pendingChoices
        val spokenName = text.filterNot(Char::isWhitespace)
        val named = choices.firstOrNull { it.filterNot(Char::isWhitespace) == spokenName }
        if (named != null) {
            AssistantStore.shared.selectRecipient(named)
            return
        }
        val answer = SendAnswer.parse(text)
        if (answer == null) {
            _status.value = appContext.getString(R.string.send_confirm_hint)
            return
        }
        if (answer && choices.isNotEmpty() && AssistantStore.shared.state.value.confirmSelected == null) {
            _status.value = appContext.getString(R.string.send_confirm_pick_first)
            return
        }
        AssistantStore.shared.answerSend(answer)
    }

    /**
     * 시연용 확인 화면. 임의의 받는 사람과 내용을 띄우고 답해도 보내지 않는다. 캡처용이다.
     * 끄면 실행 번호를 취소로 닫아 캡슐을 치운다.
     */
    fun demoConfirm(on: Boolean) {
        val previous = demoRunId
        if (previous != null) {
            AssistantStore.shared.post(PipelineEvent.CancelRequested(previous, PipelineOrchestrator.SEQUENCE.incrementAndGet()))
            AssistantStore.shared.post(PipelineEvent.Cancelled(previous, PipelineOrchestrator.SEQUENCE.incrementAndGet(), emptySet()))
            demoRunId = null
        }
        if (!on) return
        val id = UUID.randomUUID().toString().take(8)
        demoRunId = id
        AssistantStore.shared.post(PipelineEvent.RunStarted(id, PipelineOrchestrator.SEQUENCE.incrementAndGet(), "시연 확인"))
        AssistantStore.shared.post(
            PipelineEvent.ConfirmSend(id, PipelineOrchestrator.SEQUENCE.incrementAndGet(), "demo", "테스트 받는 사람", "테스트")
        )
    }

    /** adb 훅: 보내기 확인을 이 실행 동안만 끈다(true 면 켠다). 설정 값은 바꾸지 않는다. */
    fun setSendConfirmOverride(enabled: Boolean?) {
        sendConfirmOverride = enabled
    }

    // ---------------------------------------------------------------- 시작과 설정

    /**
     * 한 번만 준비한다. 서비스와 화면 어느 쪽이 먼저 불러도 된다. 실행 결과를 받는 통로도 여기서 건다.
     * 접근성 서비스는 이 객체보다 오래 살아 있으므로 콜백은 떼지 않는다.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = PipelineSettings(appContext)
        cloudSettings = CloudSettings(appContext)
        com.sihoo.aemx.pipeline.llm.SendMessageGuard.logSink = { Log.i("AemxMessageGuard", it) }
        _status.value = appContext.getString(R.string.pipeline_state_idle)
        AemxAccessibilityService.setPlanObserver(deviceObserver)
        resetPipeline()
        // 실제 LLM 을 쓰기로 했으면 시작할 때 올린다(이미 올라가 있으면 prepare 가 다시 올리지 않는다).
        if (settings.realLlm) prepareRealLlm()
        // 확인 화면의 보내기·취소 버튼은 답만 보낸다. 기다리는 쪽이 받아 보내거나 멈춘다.
        scope.launch {
            AssistantStore.shared.sendAnswers.collect { answerPendingSend(it) }
        }
        // 캡슐의 중단 버튼은 의도만 보낸다. 명령을 소유한 오케스트레이터가 받아 멈춘다.
        scope.launch {
            AssistantStore.shared.cancelRequests.collect { runId ->
                // 듣는 중 캡슐의 중단은 듣기를 끈다. 명령의 중단은 오케스트레이터가 받는다.
                if (runId == voiceRunId) {
                    cancelWakeCall()
                    return@collect
                }
                if (orchestrator?.requestCancel(runId) == true) return@collect
                // 멈출 명령이 없다(이미 끝났거나 시작 전). 캡슐이 "중단하는 중"으로 남아 멈춰 보이지 않게 끝낸다.
                // 화면은 실행 번호가 맞는 사건만 받으므로 지난 명령의 번호면 무시된다.
                val seq = PipelineOrchestrator.SEQUENCE
                AssistantStore.shared.post(PipelineEvent.CancelRequested(runId, seq.incrementAndGet()))
                AssistantStore.shared.post(PipelineEvent.Cancelled(runId, seq.incrementAndGet(), emptySet()))
            }
        }
    }

    val isEnrolled: Boolean get() = verifier.isEnrolled

    /** 설정 값. 화면이 체크박스를 채울 때 읽는다. */
    val prefs: PipelineSettings get() = settings

    /** 클라우드 설정. 화면의 클라우드 칸이 쓴다. */
    val cloud: CloudSettings get() = cloudSettings

    fun setWakeWord(enabled: Boolean) {
        settings.wakeWord = enabled
        gate?.required = enabled
        speech?.useVad = enabled
    }

    fun setOwnerOnly(enabled: Boolean) {
        settings.ownerOnly = enabled
        speech?.requireOwnerVoice = enabled
        if (enabled) scope.launch(Dispatchers.IO) { verifier.prepare() }
    }

    fun setAutoRun(enabled: Boolean) {
        settings.autoRun = enabled
    }

    // ---------------------------------------------------------------- 음성

    /**
     * 음성 듣기를 켠다. 모델이 120MB 라 처음 켤 때 몇 초 걸린다. 준비하는 동안 상태줄로 알린다.
     * 최종 결과는 명령칸에 넣고, 자동 실행이 켜져 있으면 실행 버튼과 같은 경로로 실행한다.
     * 마이크 권한은 호출하는 쪽(화면이나 서비스)이 먼저 확인한다.
     */
    fun startVoice() {
        // 켜라는 요청이 오면 잠시 끈 상태는 끝난다(홈의 마이크 버튼, 빠른 메뉴 모두).
        micPaused = false
        if (_listening.value || preparingMic) return
        val source = speech ?: SherpaSpeechSource(appContext, scope).also {
            speech = it
            // 호출어 문을 한 겹 씌워서 화면은 문을 통과한 결과만 받는다.
            val wakeGate = WakeWordSpeechSource(it, scope)
            gate = wakeGate
            scope.launch { wakeGate.results.collect { result -> onSpeech(result) } }
            // 디버깅 패널용. 호출어 문을 지나기 전의 원본 결과와 소리 크기를 넘긴다(모드가 꺼져 있으면 버린다).
            it.levelListener = { rms, threshold, gateOpen ->
                MicStatus.level = rms
                SpeechTrace.onLevel(rms, threshold, gateOpen)
            }
            scope.launch { it.results.collect { result -> SpeechTrace.onResult(result) } }
            scope.launch {
                it.rejections.collect { text -> _status.value = appContext.getString(R.string.pipeline_owner_rejected, text) }
            }
            scope.launch {
                wakeGate.ignored.collect { text -> _status.value = appContext.getString(R.string.pipeline_wake_ignored, text) }
            }
            scope.launch {
                wakeGate.awake.collect { awake -> onAwake(awake) }
            }
        }
        source.speakerVerifier = verifier
        // 호출어를 쓸 때만 VAD 로 인식기를 쉬게 한다. 끄면 예전처럼 항상 듣는다.
        val needWake = settings.wakeWord
        gate?.required = needWake
        source.useVad = needWake
        source.requireOwnerVoice = settings.ownerOnly

        _status.value = appContext.getString(R.string.pipeline_stt_preparing)
        preparingMic = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    source.prepare()
                    // 등록된 목소리가 있을 때만 화자 모델(28MB)을 올린다.
                    if (verifier.isEnrolled) verifier.prepare()
                }
                source.start()
                _listening.value = true
                _status.value = appContext.getString(R.string.pipeline_stt_ready)
            } catch (e: Exception) {
                Log.e(TAG, "음성인식 준비 실패", e)
                _status.value = appContext.getString(R.string.pipeline_stt_failed, e.message ?: "")
            } finally {
                preparingMic = false
            }
        }
    }

    /**
     * 음성 없이 듣는 중 캡슐만 띄우고 끈다(adb 검증용). 마이크와 인식기는 건드리지 않는다.
     * 캡슐의 중단 버튼은 [stopVoice] 로 이어져 같은 방식으로 끝난다.
     */
    fun demoListening(on: Boolean) {
        if (on) {
            val id = UUID.randomUUID().toString().take(8)
            voiceRunId = id
            AssistantStore.shared.post(PipelineEvent.ListeningStarted(id, PipelineOrchestrator.SEQUENCE.incrementAndGet()))
        } else {
            stopVoice()
        }
    }

    /**
     * 빠른 메뉴에서 마이크를 잠시 껐는지. 켜져 있는 동안은 앱이 스스로 마이크를 다시 켜지 않는다([VoiceService.resumeIfIdle]).
     * 저장하지 않으므로 앱이 다시 시작되면 사라진다. 사용자가 끈 상태([VoicePrefs])와는 별개다.
     */
    @Volatile
    var micPaused = false
        private set

    /**
     * 마이크를 잠시 끈다. 듣기 서비스는 그대로 둔다. 서비스를 내리면 다른 앱이 앞에 있을 때 다시 올릴 수 없기 때문이다
     * (안드로이드 14 이후의 백그라운드 마이크 서비스 시작 제한).
     */
    fun pauseMic() {
        stopVoice()
        micPaused = true
    }

    /** 잠시 껐던 마이크를 다시 켠다. */
    fun resumeMic() {
        startVoice()
    }

    fun stopVoice() {
        speech?.stop()
        MicStatus.level = 0.0
        if (_listening.value) {
            _listening.value = false
            _status.value = appContext.getString(R.string.pipeline_stt_stopped)
        }
        voiceRunId?.let { AssistantStore.shared.post(PipelineEvent.ListeningEnded(it, PipelineOrchestrator.SEQUENCE.incrementAndGet())) }
        voiceRunId = null
    }

    /**
     * 듣는 중 캡슐의 중단. 이번 호출만 닫는다. 마이크와 인식기는 그대로 둬서 다음 호출을 바로 받는다.
     * 마이크를 끄는 것은 [stopVoice] 가 맡는다(서비스 종료, 마이크 버튼, 알림).
     */
    private fun cancelWakeCall() {
        gate?.cancelAwake()
        voiceRunId?.let { AssistantStore.shared.post(PipelineEvent.ListeningEnded(it, PipelineOrchestrator.SEQUENCE.incrementAndGet())) }
        voiceRunId = null
        _status.value = appContext.getString(R.string.pipeline_stt_ready)
    }

    /** 호출어를 들었거나 호출어 창이 닫혔다. 화면의 듣는 중 캡슐을 켜고 끈다. */
    private fun onAwake(awake: Boolean) {
        if (awake) {
            _status.value = appContext.getString(R.string.pipeline_wake_detected)
            val id = UUID.randomUUID().toString().take(8)
            voiceRunId = id
            AssistantStore.shared.post(PipelineEvent.ListeningStarted(id, PipelineOrchestrator.SEQUENCE.incrementAndGet()))
        } else {
            voiceRunId?.let { AssistantStore.shared.post(PipelineEvent.ListeningEnded(it, PipelineOrchestrator.SEQUENCE.incrementAndGet())) }
            voiceRunId = null
        }
    }

    private fun onSpeech(result: SpeechResult) {
        when (result) {
            // 중간 결과는 화면에 보여 주기만 하고 캡슐은 인식 중(흐린 파형)으로 바꾼다.
            is SpeechResult.Partial -> {
                _status.value = "듣는 중... ${result.text}"
                voiceRunId?.let {
                    AssistantStore.shared.post(PipelineEvent.Recognizing(it, PipelineOrchestrator.SEQUENCE.incrementAndGet(), result.text))
                }
            }
            is SpeechResult.Final -> {
                // 보내기 확인 중의 말은 명령이 아니라 답이다.
                if (pendingSend != null) {
                    onSendVoiceAnswer(result.text)
                    return
                }
                pendingSpeech = result.text to SystemClock.elapsedRealtime()
                _heard.value = result.text
                _status.value = "들은 말: ${result.text}"
                // 듣기는 여기서 끝난다. 자동 실행이 켜져 있으면 바로 명령으로 넘어간다(RunStarted 가 캡슐을 바꾼다).
                voiceRunId?.let {
                    AssistantStore.shared.post(PipelineEvent.ListeningEnded(it, PipelineOrchestrator.SEQUENCE.incrementAndGet()))
                }
                voiceRunId = null
                if (settings.autoRun) {
                    // 실행 중에 온 명령은 실행 버튼과 똑같이 무시하되, 왜 안 도는지 알린다.
                    val reason = busyReason()
                    // 표시가 잠겨 있는데 돌고 있는 것이 없으면 낡은 값이다. 다시 맞추고 이번 명령을 받는다.
                    if (RunGate.isStale(mirrorLocked = !_runEnabled.value, reason = reason)) {
                        Log.i("AemxCommandSkip", "stuck 복구: 실행 표시가 잠겨 있었지만 돌고 있는 것이 없어 다시 열었다")
                        syncRunEnabled()
                    }
                    if (reason != null) {
                        // 명령 내용은 남기지 않고 글자 수만 남긴다(개인정보).
                        Log.i("AemxCommandSkip", "건너뜀: $reason, ${result.text.length}글자")
                        _status.value = appContext.getString(R.string.pipeline_auto_run_busy, result.text)
                    } else {
                        runCommand(result.text)
                    }
                }
            }
            is SpeechResult.Error -> _status.value = result.message
        }
    }

    /** 목소리를 등록한다. 같은 문장을 세 번 말하게 해서 평균을 낸다. 듣기가 켜져 있어야 한다. */
    fun beginEnrollment() {
        if (!_listening.value) {
            _status.value = appContext.getString(R.string.pipeline_enroll_need_listening)
            return
        }
        enrolledVoices.clear()
        _enrolling.value = true
        _status.value = appContext.getString(R.string.pipeline_enroll_prompt, 1, SpeakerVerifier.MIN_ENROLL_COUNT)

        scope.launch(Dispatchers.IO) { verifier.prepare() }
        speech?.enrollmentSink = { voice -> scope.launch { onEnrollmentUtterance(voice) } }
    }

    private suspend fun onEnrollmentUtterance(voice: FloatArray) {
        // 0.8초 미만은 목소리 특징을 뽑기에 짧다.
        if (voice.size < MIN_ENROLL_SAMPLES) {
            _status.value = appContext.getString(R.string.pipeline_enroll_too_short)
            return
        }
        enrolledVoices += voice
        val done = enrolledVoices.size
        if (done < SpeakerVerifier.MIN_ENROLL_COUNT) {
            _status.value = appContext.getString(R.string.pipeline_enroll_prompt, done + 1, SpeakerVerifier.MIN_ENROLL_COUNT)
            return
        }

        speech?.enrollmentSink = null
        val ok = withContext(Dispatchers.IO) { verifier.enroll(enrolledVoices.toList()) }
        enrolledVoices.clear()
        _enrolling.value = false
        _status.value = appContext.getString(if (ok) R.string.pipeline_enroll_done else R.string.pipeline_enroll_failed)
    }

    // ---------------------------------------------------------------- 가짜 경로가 쓰는 소소한 통로

    private const val MIN_ENROLL_SAMPLES = 12_800

    /** 보내기 확인을 기다리는 최대 시간. 답이 없으면 보내지 않는다. */
    private const val SEND_CONFIRM_TIMEOUT_MS = 15_000L

    /** 카톡 검색 제한 시간 위에 더해 두는 여유. 검색 쪽이 먼저 실패로 끝나게 하려는 것이다. */
    private const val LOOKUP_GRACE_MS = 5_000L

    /** 최근 실행 기록의 개수. */
    private const val RECENT_LIMIT = 2
}

/**
 * 접근성 서비스가 받는 형식으로 다시 직렬화한다. 모델 원문을 그대로 넘기지 않는 이유는 설명이나 코드블록이
 * 섞여 오기 때문이다. 파서가 읽어 낸 계획만 다시 JSON 으로 만들어 넘긴다.
 */
private fun ActionPlan.toWireJson(): String {
    val array = JSONArray()
    steps.forEach { step ->
        array.put(
            JSONObject().apply {
                put("action", step.action.wire)
                put("target", step.target ?: "")
                step.value?.let { put("value", it) }
            }
        )
    }
    return JSONObject().apply {
        put("app", app)
        put("steps", array)
    }.toString()
}
