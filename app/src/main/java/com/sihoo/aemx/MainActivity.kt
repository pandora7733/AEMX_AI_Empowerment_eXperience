package com.sihoo.aemx

import android.Manifest
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.sihoo.aemx.accessibility.AccessibilityState
import com.sihoo.aemx.accessibility.AccessibilityStatus
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.debug.DebugPanelView
import com.sihoo.aemx.debug.SpeechTrace
import com.sihoo.aemx.pipeline.PipelineRedaction
import com.sihoo.aemx.pipeline.app.PipelineCore
import com.sihoo.aemx.pipeline.llm.SearchApp
import com.sihoo.aemx.pipeline.app.QuitPrefs
import com.sihoo.aemx.pipeline.app.VoiceService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 앱의 첫 화면. 처음 켜면 필요한 설정을 한 번에 한 단계씩 안내하고, 다 끝나면 홈으로 간다.
 *
 * 단계는 마이크, 알림, 접근성 서비스, 화면 위 캡슐, 연락처, 모델 준비 순서다. 이미 된 단계는 건너뛰고,
 * 현재 단계가 끝나면 자동으로 다음으로 넘어간다. 알림과 연락처는 나중에 할 수 있고, 마이크와 접근성은 필수다.
 * 한 번에 단계 화면이나 홈 중 하나만 보이고([FirstRunScreenPolicy] 가 고른다), 설정 톱니로 단계를 다시 본다.
 *
 * 듣기와 실행은 [PipelineCore] 와 [VoiceService] 가 맡는다. 권한 흐름과 접근성 판정은 그대로 두고 화면만 바꿨다.
 */
/** 단계 이름. 순서와 값은 [FirstRunStep] 에서 정해 테스트로 고정한다. */
private typealias Item = FirstRunStep

class MainActivity : AppCompatActivity() {

    /** 나중에 할 수 있는 단계. 접근성과 마이크는 필수라 건너뛰기를 두지 않는다. */
    private val skippable = setOf(Item.NOTIFY, Item.CONTACTS)
    private val handler = Handler(Looper.getMainLooper())

    /** 건너뛴 단계. 완료와 같이 다음으로 넘어가지만 준비 상태에는 세지 않는다. */
    private val skipped = mutableSetOf<Item>()

    /** 지금 보여 주는 단계의 순서(0부터). */
    private var cursor = 0

    /** 마지막으로 본 완료 항목. 버튼 눌림에서 쓴다. */
    private var lastDone: Set<Item> = emptySet()

    /** 홈에 한 번 들어갔으면 연결된 동안 단계로 돌아가지 않는다. */
    private var homeReached = false

    /** 홈의 톱니로 들어온 단계 화면. 이때는 이전·다음과 홈으로를 쓴다. */
    private var reviewing = false

    /** 예시 줄을 처음 한 번만 순서대로 나타나게 하는 표시. */
    private var examplesShown = false

    /** 첫 실행 안내(여섯 단계를 모두 보여 주는 것)가 진행 중인지. 끝나면 저장하고 다시 보이지 않는다. */
    private var walkthrough = false

    private lateinit var root: FrameLayout
    private lateinit var stepsView: ScrollView
    private lateinit var homeView: ScrollView
    private lateinit var progressDots: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var iconGlyph: GlyphView
    private lateinit var stepTitle: TextView
    private lateinit var stepDesc: TextView
    private lateinit var accessSteps: LinearLayout
    private lateinit var stepStatus: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var modelBar: ProgressBar
    private lateinit var modelMessage: TextView
    private lateinit var primaryButton: Button
    private lateinit var skipButton: TextView
    private lateinit var reviewNav: LinearLayout
    private lateinit var homeBackButton: TextView
    private lateinit var micOrb: MicOrbView
    private lateinit var pill: LinearLayout
    private lateinit var pillText: TextView
    private lateinit var pillDot: View
    private lateinit var listenCaption: TextView
    private lateinit var cardValues: List<TextView>
    private lateinit var exampleRows: List<LinearLayout>
    private lateinit var recentRows: List<LinearLayout>
    private lateinit var statusView: TextView
    private lateinit var searchAppValue: TextView
    private var pillAnimator: ValueAnimator? = null

    /** 디버그 빌드인지. 디버깅 모드 스위치는 디버그 빌드에만 보인다. */
    private val debuggable by lazy { PipelineRedaction.isDebuggable(this) }

