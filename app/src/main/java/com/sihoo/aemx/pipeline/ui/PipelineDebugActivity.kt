package com.sihoo.aemx.pipeline.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
import com.sihoo.aemx.pipeline.PipelineConfig
import com.sihoo.aemx.pipeline.PipelineLogEntry
import com.sihoo.aemx.pipeline.PipelineOrchestrator
import com.sihoo.aemx.pipeline.PipelineState
import com.sihoo.aemx.pipeline.fake.FakeAppResolver
import com.sihoo.aemx.pipeline.fake.FakePlanGenerator
import com.sihoo.aemx.pipeline.fake.FakeScreenObserver
import com.sihoo.aemx.pipeline.fake.ScriptedActionExecutor
import com.sihoo.aemx.pipeline.llm.LlamaPlanGenerator
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.stt.SherpaSpeechSource
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.PlanGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var llmPlanner: LlamaPlanGenerator? = null
    private lateinit var listenButton: Button
    private var speech: SherpaSpeechSource? = null
    private var listening = false
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var orchestrator: PipelineOrchestrator
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        resetPipeline()
    }

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
            lifecycleScope.launch {
                it.results.collect { result -> render(result) }
            }
        }

        // 준비하는 동안 다시 눌리면 녹음이 두 번 열린다. 버튼을 막아 둔다.
        listenButton.isEnabled = false
        stateView.setText(R.string.pipeline_stt_preparing)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { source.prepare() }
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

    private fun render(result: SpeechResult) {
        when (result) {
            // 중간 결과는 보여 주기만 한다. 아직 말이 끝나지 않았다.
            is SpeechResult.Partial -> stateView.text = "듣는 중... ${result.text}"
            is SpeechResult.Final -> {
                commandInput.setText(result.text)
                stateView.text = "들은 말: ${result.text}"
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

        fun aladin(vararg texts: String) = ScreenSnapshot(
            packageName = "com.example.fake.aladin",
            className = "AladinScreen",
            texts = texts.toList()
        )

        // 시나리오: 로그인 버튼을 눌렀는데 아무 반응이 없어(2번째 = null)
        // 재분석이 돌고, 새 계획의 "회원 로그인"으로 끝까지 진행된다.
        val executor = ScriptedActionExecutor(
            observer = observer,
            screensAfterEach = listOf(
                aladin("알라딘", "로그인", "베스트셀러"),                    // 앱 실행
                null,                                                      // 로그인 눌렀으나 반응 없음
                aladin("아이디", "비밀번호", "로그인"),                      // 회원 로그인 눌러 폼 진입
                aladin("아이디", "demo_user", "비밀번호", "로그인"),
                aladin("아이디", "demo_user", "비밀번호", "••••", "로그인"),
                aladin("알라딘", "환영합니다", "장바구니", "베스트셀러")        // 로그인 성공
            )
        )

        val effectivePlanner = planner ?: FakePlanGenerator(
            responses = listOf(
                """{"app": "알라딘", "steps": [{"action": "open_app", "target": "알라딘"}, {"action": "tap", "target": "로그인"}, {"action": "input_credential", "target": "id"}, {"action": "input_credential", "target": "password"}, {"action": "tap", "target": "로그인"}]}""",
                """{"app": "알라딘", "steps": [{"action": "tap", "target": "회원 로그인"}, {"action": "input_credential", "target": "id"}, {"action": "input_credential", "target": "password"}, {"action": "tap", "target": "로그인"}]}"""
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
            config = PipelineConfig(quietPeriodMs = 300)
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

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_title)
                setTextAppearance(MaterialR.style.TextAppearance_MaterialComponents_Headline6)
            }, matchWidthWrapHeight())

            addView(TextView(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_description)
            }, matchWidthWrapHeight().apply { topMargin = spacing })

            val inputLayout = TextInputLayout(this@PipelineDebugActivity).apply {
                setHint(R.string.pipeline_command_label)
            }
            commandInput = TextInputEditText(inputLayout.context).apply {
                setText(R.string.pipeline_sample_command)
            }
            inputLayout.addView(commandInput, matchWidthWrapHeight())
            addView(inputLayout, matchWidthWrapHeight().apply { topMargin = spacing })

            // AEMX: 켜면 가짜 LLM 대신 온디바이스 Qwen3 를 쓴다. 실행모듈·화면관찰은 아직
            // 가짜 그대로다(공규태 파트 대기 중).
            realLlmSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_real_llm)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) prepareRealLlm() else resetPipeline()
                }
            }
            addView(realLlmSwitch, matchWidthWrapHeight().apply { topMargin = spacing })

            runButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_run)
                setOnClickListener {
                    val command = commandInput.text?.toString().orEmpty()
                    // 진짜 LLM 은 준비 비용이 커서 매번 다시 만들지 않고 계속 쓴다.
                    if (!realLlmSwitch.isChecked) resetPipeline()
                    orchestrator.submit(command)
                }
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

            logScroll = ScrollView(this@PipelineDebugActivity)
            logView = TextView(this@PipelineDebugActivity).apply {
                textSize = 12f
                setTextIsSelectable(true)
            }
            logScroll.addView(logView, matchWidthWrapHeight())
            addView(
                logScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = spacing
                    gravity = Gravity.TOP
                }
            )
        }
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        speech?.release()
        speech = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PipelineDebug"
    }
}
