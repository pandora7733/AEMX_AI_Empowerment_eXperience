package com.sihoo.aemx.pipeline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.pipeline.PipelineLogEntry
import com.sihoo.aemx.pipeline.app.PipelineCore
import com.sihoo.aemx.pipeline.app.VoiceService
import com.sihoo.aemx.pipeline.llm.CloudSettings
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 개발용 화면. 파이프라인 코어([PipelineCore])를 보여 주고 조작만 한다.
 *
 * 호출어, 음성 인식, 계획, 실행은 모두 코어와 [VoiceService] 가 맡는다. 이 화면을 닫아도 듣기는 계속된다.
 * 화면은 코어의 흐름을 구독해 글자만 바꾸고, 버튼은 코어 함수를 부른다.
 *
 * 명령을 adb 로 넘길 수 있다: `--es cmd "..."` 는 명령칸에 넣고, `--ez run true` 면 바로 실행한다.
 */
class PipelineDebugActivity : AppCompatActivity() {

    private lateinit var commandInput: TextInputEditText
    private lateinit var runButton: Button
    private lateinit var realLlmSwitch: CheckBox
    private lateinit var realExecutorSwitch: CheckBox
    private lateinit var orchestratorSwitch: CheckBox
    private lateinit var cloudSwitch: CheckBox
    private lateinit var cloudStatus: TextView
    private lateinit var routeView: TextView
    private lateinit var youtubeStatus: TextView
    private lateinit var timingView: TextView
    private lateinit var listenButton: Button
    private lateinit var wakeWordSwitch: CheckBox
    private lateinit var ownerOnlySwitch: CheckBox
    private lateinit var autoRunSwitch: CheckBox
    private lateinit var overlaySwitch: CheckBox
    private lateinit var enrollButton: Button
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val cloudSettings by lazy { CloudSettings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PipelineCore.init(applicationContext)
        // 입력칸이 처음부터 포커스를 받아 키보드가 뜨면 캡슐 위치가 키보드 위로 밀린다. 키보드는 사용자가 누를 때만 올린다.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(createContentView())
        observeCore()
        // 처음 만들 때만 adb 명령을 읽는다. 화면을 다시 만들 때 같은 명령이 또 실행되지 않게 한다.
        if (savedInstanceState == null) handleAdbCommand(intent)
        // 문자 보내기가 받는 사람을 연락처에서 찾는다. 거절해도 다른 기능에는 영향이 없다.
        if (savedInstanceState == null && ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CONTACTS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAdbCommand(intent)
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) VoiceService.start(this) else stateView.setText(R.string.pipeline_stt_denied)
    }

    /** 코어의 흐름을 화면 글자에 옮긴다. 화면이 떠 있는 동안만 구독한다. */
    private fun observeCore() {
        PipelineCore.status.onEach { stateView.text = it }.launchIn(lifecycleScope)
        PipelineCore.log.onEach { renderLog(it) }.launchIn(lifecycleScope)
        PipelineCore.planJson.onEach { if (it.isNotEmpty()) logView.text = it }.launchIn(lifecycleScope)
        PipelineCore.route.onEach { routeView.text = it }.launchIn(lifecycleScope)
        PipelineCore.timing.onEach { timingView.text = it }.launchIn(lifecycleScope)
        PipelineCore.listening.onEach { on ->
            listenButton.setText(if (on) R.string.pipeline_listen_stop else R.string.pipeline_listen_start)
        }.launchIn(lifecycleScope)
        PipelineCore.runEnabled.onEach { runButton.isEnabled = it }.launchIn(lifecycleScope)
        PipelineCore.enrolling.onEach { enrollButton.isEnabled = !it }.launchIn(lifecycleScope)
        PipelineCore.heard.onEach { heard ->
            if (heard != null) commandInput.setText(heard)
        }.launchIn(lifecycleScope)
    }

