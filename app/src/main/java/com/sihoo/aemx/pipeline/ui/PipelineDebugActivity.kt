package com.sihoo.aemx.pipeline.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.text.InputType
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sihoo.aemx.R
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.pipeline.PipelineConfig
import com.sihoo.aemx.pipeline.PipelineLogEntry
import com.sihoo.aemx.assistant.AssistantStore
import com.sihoo.aemx.pipeline.PipelineOrchestrator
import com.sihoo.aemx.AndroidTtsController
import com.sihoo.aemx.pipeline.PipelineState
import com.sihoo.aemx.pipeline.PlanStepPruner
import com.sihoo.aemx.pipeline.youtube.YouTubeSearch
import com.sihoo.aemx.pipeline.youtube.YouTubeSearchClient
import com.sihoo.aemx.pipeline.youtube.YouTubeShortcut
import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.fake.ScriptedActionExecutor
import com.sihoo.aemx.pipeline.device.AccessibilityAppResolver
import com.sihoo.aemx.pipeline.device.AccessibilityScreenObserver
import com.sihoo.aemx.pipeline.device.AccessibilityStepExecutor
import com.sihoo.aemx.pipeline.device.AnnouncementTexts
import com.sihoo.aemx.pipeline.device.AppNames
import com.sihoo.aemx.pipeline.device.announcementFor
import com.sihoo.aemx.pipeline.device.ServicePlanBridge
import com.sihoo.aemx.pipeline.device.ServiceScreenSource
import com.sihoo.aemx.pipeline.llm.CloudSettings
import com.sihoo.aemx.pipeline.llm.CommandOutcome
import com.sihoo.aemx.pipeline.llm.CommandTiming
import com.sihoo.aemx.pipeline.llm.HybridPlanGenerator
import com.sihoo.aemx.pipeline.llm.LlamaPlanGenerator
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.stt.SherpaSpeechSource
import com.sihoo.aemx.pipeline.stt.SpeakerVerifier
import com.sihoo.aemx.pipeline.stt.WakeWordSpeechSource
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.material.R as MaterialR

/**
 * 파이프라인 개발용 화면.
 *
 * STT·LLM·AccessibilityService 가 아직 각자 브랜치에 있어서, 이 화면은 전부
 * Fake 구현에 연결돼 있다. 실제 파트가 합류하면 [buildOrchestrator] 안의
 * Fake 를 진짜 어댑터로 바꾸기만 하면 같은 화면으로 실기기 검증을 할 수 있다.
 *
 * TTS 검증 화면(`MainActivity`)은 건드리지 않고 별도 런처 아이콘으로 띄운다.
 * 시연용이 아니라 개발 중 눈으로 흐름을 확인하기 위한 화면이다.
 */
class PipelineDebugActivity : AppCompatActivity() {

    private lateinit var commandInput: TextInputEditText
    private lateinit var runButton: Button
    private lateinit var realLlmSwitch: CheckBox
    private lateinit var realExecutorSwitch: CheckBox
    private lateinit var orchestratorSwitch: CheckBox
    private var deviceOrchestrator: PipelineOrchestrator? = null
    private var deviceExecutor: AccessibilityStepExecutor? = null

    /**
     * 오케스트레이터 경로에서 명령 하나가 끝났을 때 말하는 TTS. 접근성 서비스가 쓰는 것과 같은
     * [AndroidTtsController] 라 말하는 동안 TtsSilence 로 마이크가 막힌다. 엔진 초기화가 비동기라
     * 명령을 내보내기 전에 미리 만들어 둔다(준비 전에 speak 하면 소리가 버려진다).
     */
    private var announcer: AndroidTtsController? = null

    /** 화면이 보이는 동안만 true. 어댑터가 끝나고 콜백을 되돌릴지 정하는 데 쓴다. */
    @Volatile
    private var visible = false

    /**
     * 오케스트레이터를 메인이 아닌 곳에서 돌리는 스코프. 화면 읽기(바인더 호출)를
     * 메인 스레드에서 하지 않으려는 것이다. 화면이 사라질 때 같이 닫는다.
     */
    private val deviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var llmPlanner: LlamaPlanGenerator? = null
    private val cloudSettings by lazy { CloudSettings(applicationContext) }
    private var hybridPlanner: HybridPlanGenerator? = null
    private lateinit var cloudSwitch: CheckBox
    private lateinit var cloudStatus: TextView
    private lateinit var routeView: TextView
    private lateinit var youtubeStatus: TextView
    private lateinit var timingView: TextView
    /** 지금 도는 명령의 시각. 계획 콜백은 백그라운드 스레드에서 오므로 volatile. */
    @Volatile
    private var currentTiming: CommandTiming? = null

    /** [currentTiming] 의 끝 신호가 어느 경로에서 올지. 두 경로가 같은 타이머를 공유하지 않게 한다. */
    private enum class TimingPath { ORCHESTRATOR, DIRECT }

    @Volatile
    private var timingPath: TimingPath? = null

