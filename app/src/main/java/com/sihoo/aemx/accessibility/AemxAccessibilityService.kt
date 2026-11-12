package com.sihoo.aemx.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.app.ActivityManager
import android.os.Process
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.sihoo.aemx.AndroidTtsController
import com.sihoo.aemx.MainActivity
import com.sihoo.aemx.assistant.overlay.AssistantOverlayController
import com.sihoo.aemx.assistant.overlay.MicState
import com.sihoo.aemx.assistant.overlay.QuickMenuController
import com.sihoo.aemx.assistant.overlay.QuickMenuRules
import com.sihoo.aemx.pipeline.app.PipelineCore
import com.sihoo.aemx.pipeline.app.QuitPrefs
import com.sihoo.aemx.pipeline.app.VoiceService
import com.sihoo.aemx.pipeline.stt.MicStatus
import java.lang.ref.WeakReference

class AemxAccessibilityService : AccessibilityService(), ActionExecutor.Listener {
    private lateinit var executor: ActionExecutor
    private var ttsController: AndroidTtsController? = null

    /** 지금 도는 계획의 끝 안내(TTS)를 말할지. 제출할 때마다 그 계획 기준으로 다시 정해진다. */
    private var announceCompletion = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::executor.isInitialized) executor.cancel()
        ttsController?.shutdown()
        executor = ActionExecutor(this, this)
        activeService = WeakReference(this)
        status = "실행 준비 완료"
        ttsController = AndroidTtsController(applicationContext) { }
        overlay?.detach()
        overlay = AssistantOverlayController(this).also {
            it.setEnabled(overlayEnabled)
            it.attach()
        }
        if (QuitPrefs.isQuit(this)) Log.i(TAG, "완전히 종료한 상태라 듣기를 켜지 않는다")
        // 접근성 서비스는 앱을 최근 앱에서 지워도 시스템이 다시 연결한다. 그래서 여기서 호출어 듣기를 켜 두면
        // 앱 화면을 열지 않아도 된다. 마이크 권한과 사용자의 끈 상태는 확인한다. 떠 있는데 꺼진 마이크는 다시 켠다.
        com.sihoo.aemx.pipeline.app.VoiceService.resumeIfIdle(this)
        // 화면에 떠 있는 접근성 버튼. 설정 파일에서 버튼을 요청했으므로 누르면 시스템이 서비스를 끄지 않고 여기로 알린다.
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        quickMenu?.hide()
        quickMenu = buildQuickMenu()
    }

    /** 접근성 버튼을 눌렀을 때. 빠른 메뉴를 열고 닫는다. */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            Log.i(TAG, "접근성 버튼 눌림")
            quickMenu?.toggle()
        }
    }

    /** 접근성 버튼을 누르면 뜨는 메뉴(앱 열기, 마이크, 완전히 종료). 서비스가 닫힐 때 꼭 떼어야 한다. */
    private var quickMenu: QuickMenuController? = null

    private fun buildQuickMenu() = QuickMenuController(
        service = this,
        micState = ::micStateNow,
        micLevel = { MicStatus.level },
        micNotice = ::micNoticeNow,
        onOpenApp = ::openApp,
        onToggleMic = ::toggleMic,
        onQuit = ::quitApp,
    )

    /**
     * 앱을 완전히 끝낸다. 도는 계획을 멈추고, 마이크와 듣기 서비스(알림 포함)를 내리고, 화면을 최근 앱에서 지운 뒤
     * 프로세스를 끝내 모델이 쓰던 메모리를 돌려준다.
     *
     * 접근성 서비스 설정은 끄지 않는다. 그래서 안드로이드가 프로세스를 다시 띄워 이 서비스만 연결한다. 그 프로세스는
     * 마이크도 모델도 올리지 않고 떠 있는 버튼만 받는다([QuitPrefs] 가 자동 듣기를 막는다). 사용자가 앱을 열면 평소대로 돌아간다.
     */
    private fun quitApp() {
        Log.i(TAG, "빠른 메뉴: 완전히 종료")
        QuitPrefs.setQuit(this, true)
        cancelPlan()
        PipelineCore.stopVoice()
        stopService(Intent(this, VoiceService::class.java))
        getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            runCatching { task.finishAndRemoveTask() }
        }
        // 서비스와 화면이 내려갈 틈을 준 뒤에 끝낸다.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, QUIT_KILL_DELAY_MS)
    }

    private fun micStateNow(): MicState = QuickMenuRules.micState(
        listening = PipelineCore.listening.value,
        micIdle = PipelineCore.isMicIdle(),
        silenced = MicStatus.silenced,
    )

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 마이크를 켜지 못하는 이유. 꺼져 있을 때만 보여 준다. */
    private fun micNoticeNow(): String? = when {
        micStateNow() != MicState.OFF -> null
        !hasMicPermission() -> "앱을 열어 마이크를 허용해 주세요"
        VoiceService.backgroundStartBlocked -> "앱을 열어서 켜 주세요"
        else -> null
    }

    /**
     * 마이크 칸을 눌렀다. 켜져 있으면 잠시 끄고, 꺼져 있으면 켠다. 여는 중에는 아무것도 하지 않는다.
     * 듣기 서비스가 떠 있으면 마이크만 여닫고, 없으면 서비스를 올린다. 다른 앱이 앞에 있을 때 서비스를 올리는 것은
     * 안드로이드가 막을 수 있어서, 막히면 안내 줄이 앱을 열라고 알린다([micNoticeNow]).
     */
    private fun toggleMic() {
        val turnOff = QuickMenuRules.tapTurnsOff(micStateNow()) ?: return
        PipelineCore.init(applicationContext)
        if (turnOff) {
            Log.i(TAG, "빠른 메뉴: 마이크 잠시 끔")
            PipelineCore.pauseMic()
            return
        }
        if (!hasMicPermission()) return
        Log.i(TAG, "빠른 메뉴: 마이크 켬 (듣기 서비스=${VoiceService.running})")
        if (VoiceService.running) {
            PipelineCore.resumeMic()
        } else {
            runCatching { VoiceService.start(this) }
                .onFailure { Log.w(TAG, "빠른 메뉴: 듣기 서비스를 올리지 못했다 (${it.javaClass.simpleName})") }
        }
    }

    /**
     * AEMX 홈 화면을 앞으로 가져온다. 이미 떠 있던 화면이 있으면 그 화면이 올라온다.
     * 런처 인텐트를 쓰지 않고 화면을 직접 지정한다. 디버그 빌드에는 런처 항목이 둘(홈, 개발 화면)이라
     * 런처 인텐트가 개발 화면을 열 수 있기 때문이다.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }
            .onSuccess { Log.i(TAG, "빠른 메뉴: 앱 열기") }
            .onFailure { Log.w(TAG, "빠른 메뉴: 앱을 열지 못했다 (${it.javaClass.simpleName})") }
    }

    /** 다른 앱 위에 뜨는 캡슐. 서비스가 닫힐 때 꼭 떼어야 한다. */
    private var overlay: AssistantOverlayController? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
        quickMenu?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.detach()
        overlay = null
        quickMenu?.hide()
        quickMenu = null
        return super.onUnbind(intent)
    }

    private fun isExecutorRunning(): Boolean = ::executor.isInitialized && executor.isRunning

    /** 메인 스레드에서 카톡 이름 검색을 돌린다([lookupKakaoRecipients] 가 부른다). */
    private fun lookupKakaoNow(query: String, onNames: (List<String>) -> Unit, onFailed: (String) -> Unit) {
        if (!::executor.isInitialized) return onFailed("접근성 서비스가 아직 준비되지 않았습니다.")
        executor.lookupKakao(query, isCurrent = { true }, onNames = onNames, onFailed = onFailed)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::executor.isInitialized) executor.onAccessibilityTreeChanged()
    }

    /**
     * 시스템이 접근성 피드백(음성 등)을 끊으라고 부르는 콜백이다. 사용자나 앱이 계획을 멈추라고 한 것이
     * 아니므로 실행 중인 계획은 건드리지 않고 기록만 남긴다. 예전에는 여기서 계획을 취소해서, 오버레이나
     * 다른 접근성 서비스가 끼어들 때 계획이 조용히 멈출 수 있었다. 계획을 멈추는 길은 [cancelPlan] 이다.
     */
    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt: 시스템 요청이라 계획은 취소하지 않는다(실행 중=${isExecutorRunning()})")
    }

    /** 사용자나 앱이 요청한 취소. 실행 중인 계획을 멈춘다. */
    internal fun cancelPlan() {
        if (::executor.isInitialized) executor.cancel()
        status = "실행 중단됨"
    }

    override fun onDestroy() {
        if (::executor.isInitialized) executor.cancel()
        overlay?.detach()
        overlay = null
        quickMenu?.hide()
        quickMenu = null
        ttsController?.shutdown()
        ttsController = null
        if (activeService?.get() === this) activeService = null
        status = "접근성 서비스 연결 끊김"
        super.onDestroy()
    }

    private fun submit(json: String, announceCompletion: Boolean): Result<Unit> = runCatching {
        check(Looper.myLooper() == Looper.getMainLooper()) { "메인 스레드에서 제출해 주세요." }
        check(::executor.isInitialized) { "접근성 서비스가 아직 준비되지 않았습니다." }
        val plan = ActionPlanParser.parse(json)
        // 이미 실행 중이라 거절되면 돌고 있는 계획의 값을 건드리지 않도록 되돌린다.
        val previous = this.announceCompletion
        this.announceCompletion = announceCompletion
        try {
            // announceCompletion=false 로 제출하는 쪽(step 단위 호출자)은 화면을 스스로 확인하므로 정착을 기다리지 않는다.
            executor.execute(plan, settleAfterStep = announceCompletion)
        } catch (e: Throwable) {
            this.announceCompletion = previous
            throw e
        }
    }

    override fun onStepStarted(index: Int, step: ActionStep) {
        Log.d(TAG, "Action step ${index + 1}: ${step.action.wireName}")
        status = "${index + 1}단계: ${step.action.wireName}"
    }

    override fun onCompleted(plan: ActionPlan, evidence: String?) {
        status = "모든 UI 명령 전달 완료 (실제 결과 확인 필요)"
        Log.i(TAG, "Action Plan dispatched")
        if (announceCompletion) ttsController?.speak("화면 조작을 마쳤습니다. 결과를 확인해 주세요")
        observer?.invoke(PlanOutcome.Completed(evidence))
    }

    override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {
        // target/value는 사용자 입력일 수 있으므로 로그에 포함하지 않습니다.
        Log.e(TAG, "Action step ${index + 1} (${step.action.wireName}) failed: $message")
        status = "${index + 1}단계 실패: $message"
        // 성공 안내와 같은 스위치를 따른다. 오케스트레이터 경로(false)는 최종 결과를 따로 말한다.
        if (announceCompletion) ttsController?.speak("화면 조작을 하지 못했습니다. 화면을 확인해 주세요")
        observer?.invoke(PlanOutcome.Failed(index, step, message, screen))
    }

    internal fun swipeUp(): Boolean = swipeVertically(fromY = 0.78f, toY = 0.22f)

    /** 손가락을 아래로 쓸어 내린다(내용은 위로, 즉 이전 쪽으로 간다). */
    internal fun swipeDown(): Boolean = swipeVertically(fromY = 0.22f, toY = 0.78f)

    /** 화면의 (x, y) 픽셀을 짧게 탭한다. 노드가 눌 수 있다고 알리지 않을 때 쓴다. */
    internal fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchWithPassThrough(path, 60L)
    }

    private fun swipeVertically(fromY: Float, toY: Float): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * fromY)
            lineTo(x, metrics.heightPixels * toY)
        }
        return dispatchWithPassThrough(path, 350L)
    }

    /**
     * 좌표 제스처를 보낸다. 캡슐이 떠 있으면 제스처가 끝날 때까지 캡슐을 터치 통과로 바꾼다 — 캡슐 자리의
     * 탭이나 쓸기가 캡슐에 막혀 아래 앱에 닿지 않기 때문이다. 창 설정이 반영될 시간을 주려고 캡슐이
     * 떠 있을 때만 제스처를 조금 늦춰 시작한다.
     */
    private fun dispatchWithPassThrough(path: Path, durationMs: Long): Boolean {
        val gate = overlay?.takeIf { it.isShowing }?.touchGate
        gate?.begin()
        val startDelay = if (gate != null) GESTURE_START_DELAY_MS else 0L
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gate?.end()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gate?.end()
            }
        }
        val sent = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, startDelay, durationMs))
                .build(),
            callback,
            null,
        )
        // 보내지도 못했으면 끝 콜백이 오지 않으니 바로 되돌린다.
        if (!sent) gate?.end()
        return sent
    }

    companion object {
        private const val TAG = "AemxAccessibility"
        @Volatile private var activeService: WeakReference<AemxAccessibilityService>? = null
        @Volatile var status: String = "접근성 서비스 연결 대기"
            private set

        /**
         * 계획 실행이 끝나면 불린다. 파이프라인이 실패를 받아 재분석을 걸기 위한 통로다.
         *
         * 서비스는 앱 프로세스보다 오래 살 수 있으므로, 화면이 사라질 때 반드시
         * null 로 되돌려 놓아야 한다([setPlanObserver] 참고).
         */
        @Volatile private var observer: ((PlanOutcome) -> Unit)? = null

        fun setPlanObserver(callback: ((PlanOutcome) -> Unit)?) {
            observer = callback
        }

        /** 개발 화면의 "오버레이 캡슐 보이기". 서비스가 아직 없어도 값은 남아서 연결되면 적용된다. */
        @Volatile private var overlayEnabled = true

        fun isOverlayEnabled(): Boolean = overlayEnabled

        fun setOverlayEnabled(enabled: Boolean) {
            overlayEnabled = enabled
            Handler(Looper.getMainLooper()).post { activeService?.get()?.overlay?.setEnabled(enabled) }
        }

        /** 창 설정이 반영되기를 기다리는 시간. 캡슐을 터치 통과로 바꾼 직후 제스처를 늦춰 시작한다. */
        private const val GESTURE_START_DELAY_MS = 80L

        /** 완전히 종료에서 서비스와 화면을 내린 뒤 프로세스를 끝내기까지 기다리는 시간. */
        private const val QUIT_KILL_DELAY_MS = 600L

        fun cancelActionPlan() {
            Handler(Looper.getMainLooper()).post { activeService?.get()?.cancelPlan() }
        }

        /**
         * LLM 연결부가 생성한 JSON을 현재 활성화된 서비스에 전달합니다.
         *
         * @param announceCompletion false 면 이 계획이 끝나도 완료 음성 안내를 하지 않는다(기본 true).
         *   step 마다 계획을 따로 제출하는 호출자가 매 step 안내를 막으려고 쓴다. 이 계획에만 적용된다.
         *   같은 호출자는 화면을 스스로 확인하므로 열기·검색·탭·입력 뒤 정착 대기도 건너뛴다([ActionExecutor.execute]).
         */
        fun submitActionPlan(json: String, announceCompletion: Boolean = true): Result<Unit> {
            val service = activeService?.get()
                ?: return Result.failure(IllegalStateException("AEMX 접근성 서비스를 먼저 활성화해 주세요."))
            return service.submit(json, announceCompletion)
        }

        fun isConnected(): Boolean = activeService?.get() != null

        /** 서비스가 지금 계획을 실행 중인지. 서비스가 없거나 아직 준비 전이면 false. 읽기 전용이다. */
        fun isPlanRunning(): Boolean = activeService?.get()?.isExecutorRunning() == true

        /**
         * 카톡 검색 결과의 친구 이름을 모은다(보내지 않는다). 서비스가 없거나 계획이 도는 중이면 false 를 돌려주고 아무것도 하지 않는다.
         * 결과는 [onNames] 또는 [onFailed] 로 한 번 알린다(메인 스레드에서). 받는 쪽은 기다리는 시간을 따로 정한다.
         */
        fun lookupKakaoRecipients(query: String, onNames: (List<String>) -> Unit, onFailed: (String) -> Unit): Boolean {
            val service = activeService?.get() ?: return false
            if (service.isExecutorRunning()) return false
            Handler(Looper.getMainLooper()).post { service.lookupKakaoNow(query, onNames, onFailed) }
            return true
        }
    }
}