    /** adb 로 넘긴 명령을 받는다. `--es cmd "..."` 는 명령칸에 넣고, `--ez run true` 면 바로 실행한다. */
    /**
     * adb 로 넘어온 명령(개발 훅). cmd 또는 cmd_b64 가 있으면 명령 입력칸에 채우고, run 이 true 면 실행한다.
     * 쓴 extra 는 지운다. 명령 내용은 로그에 남기지 않는다(길이만).
     */
    private fun handleAdbCommand(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_LISTEN_DEMO)) {
            val on = intent.getBooleanExtra(EXTRA_LISTEN_DEMO, false)
            intent.removeExtra(EXTRA_LISTEN_DEMO)
            PipelineCore.demoListening(on)
            Log.i(TAG, "듣는 중 캡슐 시연 훅(켜짐=$on)")
            return
        }
        val command = AdbCommand.resolve(intent.getStringExtra(EXTRA_CMD), intent.getStringExtra(EXTRA_CMD_B64)) ?: return
        val run = intent.getBooleanExtra(EXTRA_RUN, false)
        intent.removeExtra(EXTRA_CMD)
        intent.removeExtra(EXTRA_CMD_B64)
        intent.removeExtra(EXTRA_RUN)
        commandInput.setText(command)
        Log.i(TAG, "adb 명령을 받았다(${command.length}자, 실행=$run)")
        if (run) PipelineCore.runCommand(command)
    }

    private fun onListenClicked() {
        if (PipelineCore.listening.value) {
            VoiceService.stop(this)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            VoiceService.start(this)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                setOnClickListener { PipelineCore.runCommand(commandInput.text?.toString().orEmpty()) }
            }
            addView(runButton, matchWidthWrapHeight().apply { topMargin = spacing })
            listenButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_listen_start)
                setOnClickListener { onListenClicked() }
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
            // AEMX: 켜면 가짜 LLM 대신 온디바이스 Qwen3 를 쓴다.
            realLlmSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_real_llm)
                isChecked = PipelineCore.prefs.realLlm
                setOnCheckedChangeListener { _, checked -> PipelineCore.setRealLlm(checked) }
            }
            addView(realLlmSwitch, matchWidthWrapHeight().apply { topMargin = spacing })
            // AEMX: 켜면 가짜 실행모듈 대신 접근성 서비스가 실제로 화면을 조작한다.
            realExecutorSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_real_executor)
                isChecked = PipelineCore.prefs.realExecutor
                setOnCheckedChangeListener { _, checked -> PipelineCore.prefs.realExecutor = checked }
            }
            addView(realExecutorSwitch, matchWidthWrapHeight().apply { topMargin = spacing })
            // AEMX: 켜면 접근성 서비스를 오케스트레이터에 꽂아 화면 검증과 재분석을 거친다.
            orchestratorSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_use_orchestrator)
                isChecked = PipelineCore.prefs.orchestrator
                setOnCheckedChangeListener { _, checked -> PipelineCore.prefs.orchestrator = checked }
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
            // AEMX: 켜면 음성 최종 결과가 오는 대로 실행 버튼과 같은 경로로 실행한다.
            autoRunSwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_auto_run)
                isChecked = PipelineCore.prefs.autoRun
                setOnCheckedChangeListener { _, checked -> PipelineCore.setAutoRun(checked) }
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
                isChecked = PipelineCore.prefs.wakeWord
                setOnCheckedChangeListener { _, checked -> PipelineCore.setWakeWord(checked) }
            }
            addView(wakeWordSwitch, matchWidthWrapHeight().apply { topMargin = spacing })
            ownerOnlySwitch = CheckBox(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_owner_only)
                isChecked = PipelineCore.prefs.ownerOnly
                setOnCheckedChangeListener { button, checked ->
                    if (checked && !PipelineCore.isEnrolled) {
                        button.isChecked = false
                        stateView.setText(R.string.pipeline_owner_not_enrolled)
                        return@setOnCheckedChangeListener
                    }
                    PipelineCore.setOwnerOnly(checked)
                }
            }
            addView(ownerOnlySwitch, matchWidthWrapHeight().apply { topMargin = spacing })
            enrollButton = Button(this@PipelineDebugActivity).apply {
                setText(R.string.pipeline_enroll)
                setOnClickListener { PipelineCore.beginEnrollment() }
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

    private fun renderCloudStatus() {
        cloudStatus.setText(
            when {
                !cloudSettings.enabled -> R.string.pipeline_cloud_status_off
                !cloudSettings.hasKey -> R.string.pipeline_cloud_status_no_key
                else -> R.string.pipeline_cloud_status_on
            },
        )
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

    private fun logLayoutParams(height: Int, top: Int, side: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(side, top, side, side)
        }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        /** 로그 영역의 기본 높이(dp). 접혀 있을 때도 이만큼은 보인다. 펼치면 화면 절반까지 늘어난다. */
        private const val LOG_COLLAPSED_DP = 180

        /** adb 로 명령을 넘기는 추가 항목 이름. */
        private const val TAG = "PipelineDebug"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_CMD_B64 = "cmd_b64"
        private const val EXTRA_RUN = "run"
        /** 음성 없이 듣는 중 캡슐을 띄우거나 끄는 검증용 extra. */
        private const val EXTRA_LISTEN_DEMO = "listen_demo"
    }
}