    /** 음성 최종 결과의 말 끝 시각과 문장. 같은 문장으로 명령을 실행할 때만 쓴다. */
    private var pendingSpeech: Pair<String, Long>? = null
    private lateinit var listenButton: Button
    private lateinit var wakeWordSwitch: CheckBox
    private lateinit var ownerOnlySwitch: CheckBox
    private lateinit var autoRunSwitch: CheckBox
    private lateinit var overlaySwitch: CheckBox
    private lateinit var enrollButton: Button
    private val verifier by lazy { SpeakerVerifier(applicationContext) }
    private val enrolledVoices = ArrayList<FloatArray>()
    private var speech: SherpaSpeechSource? = null
    private var gate: WakeWordSpeechSource? = null
    private var listening = false
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var orchestrator: PipelineOrchestrator
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 받는 사람을 바로잡은 기록은 logcat 으로 남긴다(이름은 글자 수만).
        com.sihoo.aemx.pipeline.llm.SendMessageGuard.logSink = { Log.i("AemxMessageGuard", it) }
        setContentView(createContentView())
        resetPipeline()
        // 처음 만들 때만 adb 명령을 읽는다. 화면을 다시 만들 때 같은 명령이 또 실행되지 않게 한다.
        if (savedInstanceState == null) handleAdbIntent(intent)
        // 캡슐의 중단 버튼은 의도만 보낸다. 명령을 소유한 이 화면의 오케스트레이터가 받아 멈춘다.
        lifecycleScope.launch {
            AssistantStore.shared.cancelRequests.collect { runId -> orchestrator.requestCancel(runId) }
        }
        // 문자 보내기가 받는 사람을 연락처에서 찾는다. 거절해도 다른 기능에는 영향이 없다.
        if (savedInstanceState == null && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CONTACTS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    /** 이미 떠 있을 때 adb 가 새 명령을 넘기면 여기로 온다(manifest 의 singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAdbIntent(intent)
    }

    /**
     * adb 로 넘어온 명령(개발 훅). cmd 또는 cmd_b64 가 있으면 명령 입력칸에 채우고, run 이 true 면 실행 버튼과
     * 같은 경로로 실행한다. 쓴 extra 는 지운다. 명령 내용은 로그에 남기지 않는다(길이만).
     */
    private fun handleAdbIntent(intent: Intent) {
        val command = AdbCommand.resolve(intent.getStringExtra("cmd"), intent.getStringExtra("cmd_b64")) ?: return
        val run = intent.getBooleanExtra("run", false)
        intent.removeExtra("cmd")
        intent.removeExtra("cmd_b64")
        intent.removeExtra("run")
        commandInput.setText(command)
        Log.i(TAG, "adb 명령을 받았다(${command.length}자, 실행=$run)")
        if (run) runCommand(command)
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else stateView.setText(R.string.pipeline_stt_denied)
    }

    /**
     * 음성인식을 켠다.
     *
     * 모델이 120MB 라 처음 켤 때 몇 초가 걸린다. 그 동안 버튼을 막고 상태줄로 알린다.
     * 인식 결과는 명령칸에 넣기만 하고 바로 실행하지는 않는다 — 잘못 들었을 때
     * 고칠 수 있어야 하기 때문이다.
     */
    private fun startListening() {
        val source = speech ?: SherpaSpeechSource(applicationContext, lifecycleScope).also {
            speech = it
            // 호출어 문을 한 겹 씌워서 화면은 문을 통과한 결과만 받는다.
            val wakeGate = WakeWordSpeechSource(it, lifecycleScope)
            gate = wakeGate
            lifecycleScope.launch { wakeGate.results.collect { result -> render(result) } }
            lifecycleScope.launch {
                it.rejections.collect { text -> stateView.text = getString(R.string.pipeline_owner_rejected, text) }
            }
            lifecycleScope.launch {
                wakeGate.ignored.collect { text -> stateView.text = getString(R.string.pipeline_wake_ignored, text) }
            }
            lifecycleScope.launch {
                wakeGate.awake.collect { awake -> if (awake) stateView.setText(R.string.pipeline_wake_detected) }
            }
        }
        source.speakerVerifier = verifier
        // 호출어를 쓸 때만 VAD 로 인식기를 쉬게 한다. 끄면 예전처럼 항상 듣는다.
        val needWake = wakeWordSwitch.isChecked
        gate?.required = needWake
        source.useVad = needWake
        // 체크박스 리스너는 바꾸는 순간에만 값을 넣는다. 듣기를 켜기 전에 체크했거나 speech 를 새로
        // 만든 경우에 값이 빠지지 않게, 시작할 때마다 현재 값을 넣는다.
        source.requireOwnerVoice = ownerOnlySwitch.isChecked

        // 준비하는 동안 다시 눌리면 녹음이 두 번 열린다. 버튼을 막아 둔다.
        listenButton.isEnabled = false
        stateView.setText(R.string.pipeline_stt_preparing)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    source.prepare()
                    // 등록된 목소리가 있을 때만 화자 모델(28MB)을 올린다.
                    if (verifier.isEnrolled) verifier.prepare()
                }
                source.start()
                listening = true
                listenButton.setText(R.string.pipeline_listen_stop)
                stateView.setText(R.string.pipeline_stt_ready)
            } catch (e: Exception) {
                Log.e(TAG, "음성인식 준비 실패", e)
                stateView.text = getString(R.string.pipeline_stt_failed, e.message ?: "")
            } finally {
                listenButton.isEnabled = true
            }
        }
    }

    private fun stopListening() {
        speech?.stop()
        listening = false
        listenButton.setText(R.string.pipeline_listen_start)
        // 멈춘 것을 상태줄에도 적는다. 버튼만 바뀌면 아직 듣는 줄 알고 계속 말하게 된다.
        stateView.setText(R.string.pipeline_stt_stopped)
    }

    /**
     * 목소리를 등록한다. 같은 문장을 세 번 말하게 해서 평균을 낸다.
     *
     * 한 번만 듣고 등록하면 그때 컨디션이나 마이크 거리에 점수가 크게 흔들린다.
     * 듣기가 켜져 있어야 하고, 이 동안 인식 결과는 명령으로 나가지 않는다.
     */
    private fun beginEnrollment() {
        if (!listening) {
            stateView.setText(R.string.pipeline_enroll_need_listening)
            return
        }
        enrolledVoices.clear()
        enrollButton.isEnabled = false
        stateView.text = getString(R.string.pipeline_enroll_prompt, 1, SpeakerVerifier.MIN_ENROLL_COUNT)

        lifecycleScope.launch(Dispatchers.IO) { verifier.prepare() }
        speech?.enrollmentSink = { voice ->
            runOnUiThread { onEnrollmentUtterance(voice) }
        }
    }

    private fun onEnrollmentUtterance(voice: FloatArray) {
        // 0.8초 미만은 목소리 특징을 뽑기에 짧다.
        if (voice.size < MIN_ENROLL_SAMPLES) {
            stateView.setText(R.string.pipeline_enroll_too_short)
            return
        }
        enrolledVoices += voice
        val done = enrolledVoices.size
        if (done < SpeakerVerifier.MIN_ENROLL_COUNT) {
            stateView.text = getString(R.string.pipeline_enroll_prompt, done + 1, SpeakerVerifier.MIN_ENROLL_COUNT)
            return
        }

        speech?.enrollmentSink = null
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { verifier.enroll(enrolledVoices.toList()) }
            enrolledVoices.clear()
            enrollButton.isEnabled = true
            stateView.setText(if (ok) R.string.pipeline_enroll_done else R.string.pipeline_enroll_failed)
        }
    }

    /**
     * 명령을 처리하는 중인지. 오케스트레이터가 바쁘거나 실제 기기 경로가 계획을 만드는 중이면 실행 버튼이
     * 막히고, 계획을 서비스에 넘긴 뒤 실행하는 동안은 서비스의 실행 상태로 안다.
     */
    private fun isRunning(): Boolean =
        !runButton.isEnabled || orchestrator.isBusy || AemxAccessibilityService.isPlanRunning()

    /** 선택된 경로(오케스트레이터, 실제 기기, 가짜)로 명령을 실행한다. 실행 버튼과 "말하면 바로 실행"이 같이 쓴다. */
    private fun runCommand(command: String) {
        if (orchestratorSwitch.isChecked) {
            runViaOrchestrator(command)
            return
        }
        if (realExecutorSwitch.isChecked) {
            runOnDevice(command)
            return
        }
        // 진짜 LLM 은 준비 비용이 커서 매번 다시 만들지 않고 계속 쓴다.
        if (!realLlmSwitch.isChecked) resetPipeline()
        orchestrator.submit(command)
    }

    /** 명령의 시작 시각을 잡는다. 음성이면 말 끝 판정 시각부터, 글 입력이면 지금부터 잰다. */
    private fun beginTiming(command: String, path: TimingPath) {
        val speech = pendingSpeech?.takeIf { it.first == command }
        pendingSpeech = null
        timingPath = path
        currentTiming = CommandTiming(
            startMs = speech?.second ?: SystemClock.elapsedRealtime(),
            fromSpeech = speech != null,
        )
    }

    /** 같은 경로에서 온 끝 신호만 받아 한 줄을 로그와 화면에 남긴다. 계획 전의 신호는 타이머가 무시한다. */
    private fun endTiming(path: TimingPath, outcome: CommandOutcome) {
        if (timingPath != path) return
        val line = currentTiming?.finish(SystemClock.elapsedRealtime(), outcome) ?: return
        Log.i("AemxTiming", line)
        timingView.text = line
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

    private fun render(result: SpeechResult) {
        when (result) {
            // 중간 결과는 보여 주기만 한다. 아직 말이 끝나지 않았다.
            is SpeechResult.Partial -> stateView.text = "듣는 중... ${result.text}"
            is SpeechResult.Final -> {
                pendingSpeech = result.text to SystemClock.elapsedRealtime()
                commandInput.setText(result.text)
                stateView.text = "들은 말: ${result.text}"
                if (autoRunSwitch.isChecked) {
                    // 실행 중에 온 명령은 실행 버튼과 똑같이 무시하되, 왜 안 도는지 알린다.
                    if (isRunning()) {
                        stateView.text = getString(R.string.pipeline_auto_run_busy, result.text)
                    } else {
                        runCommand(result.text)
                    }
                }
            }
            is SpeechResult.Error -> stateView.text = result.message
        }
    }

    /**
     * 가짜 파트들을 새로 만들어 붙인다.
     *
     * 가짜 LLM 은 준비된 응답을 순서대로 쓰고 가짜 실행모듈은 대본을 따라가므로,
     * 한 번 돌고 나면 둘 다 바닥난다. 실행할 때마다 새로 조립해야 같은 시나리오를
     * 반복해서 볼 수 있다.
     */
    private fun resetPipeline() {
        collectJob?.cancel()
        orchestrator = buildOrchestrator(planner = null)
        collectJob = lifecycleScope.launch {
            launch { orchestrator.state.collect { render(it) } }
            launch { orchestrator.log.collect { entries -> renderLog(entries) } }
            launch { orchestrator.events.collect { AssistantStore.shared.post(it) } }
        }
    }

    /**
     * 온디바이스 LLM 을 준비해서 파이프라인에 꽂는다.
     *
     * 모델이 1.2GB 라 이 앱에서 처음 켜면 내려받는다(Wi-Fi 권장). 준비가 끝날 때까지
     * 실행 버튼을 막고 진행 상황을 상태줄에 보여 준다.
     */
    private fun prepareRealLlm() {
        realLlmSwitch.isEnabled = false
        runButton.isEnabled = false

        lifecycleScope.launch {
            val planner = llmPlanner ?: LlamaPlanGenerator(applicationContext).also { llmPlanner = it }
            try {
                withContext(Dispatchers.IO) {
                    planner.prepare { _, message ->
                        lifecycleScope.launch { stateView.text = message }
                    }
                }
                collectJob?.cancel()
                orchestrator = buildOrchestrator(planner = planner)
                collectJob = lifecycleScope.launch {
                    launch { orchestrator.state.collect { render(it) } }
                    launch { orchestrator.log.collect { entries -> renderLog(entries) } }
                    launch { orchestrator.events.collect { AssistantStore.shared.post(it) } }
                }
                stateView.text = getString(R.string.pipeline_llm_ready)
            } catch (e: Exception) {
                Log.e(TAG, "LLM 준비 실패", e)
                stateView.text = getString(R.string.pipeline_llm_failed, e.message ?: "")
                realLlmSwitch.isChecked = false
            } finally {
                realLlmSwitch.isEnabled = true
                runButton.isEnabled = true
            }
        }
    }

    /**
     * 말한 명령을 LLM 에 넣고, 나온 계획을 접근성 서비스에 넘겨 실제로 기기를 조작한다.
     *
     * 오케스트레이터를 거치지 않는다. 오케스트레이터의 실행모듈 경계는 step 을 하나씩
     * 수행하는 모양인데 접근성 서비스는 계획 하나를 통째로 받아 돌려서, 둘을 맞추는 일은
     * 이슈 #11 에서 따로 한다. 그때까지 음성부터 실제 조작까지를 실기기에서 보려면
     * 이 길이 필요하다. 화면 검증과 재분석은 이 길에서는 돌지 않는다.
     */
    private fun runOnDevice(command: String) {
        val planner = llmPlanner
        if (planner == null || !planner.isReady()) {
            stateView.setText(R.string.pipeline_device_needs_llm)
            return
        }
        if (!AemxAccessibilityService.isConnected()) {
            stateView.setText(R.string.pipeline_device_needs_service)
            return
        }

        runButton.isEnabled = false
        stateView.setText(R.string.pipeline_device_planning)
        beginTiming(command, TimingPath.DIRECT)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                planFor(planner).generate(PlanRequest(utterance = command, mode = PlanRequest.Mode.INITIAL))
            }
            when (result) {
                is PlanResult.Success -> {
                    // 오케스트레이터 경로와 같은 후처리(시간만 드는 앱 열기 제거, 유튜브 API 로 영상 열기)를 거친 뒤 보낸다.
                    val pruned = PlanStepPruner.prune(result.plan)
                    val plan = youtubeSearchOrNull()?.let { YouTubeShortcut.rewrite(pruned, command, it) } ?: pruned
                    val json = plan.toWireJson()
                    Log.i(TAG, "실기기 실행 계획: $json")
                    logView.text = json
                    AemxAccessibilityService.submitActionPlan(json).fold(
                        onSuccess = { stateView.setText(R.string.pipeline_device_running) },
                        onFailure = {
                            stateView.text = getString(R.string.pipeline_device_failed, it.message ?: "")
                            endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                        },
                    )
                }
                is PlanResult.Malformed -> {
                    logView.text = result.raw
                    stateView.text = getString(R.string.pipeline_device_failed, result.reason)
                    endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                }
                is PlanResult.EngineError -> {
                    stateView.text = getString(R.string.pipeline_device_failed, result.message)
                    endTiming(TimingPath.DIRECT, CommandOutcome.FAILED)
                }
                is PlanResult.Refused -> {
                    stateView.text = getString(R.string.pipeline_device_failed, result.message)
                    endTiming(TimingPath.DIRECT, CommandOutcome.REFUSED)
                }
            }
            runButton.isEnabled = true
        }
    }

    /**
     * 말한 명령을 오케스트레이터를 거쳐 실제 기기에서 실행한다(이슈 #11).
     *
     * [runOnDevice] 와 달리 step 마다 화면이 기대대로 바뀌었는지 검사하고, 어긋나면 현재 화면을
     * 넣어 LLM 에 다시 묻는다. 진짜 LLM 이 켜져 있고 접근성 서비스가 연결돼 있어야 한다.
     */
    private fun runViaOrchestrator(command: String) {
        val planner = llmPlanner
        if (planner == null || !planner.isReady()) {
            stateView.setText(R.string.pipeline_device_needs_llm)
            return
        }
        if (!AemxAccessibilityService.isConnected()) {
            stateView.setText(R.string.pipeline_device_needs_service)
            return
        }

        if (announcer == null) announcer = AndroidTtsController(applicationContext) { }
        val device = deviceOrchestrator ?: buildDeviceOrchestrator(planFor(planner)).also { deviceOrchestrator = it }
        // 다른 경로가 orchestrator 를 갈아 끼웠을 수 있으니, 이쪽이 아니면 화면 구독을 옮긴다.
        if (orchestrator !== device) {
            collectJob?.cancel()
            orchestrator = device
            collectJob = lifecycleScope.launch {
                launch {
                    device.state.collect {
                        recordOrchestratorEnd(it)
                        render(it)
                    }
                }
                // StateFlow 는 구독하자마자 마지막 값을 준다. 지난 명령의 끝 상태를 다시 말하지 않게 건너뛴다.
                launch { device.state.drop(1).collect { announce(it) } }
                launch { device.events.collect { AssistantStore.shared.post(it) } }
                launch { device.log.collect { entries -> renderLog(entries) } }
            }
        }
        beginTiming(command, TimingPath.ORCHESTRATOR)
        device.submit(command)
    }

    /**
     * 로컬 계획 생성기를 클라우드 하이브리드로 감싼다. 설정(켜짐·키)은 명령마다 읽으므로
     * 화면에서 바꾸면 다음 명령부터 바로 적용된다. 키가 없으면 로컬과 똑같이 동작한다.
     */
    private fun planFor(local: PlanGenerator): PlanGenerator =
        hybridPlanner ?: HybridPlanGenerator(
            local = local,
            cloud = { cloudSettings.generatorOrNull() },
            log = { Log.i("CloudPlan", it) },
            onRoute = { report ->
                // 계획이 나온 시각. 끝 신호보다 먼저 일어나므로(같은 흐름에서 호출) 순서가 어긋나지 않는다.
                currentTiming?.planned(SystemClock.elapsedRealtime(), report)
                runOnUiThread { routeView.text = report.summary() }
            },
        ).also { hybridPlanner = it }

    /**
     * 유튜브 검색을 YouTube Data API 로 미리 푸는 통로. 설정이 꺼졌거나 키가 없으면 null(화면 검색 경로).
     * 한도 초과 기억은 [YouTubeQuota.shared] 가 맡는다. 시간은 명령의 타이머에 "유튜브 API" 로 붙인다.
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

    private fun renderYoutubeStatus() {
        if (!::youtubeStatus.isInitialized) return
        youtubeStatus.setText(
            when {
                !cloudSettings.hasYoutubeKey -> R.string.pipeline_youtube_status_no_key
                !cloudSettings.youtubeApiEnabled -> R.string.pipeline_youtube_status_off
                else -> R.string.pipeline_youtube_status_on
            },
        )
    }

    private fun renderCloudStatus() {
        cloudStatus.setText(
            when {
                !cloudSettings.enabled -> R.string.pipeline_cloud_status_off
                !cloudSettings.hasKey -> R.string.pipeline_cloud_status_no_key
                else -> R.string.pipeline_cloud_status_on
            },
        )
    }

    /** 명령이 끝나 최종 상태가 되거나 재분석에 들어가면 말한다. 오케스트레이터 경로에서만 불린다(가짜 경로는 말하지 않는다). */
    private fun announce(state: PipelineState) {
        val texts = AnnouncementTexts(
            finished = getString(R.string.pipeline_announce_finished),
            failed = getString(R.string.pipeline_announce_failed),
            needsUser = getString(R.string.pipeline_announce_needs_user),
            needsSetup = getString(R.string.pipeline_announce_needs_setup),
            replanning = getString(R.string.pipeline_announce_replanning),
        )
        val text = announcementFor(state, texts) ?: return
        announcer?.speak(text)
    }

    /** 진짜 LLM + 접근성 서비스 어댑터로 오케스트레이터를 조립한다. */
    private fun buildDeviceOrchestrator(planner: PlanGenerator): PipelineOrchestrator {
        val screenObserver = AccessibilityScreenObserver(ServiceScreenSource())
        // 같은 리졸버를 오케스트레이터와 실행기가 나눠 써서, 풀린 앱 이름을 서비스에 그대로 넘긴다.
        val appResolver = AccessibilityAppResolver(applicationContext)
        val executor = AccessibilityStepExecutor(
            bridge = ServicePlanBridge,
            foregroundPackage = { screenObserver.capture()?.packageName },
            nameForPackage = { pkg -> AppNames.forPackage(pkg) ?: appResolver.nameFor(pkg) },
            isSupportedApp = appResolver::isResolvable,
            // step 이 끝나면 화면이 원래 걸어 둔 콜백으로 되돌린다. 멈춘 화면이면 걸지 않는다.
            restoreObserver = { if (visible) deviceObserver else null }
        )
        deviceExecutor = executor
        return PipelineOrchestrator(
            planner = planner,
            executor = executor,
            screenObserver = screenObserver,
            appResolver = appResolver,
            scope = deviceScope,
            logSink = { Log.i("AemxOrchestrator", it) },
            youtubeSearch = { youtubeSearchOrNull() },
        )
    }

    /**
     * 가짜 파트로 파이프라인을 조립한다.
     *
     * 가짜 LLM 은 시스템 프롬프트의 예시 출력을 그대로 돌려주고, 가짜 실행모듈은
     * 명령을 받을 때마다 미리 정해 둔 화면으로 넘어간다. 두 번째 명령에서는
     * 일부러 화면이 바뀌지 않게 해서 **재분석 경로가 눈에 보이도록** 해 뒀다.
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
        // 첫 영상을 눌렀는데 아무 반응이 없어(3번째 = null) 재분석이 돌고,
        // 새 계획의 play_first 로 끝까지 진행된다.
        val executor = ScriptedActionExecutor(
            observer = observer,
            screensAfterEach = listOf(
                youtube("유튜브", "홈", "Shorts"),                          // 앱 실행
                youtube("고양이", "고양이 영상 모음", "고양이 브이로그"),         // 검색 결과
                null,                                                      // 첫 영상을 눌렀으나 반응 없음
                youtube("고양이 영상 모음", "재생 중", "댓글")                 // 다시 눌러 재생
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
            scope = lifecycleScope,
            config = PipelineConfig(quietPeriodMs = 300),
            logSink = { Log.i("AemxOrchestrator", it) },
        )
    }

    private fun render(state: PipelineState) {
        stateView.text = when (state) {
            is PipelineState.Idle -> "대기 중"
            is PipelineState.Listening -> "듣는 중... ${state.partial}"
            is PipelineState.Planning ->
                if (state.replanCount > 0) {
                    "화면을 다시 분석하는 중 (${state.replanCount}번째)"
                } else {
                    "실행계획을 만드는 중..."
                }
            is PipelineState.Executing ->
                "실행 중 (${state.position}/${state.total}) ${state.step.describe()}"
            is PipelineState.Verifying -> "화면 확인 중..."
            is PipelineState.NeedsUser -> "사람이 처리해야 함: ${state.reason}"
            is PipelineState.NeedsSetup -> "준비 필요: ${state.reason}"
            is PipelineState.Finished -> "완료: ${state.message}"
            is PipelineState.Failed -> "실패: ${state.reason}"
        }
        runButton.isEnabled = !orchestrator.isBusy
    }

    private fun renderLog(entries: List<PipelineLogEntry>) {
        logView.text = entries.joinToString("\n\n") { entry ->
            val mark = when (entry.level) {
                PipelineLogEntry.Level.INFO -> "·"
                PipelineLogEntry.Level.STEP -> "▶"
                PipelineLogEntry.Level.WARN -> "!"
                PipelineLogEntry.Level.ERROR -> "X"
                PipelineLogEntry.Level.JSON -> "{}"
            }
            "$mark ${entry.message}"
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun createContentView(): LinearLayout {
        val padding = dpToPx(20)
        val spacing = dpToPx(12)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            // 제목은 툴바가 맡으므로 본문에는 두지 않는다.
            val inputLayout = TextInputLayout(this@PipelineDebugActivity).apply {
                setHint(R.string.pipeline_command_label)
            }
            commandInput = TextInputEditText(inputLayout.context).apply {
                setText(R.string.pipeline_sample_command)
            }
            inputLayout.addView(commandInput, matchWidthWrapHeight())
            addView(inputLayout, matchWidthWrapHeight().apply { topMargin = spacing })

            // 실행, 듣기, 상태와 처리 경로·시간 줄은 스크롤 없이 첫 화면에 보이도록 위에 둔다.
            runButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_run)
                setOnClickListener { runCommand(commandInput.text?.toString().orEmpty()) }
            }
            addView(runButton, matchWidthWrapHeight().apply { topMargin = spacing })

            listenButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_listen_start)
                setOnClickListener {
                    if (listening) {
                        stopListening()
                    } else if (ContextCompat.checkSelfPermission(
                            this@PipelineDebugActivity, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startListening()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            addView(listenButton, matchWidthWrapHeight().apply { topMargin = spacing })

            stateView = TextView(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_state_idle)
                setTextAppearance(MaterialR.style.TextAppearance_MaterialComponents_Subtitle2)
            }
            addView(stateView, matchWidthWrapHeight().apply { topMargin = spacing })

            cloudStatus = TextView(this@PipelineDebugActivity)
            addView(cloudStatus, matchWidthWrapHeight().apply { topMargin = spacing })
            renderCloudStatus()

            routeView = TextView(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_route_none)
            }
            addView(routeView, matchWidthWrapHeight().apply { topMargin = spacing })

            timingView = TextView(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_timing_none)
            }
            addView(timingView, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 켜면 가짜 LLM 대신 온디바이스 Qwen3 를 쓴다. 실행모듈·화면관찰은 아직
            // 가짜 그대로다(공규태 파트 대기 중).
            realLlmSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_real_llm)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) prepareRealLlm() else resetPipeline()
                }
            }
            addView(realLlmSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 켜면 가짜 실행모듈 대신 접근성 서비스가 실제로 화면을 조작한다.
            realExecutorSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_real_executor)
            }
            addView(realExecutorSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 켜면 접근성 서비스를 오케스트레이터에 꽂아 화면 검증과 재분석을 거친다.
            // 꺼 두면 위의 기존 경로가 그대로 동작한다.
            orchestratorSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_orchestrator)
            }
            addView(orchestratorSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 어려운 동작(영상 재생·탭·입력)만 클라우드 AI 로 처리한다. 키는 이 기기에만 저장된다.
            cloudSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_cloud_toggle)
                isChecked = cloudSettings.enabled
                setOnCheckedChangeListener { _, checked ->
                    cloudSettings.enabled = checked
                    renderCloudStatus()
                }
            }
            addView(cloudSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            // 유튜브 검색 결과의 N번째 영상을 YouTube Data API 로 바로 연다. 끄면 화면 검색과 스크롤로 찾는다.
            val youtubeSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_youtube_toggle)
                isChecked = cloudSettings.youtubeApiEnabled
                setOnCheckedChangeListener { _, checked ->
                    cloudSettings.youtubeApiEnabled = checked
                    renderYoutubeStatus()
                }
            }
            addView(youtubeSwitch, matchWidthWrapHeight().apply { topMargin = spacing })
            youtubeStatus = TextView(this@PipelineDebugActivity)
            addView(youtubeStatus, matchWidthWrapHeight().apply { topMargin = spacing })
            renderYoutubeStatus()

            // 키·모델 입력은 평소에 필요 없다(키는 빌드에 들어 있다). 펼칠 때만 보여 준다.
            val cloudSection = LinearLayout(this@PipelineDebugActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            addView(Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_cloud_settings_show)
                setOnClickListener {
                    val open = cloudSection.visibility != View.VISIBLE
                    cloudSection.visibility = if (open) View.VISIBLE else View.GONE
                    setText(
                        if (open) R.string.pipeline_cloud_settings_hide else R.string.pipeline_cloud_settings_show,
                    )
                }
            }, matchWidthWrapHeight().apply { topMargin = spacing })
            addView(cloudSection, matchWidthWrapHeight())

            val keyLayout = TextInputLayout(this@PipelineDebugActivity).apply {
                setHint(R.string.pipeline_cloud_key_hint)
            }
            val keyInput = TextInputEditText(keyLayout.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                // 저장된 키는 화면에 다시 보여 주지 않는다. 비워 두고 저장하면 키를 지운다.
            }
            keyLayout.addView(keyInput, matchWidthWrapHeight())
            cloudSection.addView(keyLayout, matchWidthWrapHeight().apply { topMargin = spacing })

            cloudSection.addView(Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_cloud_key_save)
                setOnClickListener {
                    val typed = keyInput.text?.toString().orEmpty().trim()
                    cloudSettings.apiKey = typed
                    keyInput.setText("")
                    renderCloudStatus()
                    stateView.setText(
                        if (typed.isBlank()) R.string.pipeline_cloud_key_cleared
                        else R.string.pipeline_cloud_key_saved,
                    )
                }
            }, matchWidthWrapHeight().apply { topMargin = spacing })

            val modelLayout = TextInputLayout(this@PipelineDebugActivity).apply {
                setHint(R.string.pipeline_cloud_model_hint)
            }
            val modelInput = TextInputEditText(modelLayout.context).apply {
                setText(cloudSettings.model)
            }
            modelLayout.addView(modelInput, matchWidthWrapHeight())
            cloudSection.addView(modelLayout, matchWidthWrapHeight().apply { topMargin = spacing })

            cloudSection.addView(Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_cloud_model_save)
                setOnClickListener {
                    cloudSettings.model = modelInput.text?.toString().orEmpty()
                    renderCloudStatus()
                    stateView.setText(R.string.pipeline_cloud_model_saved)
                }
            }, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 켜면 음성 최종 결과가 오는 대로 실행 버튼과 같은 경로로 실행한다. 기본은 꺼짐.
            autoRunSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_auto_run)
            }
            addView(autoRunSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            // 다른 앱 위에 뜨는 캡슐. 기본 켜짐이고, 끄면 캡슐만 사라지고 실행은 그대로다.
            overlaySwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_overlay_toggle)
                isChecked = AemxAccessibilityService.isOverlayEnabled()
                setOnCheckedChangeListener { _, checked -> AemxAccessibilityService.setOverlayEnabled(checked) }
            }
            addView(overlaySwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            wakeWordSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_wake_word)
                setOnCheckedChangeListener { _, checked ->
                    gate?.required = checked
                    speech?.useVad = checked
                }
            }
            addView(wakeWordSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            ownerOnlySwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_owner_only)
                setOnCheckedChangeListener { button, checked ->
                    if (checked && !verifier.isEnrolled) {
                        button.isChecked = false
                        stateView.setText(R.string.pipeline_owner_not_enrolled)
                        return@setOnCheckedChangeListener
                    }
                    speech?.requireOwnerVoice = checked
                    if (checked) lifecycleScope.launch(Dispatchers.IO) { verifier.prepare() }
                }
            }
            addView(ownerOnlySwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            enrollButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_enroll)
                setOnClickListener { beginEnrollment() }
            }
            addView(enrollButton, matchWidthWrapHeight().apply { topMargin = spacing })

            logScroll = ScrollView(this@PipelineDebugActivity)
            logView = TextView(this@PipelineDebugActivity).apply {
                textSize = 12f
                setTextIsSelectable(true)
            }
            logScroll.addView(logView, matchWidthWrapHeight())
        }

        // 컨트롤은 세로 스크롤 하나에 넣고, 로그는 그 아래에 남겨 높이 일부를 차지하게 한다.
        val controlsScroll = ScrollView(this).apply { addView(controls, matchWidthWrapHeight()) }

        // 로그는 기본 180dp 로 아래에 두고, 토글로 화면 절반까지 늘린다. 조작 영역은 남은 높이를 쓴다.
        var logExpanded = false
        val logToggle = Button(this).apply {
            setText(R.string.pipeline_log_expand)
            setOnClickListener {
                logExpanded = !logExpanded
                val height = if (logExpanded) resources.displayMetrics.heightPixels / 2 else dpToPx(LOG_COLLAPSED_DP)
                logScroll.layoutParams = logLayoutParams(height, spacing, padding)
                setText(if (logExpanded) R.string.pipeline_log_collapse else R.string.pipeline_log_expand)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(logToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(padding, 0, padding, 0)
            })
            addView(logScroll, logLayoutParams(dpToPx(LOG_COLLAPSED_DP), spacing, padding))
        }
    }

    private fun logLayoutParams(height: Int, top: Int, side: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(side, top, side, side)
        }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * 실행 결과를 받아 보는 통로를 연다.
     *
     * 접근성 서비스는 이 화면보다 오래 살아 있다. 떼어 내지 않으면 화면이 사라진 뒤에도
     * 콜백이 남아 죽은 뷰를 건드린다.
     */
    override fun onStart() {
        super.onStart()
        visible = true
        setPlanObserverIfIdle(deviceObserver)
    }

    /** 우회 경로(runOnDevice)의 결과를 상태줄에 띄우는 콜백. 오케스트레이터 경로는 이걸 거치지 않는다. */
    private val deviceObserver: (PlanOutcome) -> Unit = { outcome ->
        runOnUiThread {
            endTiming(
                TimingPath.DIRECT,
                when (outcome) {
                    is PlanOutcome.Completed -> CommandOutcome.SUCCESS
                    is PlanOutcome.Failed -> CommandOutcome.FAILED
                },
            )
            stateView.text = when (outcome) {
                is PlanOutcome.Completed -> getString(R.string.pipeline_device_done)
                is PlanOutcome.Failed -> getString(
                    R.string.pipeline_device_step_failed,
                    outcome.stepIndex + 1,
                    outcome.message,
                )
            }
        }
    }

    /**
     * 오케스트레이터 경로가 실행 중에 쥐고 있는 콜백은 건드리지 않는다. 지우면 그 step 의
     * 끝 알림을 못 받아 25초 뒤 시간 초과가 난다. 어댑터가 끝나면 [visible] 을 보고 되돌린다.
     */
    private fun setPlanObserverIfIdle(callback: ((PlanOutcome) -> Unit)?) {
        val executor = deviceExecutor
        if (executor != null) executor.setObserverIfIdle(callback)
        else AemxAccessibilityService.setPlanObserver(callback)
    }

    override fun onStop() {
        visible = false
        setPlanObserverIfIdle(null)
        super.onStop()
    }

    override fun onDestroy() {
        deviceOrchestrator?.cancel()
        announcer?.shutdown()
        announcer = null
        deviceScope.cancel()
        verifier.release()
        speech?.release()
        speech = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PipelineDebug"

        /** 로그 영역의 기본 높이(dp). 접혀 있을 때도 이만큼은 보인다. 펼치면 화면 절반까지 늘어난다. */
        private const val LOG_COLLAPSED_DP = 180

        /** 등록에 쓸 수 있는 최소 길이. 0.8초(16kHz). */
        private const val MIN_ENROLL_SAMPLES = 12_800
    }
}

/**
 * 접근성 서비스가 받는 형식으로 다시 직렬화한다.
 *
 * 모델 원문을 그대로 넘기지 않는 이유는 설명이나 코드블록이 섞여 오기 때문이다.
 * 파서가 읽어 낸 계획만 다시 JSON 으로 만들어 넘긴다.
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