    /** 디버깅 모드가 켜져 있는지. 저장해 두고 다음 실행에도 이어 간다. */
    private var debugMode = false
    private var debugPanel: DebugPanelView? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.RECORD_AUDIO, granted)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
    }
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.READ_CONTACTS, granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 시작 화면은 준비 화면 위에서만 짧게 보인다. 모델이 먼저 준비되면 바로 내린다.
        // 첫 실행의 1.2GB 다운로드 동안 붙잡아 두면 단계 화면이 가려지므로 최대 시간을 둔다.
        val splash = installSplashScreen()
        val startedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - startedAt < SPLASH_MAX_MS && !PipelineCore.modelReady.value
        }
        // 화면을 열었으면 완전히 종료한 상태는 끝난다. 아래의 준비와 자동 듣기가 평소처럼 돈다.
        QuitPrefs.setQuit(this, false)
        PipelineCore.init(applicationContext)
        walkthrough = !prefs().getBoolean(KEY_ONBOARDING_SEEN, false)
        debugMode = debuggable && prefs().getBoolean(KEY_DEBUG_MODE, false)
        SpeechTrace.enabled = debugMode
        // 화면 회전이나 프로세스 복구에서도 있던 자리를 잃지 않게 되살린다.
        savedInstanceState?.let { saved ->
            cursor = saved.getInt(STATE_CURSOR, 0)
            reviewing = saved.getBoolean(STATE_REVIEWING, false)
            homeReached = saved.getBoolean(STATE_HOME_REACHED, false)
            saved.getStringArray(STATE_SKIPPED)?.forEach { name -> runCatching { skipped += Item.valueOf(name) } }
        }
        buildViews()
        observeCore()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURSOR, cursor)
        outState.putBoolean(STATE_REVIEWING, reviewing)
        outState.putBoolean(STATE_HOME_REACHED, homeReached)
        outState.putStringArray(STATE_SKIPPED, skipped.map { it.name }.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        // 종료한 뒤에 남아 있던 화면으로 돌아온 경우에도 다시 쓰는 것으로 본다.
        if (QuitPrefs.isQuit(this)) QuitPrefs.setQuit(this, false)
        VoiceService.retryIfSilenced(this)
        VoiceService.resumeIfIdle(this)
        refresh()
        handler.removeCallbacks(accessPoll)
        if (accessibilityState() != AccessibilityState.Connected) handler.postDelayed(accessPoll, ACCESS_POLL_MS)
        // 홈이 보이는 동안만 알약을 흐리게 한다.
        if (::homeView.isInitialized && homeView.visibility == View.VISIBLE) startPill()
        if (debugMode) debugPanel?.start(lifecycleScope)
    }

    override fun onPause() {
        // 화면을 떠나면 폴링과 알약 애니메이션, 디버깅 패널의 측정을 멈춘다. 돌아오면 onResume 이 다시 맞춘다.
        handler.removeCallbacks(accessPoll)
        stopPill()
        debugPanel?.stop()
        super.onPause()
    }

    /**
     * 권한 요청 결과. 허용되지 않았고 시스템이 다시 묻지 않으면(영구 거부) 설정 화면의 앱 권한을 연다.
     * 그냥 거부한 것이면 다음 누름에 다시 묻는다.
     */
    private fun onPermissionResult(permission: String, granted: Boolean) {
        refresh()
        if (!granted && !shouldShowRequestPermissionRationale(permission)) openAppDetails()
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun observeCore() {
        PipelineCore.modelReady.onEach { refresh() }.launchIn(lifecycleScope)
        PipelineCore.modelFailed.onEach { refresh() }.launchIn(lifecycleScope)
        PipelineCore.modelMessage.onEach { message ->
            modelMessage.text = message
            refresh()
        }.launchIn(lifecycleScope)
        PipelineCore.status.onEach { statusView.text = it }.launchIn(lifecycleScope)
        PipelineCore.recent.onEach { runs -> renderRecent(runs) }.launchIn(lifecycleScope)
        PipelineCore.listening.onEach { on -> renderListening(on) }.launchIn(lifecycleScope)
    }

    // ---------------------------------------------------------------- 화면 만들기

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.onb_bg)) }
        // 처음에는 둘 다 숨긴다. 어느 쪽을 보일지는 refresh() 가 한 번에 하나만 고른다.
        stepsView = ScrollView(this).apply { addView(buildSteps()); visibility = View.GONE }
        homeView = ScrollView(this).apply { addView(buildHome()); visibility = View.GONE }
        root.addView(stepsView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(homeView, FrameLayout.LayoutParams(MATCH, MATCH))
        // 맨 위 여백은 상태 표시줄 높이만큼 준다. 로고와 AEMX 가 시계와 겹치지 않게 한다(단계 화면과 홈 모두).
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            stepsView.setPadding(0, top, 0, 0)
            homeView.setPadding(0, top, 0, 0)
            insets
        }
        setContentView(root)
    }

    private fun buildSteps(): LinearLayout {
        val pad = dp(24)
        return column().apply {
            setPadding(pad, dp(20), pad, pad)

            // 위쪽: 로고와 AEMX, 오른쪽에 진행 점 여섯 개와 숫자.
            val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_aemx_logo)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            header.addView(text(20f, R.color.onb_ink, bold = true).apply { setText(R.string.home_title_app) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            val spacer = View(this@MainActivity)
            header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            progressDots = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(progressDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            progressText = text(13f, R.color.onb_muted, bold = true)
            header.addView(progressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(header, matchWrap())

            // 가운데: 큰 아이콘 카드와 제목, 설명.
            val card = FrameLayout(this@MainActivity).apply {
                background = shape(R.color.onb_chip, 20)
            }
            iconGlyph = GlyphView(this@MainActivity, null, Glyph.MIC, getColor(R.color.onb_plum))
            card.addView(iconGlyph, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            addView(card, LinearLayout.LayoutParams(dp(120), dp(120)).apply { topMargin = dp(32); gravity = Gravity.CENTER_HORIZONTAL })

            stepTitle = text(26f, R.color.onb_ink, bold = true).apply { gravity = Gravity.CENTER }
            addView(stepTitle, matchWrap().apply { topMargin = dp(24) })
            stepDesc = text(16f, R.color.onb_muted).apply { gravity = Gravity.CENTER }
            addView(stepDesc, matchWrap().apply { topMargin = dp(12) })

            // 접근성 단계의 세 줄 안내(번호 1, 2, 3).
            accessSteps = column().apply { visibility = View.GONE }
            listOf(R.string.step_access_1, R.string.step_access_2, R.string.step_access_3).forEach { res ->
                accessSteps.addView(text(16f, R.color.onb_ink).apply { setText(res) }, matchWrap().apply { topMargin = dp(8) })
            }
            addView(accessSteps, matchWrap().apply { topMargin = dp(16) })

            stepStatus = text(14f, R.color.onb_green_ink, bold = true).apply { gravity = Gravity.CENTER }
            addView(stepStatus, matchWrap().apply { topMargin = dp(12) })

            // 모델 단계: 가로 진행 막대와 상태 문장.
            modelBox = column().apply { visibility = View.GONE }
            modelBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
            modelBox.addView(modelBar, matchWrap())
            modelMessage = text(15f, R.color.onb_muted).apply { gravity = Gravity.CENTER }
            modelBox.addView(modelMessage, matchWrap().apply { topMargin = dp(8) })
            addView(modelBox, matchWrap().apply { topMargin = dp(20) })

            // 맨 아래: 큰 주요 버튼(높이 56, 모서리 28, 그라데이션). 글자가 커도 가려지지 않게 높이는 최소로 둔다.
            primaryButton = bigButton().apply { setOnClickListener { cursorItem()?.let(::onPrimary) } }
            addView(primaryButton, matchWrap().apply { topMargin = dp(32) })
            skipButton = text(16f, R.color.onb_muted).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setOnClickListener {
                    cursorItem()?.let { skipped += it }
                    if (walkthrough && !reviewing) nextStep() else refresh()
                }
            }
            addView(skipButton, matchWrap().apply { topMargin = dp(4) })

            // 설정 다시 보기에서만 보이는 이전·다음.
            reviewNav = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
            reviewNav.addView(bigButton().apply {
                setText(R.string.step_prev)
                setOnClickListener { moveCursor(-1) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            reviewNav.addView(bigButton().apply {
                setText(R.string.step_next)
                setOnClickListener { moveCursor(1) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reviewNav, matchWrap().apply { topMargin = dp(12) })
            homeBackButton = text(16f, R.color.onb_plum, bold = true).apply {
                setText(R.string.home_back)
                gravity = Gravity.CENTER
                minHeight = dp(48)
                visibility = View.GONE
                setOnClickListener { showHome() }
            }
            addView(homeBackButton, matchWrap().apply { topMargin = dp(8) })
        }
    }

    private fun buildHome(): LinearLayout {
        val pad = dp(24)
        return column().apply {
            setPadding(pad, dp(20), pad, pad)

            // 위: 로고와 AEMX, 오른쪽 끝에 톱니(설정 다시 보기).
            val top = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_aemx_logo)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            top.addView(text(22f, R.color.onb_ink, bold = true).apply { setText(R.string.home_title_app) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            top.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(FrameLayout(this@MainActivity).apply {
                isClickable = true
                contentDescription = getString(R.string.home_settings)
                setOnClickListener { openReview() }
                addView(GlyphView(this@MainActivity, null, Glyph.GEAR, getColor(R.color.onb_muted)),
                    FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(top, matchWrap())

            // 상태 알약: 초록 점과 "호출을 기다리고 있어요". 2.4초 주기로 살짝 흐려졌다 돌아온다.
            pill = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(R.color.onb_green_pill, 15)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            pillDot = View(this@MainActivity).apply { background = oval(R.color.onb_green) }
            pill.addView(pillDot, LinearLayout.LayoutParams(dp(8), dp(8)))
            pillText = text(13f, R.color.onb_green_ink, bold = true).apply { setText(R.string.home_idle) }
            pill.addView(pillText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })

            // 가운데: 마이크 세 겹 원. 누르면 호출어 듣기를 켜고 끈다.
            micOrb = MicOrbView(this@MainActivity).apply {
                contentDescription = getString(R.string.main_listen_start)
                setOnClickListener { onListenClicked() }
            }
            // 바깥 원은 숨 쉬며 6% 커지므로 칸을 그만큼 넉넉히 준다. 칸보다 큰 그림은 잘리기 때문이다.
            addView(micOrb, LinearLayout.LayoutParams(dp(MicOrbView.FRAME_DP.toInt()), dp(MicOrbView.FRAME_DP.toInt()))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
            listenCaption = text(19f, R.color.onb_ink, bold = true).apply { gravity = Gravity.CENTER; setText(R.string.home_say) }
            addView(listenCaption, matchWrap().apply { topMargin = dp(4) })
            addView(text(13.5f, R.color.onb_hint).apply { gravity = Gravity.CENTER; setText(R.string.home_sub) }, matchWrap().apply { topMargin = dp(4) })

            // 상태 카드 넷(2행 2열): 마이크, 접근성, 백그라운드, 음성 모델. 누르면 그 설정으로 간다.
            val values = mutableListOf<TextView>()
            val labels = listOf(
                R.string.home_status_mic, R.string.home_status_access,
                R.string.home_status_background, R.string.home_status_model,
            )
            val grid = column()
            labels.chunked(2).forEachIndexed { row, pair ->
                val line = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { col, label ->
                    val index = row * 2 + col
                    val card = column().apply {
                        background = shape(R.color.onb_card, 16)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        isClickable = true
                        setOnClickListener { cardAction(index) }
                    }
                    card.addView(text(12f, R.color.onb_muted, bold = true).apply { setText(label) }, matchWrap())
                    val value = text(13.5f, R.color.onb_green_ink, bold = true)
                    card.addView(value, matchWrap().apply { topMargin = dp(6) })
                    values += value
                    line.addView(card, LinearLayout.LayoutParams(0, dp(58), 1f).apply { if (col > 0) marginStart = dp(8) })
                }
                grid.addView(line, matchWrap().apply { if (row > 0) topMargin = dp(8) })
            }
            cardValues = values
            addView(grid, matchWrap().apply { topMargin = dp(16) })

            // 예시 줄 셋.
            addView(text(15f, R.color.onb_ink, bold = true).apply { setText(R.string.home_examples_title) }, matchWrap().apply { topMargin = dp(20) })
            val rows = mutableListOf<LinearLayout>()
            listOf(
                R.string.home_example_1 to Glyph.PLAY,
                R.string.home_example_2 to Glyph.SUN,
                R.string.home_example_3 to Glyph.BUBBLE,
            ).forEach { (res, glyph) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(R.color.onb_card, 14)
                    setPadding(dp(10), 0, dp(14), 0)
                }
                row.addView(FrameLayout(this@MainActivity).apply {
                    background = shape(R.color.onb_chip, 10)
                    addView(GlyphView(this@MainActivity, null, glyph, getColor(R.color.onb_plum)),
                        FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                row.addView(text(14.5f, R.color.onb_ink, bold = false).apply { setText(res) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                rows += row
                addView(row, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(6) })
            }
            exampleRows = rows

            // 최근 실행 두 줄.
            addView(text(15f, R.color.onb_ink, bold = true).apply { setText(R.string.home_recent_title) }, matchWrap().apply { topMargin = dp(20) })
            val recent = mutableListOf<LinearLayout>()
            repeat(RECENT_SLOTS) {
                val line = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(View(this@MainActivity).apply { background = oval(R.color.onb_green) }, LinearLayout.LayoutParams(dp(8), dp(8)))
                line.addView(text(14f, R.color.onb_ink).apply { tag = "name" },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                line.addView(text(12.5f, R.color.onb_hint).apply { tag = "time" },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                recent += line
                addView(line, matchWrap().apply { topMargin = dp(8) })
            }
            recentRows = recent
            renderRecent(PipelineCore.recent.value)

            // 기본 검색 앱. 앱을 말하지 않은 검색이 어디로 가는지 보여 주고, 누르면 바꾼다.
            addView(text(15f, R.color.onb_ink, bold = true).apply { setText(R.string.home_search_app_title) }, matchWrap().apply { topMargin = dp(20) })
            val searchRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(R.color.onb_card, 14)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                minimumHeight = dp(56)
                isClickable = true
                setOnClickListener { chooseSearchApp() }
            }
            val searchTexts = column()
            searchAppValue = text(15f, R.color.onb_ink, bold = true)
            searchTexts.addView(searchAppValue, matchWrap())
            searchTexts.addView(text(12.5f, R.color.onb_muted).apply { setText(R.string.home_search_app_hint) }, matchWrap().apply { topMargin = dp(2) })
            searchRow.addView(searchTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            searchRow.addView(text(14f, R.color.onb_plum, bold = true).apply { setText(R.string.home_search_app_change) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            addView(searchRow, matchWrap().apply { topMargin = dp(6) })
            renderSearchApp()

            statusView = text(13f, R.color.onb_hint)
            addView(statusView, matchWrap().apply { topMargin = dp(16) })

            // 디버깅 모드 스위치와 패널. 인식된 말이 화면에 그대로 보이므로 디버그 빌드에만 둔다(#16 과 같은 기준).
            if (debuggable) {
                addView(SwitchCompat(this@MainActivity).apply {
                    text = "디버깅 모드"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(getColor(R.color.onb_ink))
                    minHeight = dp(48)
                    isChecked = debugMode
                    setOnCheckedChangeListener { _, on -> setDebugMode(on) }
                }, matchWrap().apply { topMargin = dp(12) })
                debugPanel = DebugPanelView(this@MainActivity).apply {
                    visibility = if (debugMode) View.VISIBLE else View.GONE
                }
                addView(debugPanel, matchWrap().apply { topMargin = dp(4) })
            }
        }
    }

    private fun searchAppLabel(app: SearchApp): Int = when (app) {
        SearchApp.NAVER -> R.string.search_app_naver
        SearchApp.GOOGLE -> R.string.search_app_google
        SearchApp.CHROME -> R.string.search_app_chrome
        SearchApp.BROWSER -> R.string.search_app_browser
    }

    private fun renderSearchApp() {
        if (::searchAppValue.isInitialized) searchAppValue.setText(searchAppLabel(PipelineCore.searchApp))
    }

    /**
     * 기본 검색 앱을 고르는 창. 이 기기에 설치된 앱만 보여 준다(지금 고른 앱은 지워졌더라도 보여 준다).
     * 고르면 바로 저장하고 다음 명령부터 적용된다.
     */
    private fun chooseSearchApp() {
        val launcher = AppLauncher(this)
        val current = PipelineCore.searchApp
        val options = SearchApp.entries.filter { it == current || launcher.isInstalled(it.appName) }
        val labels = options.map { getString(searchAppLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.home_search_app_dialog)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                PipelineCore.searchApp = options[which]
                renderSearchApp()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            // 기본 버튼 색은 어두운 화면에서 잘 보이지 않는다. 홈 화면의 강조색으로 맞춘다.
            .getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.onb_plum))
    }

    /** 디버깅 모드를 켜고 끈다. 켜면 홈 아래에 패널이 붙고, 끄면 모아 둔 인식 내용을 지운다. */
    private fun setDebugMode(on: Boolean) {
        debugMode = on
        prefs().edit().putBoolean(KEY_DEBUG_MODE, on).apply()
        SpeechTrace.enabled = on
        debugPanel?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) debugPanel?.start(lifecycleScope) else debugPanel?.stop()
    }

    // ---------------------------------------------------------------- 상태에 따라 그리기

    /** 완료 여부를 다시 보고 어느 화면을 보일지 고른다. 권한이나 설정이 바뀔 때마다 부른다. */
    private fun refresh() {
        if (!::micOrb.isInitialized) return
        val access = accessibilityState()
        val connected = access == AccessibilityState.Connected
        lastDone = doneItems(access)
        val allFinished = Item.entries.all { it in lastDone || it in skipped }

        when (FirstRunScreenPolicy.choose(reviewing, connected, allFinished, homeReached, walkthrough)) {
            FirstRunScreen.Home -> {
                homeReached = true
                showHome()
            }
            FirstRunScreen.Steps -> {
                // 아직 안 끝난 첫 단계부터 보여 준다(접근성 연결과는 무관하다). 설정 다시 보기 중에는 위치를 두고 본다.
                // 첫 실행 안내 중에는 1단계부터 차례로 보여 준다. 그 밖에는 아직 안 끝난 첫 단계부터다.
                if (!reviewing && !walkthrough) cursor = FirstRunCursor.firstPending(lastDone + skipped)
                showSteps()
                renderSteps(access)
            }
        }
        renderHome()

        // 준비가 끝난 뒤 처음 한 번만 듣기를 켠다. 사용자가 끄면 프로세스가 살아 있는 동안 다시 켜지 않는다.
        if (allFinished && !walkthrough && !autoListenStarted && !PipelineCore.listening.value) {
            autoListenStarted = true
            VoiceService.autoStart(this)
        }
    }

    /** 주요 버튼: 이미 된 단계(첫 실행 안내 중)는 다음으로, 아직 안 된 단계는 그 단계의 동작을 한다. */
    private fun onPrimary(item: Item) {
        if (walkthrough && !reviewing && item in lastDone) nextStep() else onAction(item)
    }

    /** 첫 실행 안내에서 다음 단계로. 마지막 단계에서는 안내를 마치고 홈으로 간다. */
    private fun nextStep() {
        if (cursor >= Item.entries.size - 1) {
            finishWalkthrough()
            return
        }
        cursor++
        renderSteps(accessibilityState())
    }

    /** 첫 실행 안내를 끝낸다. 한 번 끝나면 다음부터는 처음부터 다시 보여 주지 않는다. */
    private fun finishWalkthrough() {
        prefs().edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply()
        walkthrough = false
        homeReached = true
        refresh()
    }

    private fun prefs() = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)

    private fun renderSteps(access: AccessibilityState) {
        val item = Item.entries[cursor]
        val isDone = item in lastDone
        val isSkipped = item in skipped
        val disconnected = item == Item.ACCESS && !isDone && access == AccessibilityState.ConfiguredButDisconnected

        progressText.text = getString(R.string.step_progress, cursor + 1, Item.entries.size)
        renderDots()
        iconGlyph.glyph = iconFor(item)
        iconGlyph.invalidate()
        stepTitle.setText(if (disconnected) R.string.guide_access_disconnected_title else titleFor(item))
        stepDesc.text = when (item) {
            Item.ACCESS -> getString(R.string.guide_access_desc)
            // 백그라운드 단계는 삼성 기기 안내 한 줄을 설명 아래에 붙인다.
            Item.BACKGROUND -> getString(R.string.guide_background_desc) + "\n" + getString(R.string.guide_background_samsung)
            else -> getString(descFor(item))
        }
        accessSteps.visibility = if (item == Item.ACCESS && !isDone) View.VISIBLE else View.GONE

        // 상태 줄: 완료는 초록 글자, 나머지는 안내 글자. 기호로도 구분한다.
        // 첫 실행 안내 중에는 이미 된 단계도 보여 주고 "다음"으로 넘어가게 한다.
        val walking = walkthrough && !reviewing
        stepStatus.text = when {
            isDone && walking && item == Item.OVERLAY -> getString(R.string.step_already_on)
            isDone && walking -> getString(R.string.step_already_ready)
            isDone -> "✓  " + getString(R.string.guide_done)
            isSkipped -> getString(R.string.step_status_skipped)
            disconnected -> getString(R.string.step_access_hint)
            else -> ""
        }
        stepStatus.setTextColor(getColor(if (isDone) R.color.onb_green_ink else R.color.onb_muted))

        // 모델 단계: 준비가 끝났으면 막대를 100%로 채우고 "준비됐어요"를 보인다(첫 실행 안내 중에만 보여 준다).
        modelBox.visibility = if (item == Item.MODEL && (!isDone || walking)) View.VISIBLE else View.GONE
        modelBar.isIndeterminate = !isDone
        if (isDone) {
            modelBar.progress = 100
            modelMessage.setText(R.string.step_model_ready)
        }
        when {
            isDone && walking -> primaryButton.setText(R.string.step_next)
            isDone -> primaryButton.setText(R.string.guide_done)
            // 모델 내려받기가 실패했으면 같은 자리에서 다시 받을 수 있게 한다.
            item == Item.MODEL && !isDone && PipelineCore.modelFailed.value -> primaryButton.setText(R.string.step_model_retry)
            else -> primaryButton.setText(actionFor(item))
        }
        val retryModel = item == Item.MODEL && !isDone && PipelineCore.modelFailed.value
        primaryButton.isEnabled = (isDone && walking) || (!isDone && item != Item.MODEL) || retryModel
        primaryButton.alpha = if (primaryButton.isEnabled) 1f else 0.45f
        skipButton.setText(R.string.step_skip)
        skipButton.visibility = if (item in skippable && !isDone && !isSkipped) View.VISIBLE else View.GONE
        reviewNav.visibility = if (reviewing) View.VISIBLE else View.GONE
        // 홈으로는 모든 단계가 끝났을 때만 보인다. 끝나지 않았는데 누르면 단계 화면으로 되돌아오기 때문이다.
        val allFinished = Item.entries.all { it in lastDone || it in skipped }
        homeBackButton.visibility = if (reviewing && allFinished) View.VISIBLE else View.GONE
    }

    /** 여섯 점: 끝난 단계는 초록, 지금 단계는 길쭉한 자주, 남은 단계는 연한 회색. */
    private fun renderDots() {
        progressDots.removeAllViews()
        Item.entries.forEachIndexed { index, item ->
            val current = index == cursor
            val color = when {
                item in lastDone -> R.color.onb_green
                current -> R.color.onb_plum
                else -> R.color.onb_track
            }
            val width = if (current) dp(22) else dp(8)
            progressDots.addView(View(this).apply { background = pillShape(color) },
                LinearLayout.LayoutParams(width, dp(8)).apply { marginEnd = dp(4) })
        }
    }

    private fun renderHome() {
        if (!::cardValues.isInitialized) return
        val values = listOf(Item.MIC, Item.ACCESS, Item.BACKGROUND, Item.MODEL)
        values.forEachIndexed { index, item ->
            val done = item in lastDone
            cardValues[index].setText(if (done) R.string.home_status_ready else R.string.home_status_needed)
            cardValues[index].setTextColor(getColor(if (done) R.color.onb_green_ink else R.color.onb_plum))
        }
        renderListening(PipelineCore.listening.value)
        statusView.text = PipelineCore.status.value
    }

    /** 듣는 중이면 알약과 안내 글자를 바꾸고 마이크 버튼의 상태를 맞춘다. */
    private fun renderListening(on: Boolean) {
        if (!::pillText.isInitialized) return
        pillText.setText(if (on) R.string.home_listening else R.string.home_idle)
        listenCaption.setText(if (on) R.string.home_listening else R.string.home_say)
        micOrb.contentDescription = getString(if (on) R.string.main_listen_stop else R.string.main_listen_start)
    }

    /** 최근 실행 두 줄. 성공은 초록 점, 실패는 연한 주황 점이고 명령 이름과 걸린 시간을 보여 준다. */
    private fun renderRecent(runs: List<PipelineCore.RecentRun>) {
        if (!::recentRows.isInitialized) return
        recentRows.forEachIndexed { index, line ->
            val run = runs.getOrNull(index)
            val dot = line.getChildAt(0)
            val name = line.findViewWithTag<TextView>("name")
            val time = line.findViewWithTag<TextView>("time")
            if (run == null) {
                line.visibility = if (index == 0) View.VISIBLE else View.GONE
                if (index == 0) name.setText(R.string.home_recent_empty) else name.text = ""
                time.text = ""
                dot.visibility = View.GONE
            } else {
                line.visibility = View.VISIBLE
                dot.visibility = View.VISIBLE
                dot.background = oval(if (run.success) R.color.onb_green else R.color.onb_warn)
                name.text = run.command
                time.text = getString(R.string.home_recent_seconds, run.seconds)
            }
        }
    }

    private fun showHome() {
        reviewing = false
        show(homeView)
        if (!examplesShown) {
            examplesShown = true
            playExamples()
        }
        startPill()
    }

    private fun showSteps() {
        stopPill()
        show(stepsView)
    }

    /** 예시 줄을 50ms 간격으로 아래 8dp 에서 올라오며 나타나게 한다. 움직임을 줄였으면 바로 보인다. */
    private fun playExamples() {
        exampleRows.forEachIndexed { index, row ->
            if (!ValueAnimator.areAnimatorsEnabled()) return@forEachIndexed
            row.alpha = 0f
            row.translationY = dp(8).toFloat()
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * EXAMPLE_STAGGER_MS)
                .setDuration(EXAMPLE_ENTER_MS)
                .start()
        }
    }

    /** 상태 알약의 점 주기: 2.4초에 한 번 1에서 0.7까지 흐려졌다 돌아온다. */
    private fun startPill() {
        if (!ValueAnimator.areAnimatorsEnabled() || pillAnimator != null) return
        pillAnimator = ValueAnimator.ofFloat(1f, 0.7f).apply {
            duration = PILL_HALF_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { pill.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPill() {
        pillAnimator?.cancel()
        pillAnimator = null
        pill.alpha = 1f
    }

    private fun openReview() {
        reviewing = true
        cursor = 0
        showSteps()
        renderSteps(accessibilityState())
    }

    private fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, Item.entries.size - 1)
        renderSteps(accessibilityState())
    }

    /** 보여 줄 화면 하나만 남긴다. 다른 화면은 먼저 완전히 숨겨서 겹쳐 보이지 않게 한다. */
    private fun show(target: View) {
        val other = if (target === homeView) stepsView else homeView
        other.animate().cancel()
        other.alpha = 1f
        other.visibility = View.GONE
        if (target.visibility == View.VISIBLE) return
        target.visibility = View.VISIBLE
        if (ValueAnimator.areAnimatorsEnabled()) {
            target.alpha = 0f
            target.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            target.alpha = 1f
        }
    }

    private fun cursorItem(): Item? = Item.entries.getOrNull(cursor)

    // ---------------------------------------------------------------- 권한과 설정 흐름(바꾸지 않음)

    private fun doneItems(access: AccessibilityState): Set<Item> {
        val done = mutableSetOf<Item>()
        if (granted(Manifest.permission.RECORD_AUDIO)) done += Item.MIC
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)) {
            done += Item.NOTIFY
        }
        val connected = access == AccessibilityState.Connected
        if (connected) done += Item.ACCESS
        if (isIgnoringBatteryOptimizations()) done += Item.BACKGROUND
        if (connected && AemxAccessibilityService.isOverlayEnabled()) done += Item.OVERLAY
        if (granted(Manifest.permission.READ_CONTACTS)) done += Item.CONTACTS
        if (PipelineCore.modelReady.value) done += Item.MODEL
        return done
    }

    private fun onAction(item: Item) {
        when (item) {
            Item.MIC -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            Item.NOTIFY -> notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            Item.ACCESS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Item.BACKGROUND -> requestBackground()
            Item.OVERLAY -> {
                AemxAccessibilityService.setOverlayEnabled(true)
                refresh()
            }
            Item.CONTACTS -> contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            // 모델은 누르면 다시 받는다. 받는 중이거나 이미 준비됐으면 아무 일도 하지 않는다.
            Item.MODEL -> PipelineCore.retryModel()
        }
    }

    /** 배터리 최적화에서 빼 달라고 요청한다. 요청 창이 안 열리면 배터리 설정 목록으로 대신 보낸다. */
    private fun requestBackground() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** 배터리 최적화에서 빠져 있는지. 빠져 있으면 최근 앱에서 지워도 듣기가 계속된다. */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** 홈의 상태 카드를 누르면 그 설정으로 간다. 준비된 카드는 아무 일도 하지 않는다. */
    private fun cardAction(index: Int) {
        val item = listOf(Item.MIC, Item.ACCESS, Item.BACKGROUND, Item.MODEL)[index]
        if (item in lastDone || item == Item.MODEL) return
        onAction(item)
    }

    private fun onListenClicked() {
        if (PipelineCore.listening.value) {
            VoiceService.stop(this)
        } else if (granted(Manifest.permission.RECORD_AUDIO)) {
            VoiceService.start(this)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 접근성 상태. 연결된 것만 완료로 본다. 설정에만 남아 있으면 껐다 켜 달라고 따로 안내한다. */
    private fun accessibilityState(): AccessibilityState {
        val expected = ComponentName(this, AemxAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return AccessibilityStatus.of(
            connected = AemxAccessibilityService.isConnected(),
            configured = AccessibilityStatus.configuredIn(enabledServices, expected),
        )
    }

    /**
     * 연결이 아직 안 됐으면 1초마다 다시 본다. 설정에서 켜고 돌아오지 않고 화면에 머무는 동안에도 완료로 바뀐다.
     * 화면이 가려지면 멈춘다.
     */
    private val accessPoll = object : Runnable {
        override fun run() {
            refresh()
            if (accessibilityState() != AccessibilityState.Connected) handler.postDelayed(this, ACCESS_POLL_MS)
        }
    }

    private fun iconFor(item: Item) = when (item) {
        Item.MIC -> Glyph.MIC
        Item.NOTIFY -> Glyph.BELL
        Item.ACCESS -> Glyph.PERSON
        Item.BACKGROUND -> Glyph.BOLT
        Item.OVERLAY -> Glyph.LAYERS
        Item.CONTACTS -> Glyph.PEOPLE
        Item.MODEL -> Glyph.CHIP
    }

    private fun titleFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_title
        Item.NOTIFY -> R.string.guide_notify_title
        Item.ACCESS -> R.string.guide_access_title
        Item.BACKGROUND -> R.string.guide_background_title
        Item.OVERLAY -> R.string.guide_overlay_title
        Item.CONTACTS -> R.string.guide_contacts_title
        Item.MODEL -> R.string.guide_model_title
    }

    private fun descFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_desc
        Item.NOTIFY -> R.string.guide_notify_desc
        Item.ACCESS -> R.string.guide_access_desc
        Item.BACKGROUND -> R.string.guide_background_desc
        Item.OVERLAY -> R.string.guide_overlay_desc
        Item.CONTACTS -> R.string.guide_contacts_desc
        Item.MODEL -> R.string.guide_model_desc
    }

    private fun actionFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_action
        Item.NOTIFY -> R.string.guide_notify_action
        Item.ACCESS -> R.string.guide_access_action
        Item.BACKGROUND -> R.string.guide_background_action
        Item.OVERLAY -> R.string.guide_overlay_action
        Item.CONTACTS -> R.string.guide_contacts_action
        Item.MODEL -> R.string.guide_model_action
    }

    // ---------------------------------------------------------------- 작은 도우미

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** 주요 버튼: 자주에서 분홍으로 가는 그라데이션, 높이 56, 흰 굵은 글자. */
    private fun bigButton() = Button(this).apply {
        minHeight = dp(56)
        minimumHeight = dp(56)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        isAllCaps = false
        // 주요 버튼 글자는 흰색이다. 그라데이션 위에서 밤낮 모두 읽히도록 색을 고정한다.
        setTextColor(getColor(R.color.onb_on_brand))
        background = getDrawable(R.drawable.bg_onb_button)
    }

    private fun shape(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(color))
    }

    private fun pillShape(color: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(4).toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** 시작 화면의 최대 시간(ms). 모델이 이보다 빨리 준비되면 그때 내린다. */
        const val SPLASH_MAX_MS = 2_000L

        /** 접근성 연결을 기다리는 동안 다시 보는 간격. */
        const val ACCESS_POLL_MS = 1_000L

        /** 화면을 바꿀 때 나타나는 시간. 움직임을 줄이면 쓰지 않는다. */
        const val FADE_MS = 180L

        /** 예시 줄 등장: 간격 50ms, 한 줄 260ms. */
        const val EXAMPLE_STAGGER_MS = 50L
        const val EXAMPLE_ENTER_MS = 260L

        /** 상태 알약의 흐려짐 한 번에 걸리는 시간(2.4초 주기의 절반). */
        const val PILL_HALF_MS = 1_200L

        /** 최근 실행을 보여 줄 줄 수. */
        const val RECENT_SLOTS = 2

        /** 첫 실행 안내를 마쳤는지 저장하는 곳과 키. */
        const val PREFS_ONBOARDING = "aemx_onboarding"
        const val KEY_ONBOARDING_SEEN = "onboarding_seen"

        /** 디버깅 모드를 켜 두었는지. 같은 저장소에 둔다. */
        const val KEY_DEBUG_MODE = "debug_mode"

        /** 화면 회전 때 되살릴 값들의 이름. */
        const val STATE_CURSOR = "cursor"
        const val STATE_REVIEWING = "reviewing"
        const val STATE_HOME_REACHED = "homeReached"
        const val STATE_SKIPPED = "skipped"

        /**
         * 프로세스 안에서 자동 듣기를 한 번만 켜기 위한 표시. 화면을 돌리거나 다시 열어도 다시 켜지 않는다.
         * 사용자가 끈 뒤 화면을 돌려도 듣기가 되살아나지 않도록 화면이 아니라 프로세스에 둔다.
         */
        var autoListenStarted = false
    }
}
