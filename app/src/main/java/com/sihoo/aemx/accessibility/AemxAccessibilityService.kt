package com.sihoo.aemx.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.sihoo.aemx.AndroidTtsController
import java.lang.ref.WeakReference

class AemxAccessibilityService : AccessibilityService(), ActionExecutor.Listener {
    private lateinit var executor: ActionExecutor
    private var ttsController: AndroidTtsController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::executor.isInitialized) executor.cancel()
        ttsController?.shutdown()
        executor = ActionExecutor(this, this)
        activeService = WeakReference(this)
        status = "실행 준비 완료"
        ttsController = AndroidTtsController(applicationContext) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::executor.isInitialized) executor.onAccessibilityTreeChanged()
    }

    override fun onInterrupt() {
        if (::executor.isInitialized) executor.cancel()
        status = "실행 중단됨"
    }

    override fun onDestroy() {
        if (::executor.isInitialized) executor.cancel()
        ttsController?.shutdown()
        ttsController = null
        if (activeService?.get() === this) activeService = null
        status = "접근성 서비스 연결 끊김"
        super.onDestroy()
    }

    private fun submit(json: String): Result<Unit> = runCatching {
        check(Looper.myLooper() == Looper.getMainLooper()) { "메인 스레드에서 제출해 주세요." }
        check(::executor.isInitialized) { "접근성 서비스가 아직 준비되지 않았습니다." }
        executor.execute(ActionPlanParser.parse(json))
    }

    override fun onStepStarted(index: Int, step: ActionStep) {
        Log.d(TAG, "Action step ${index + 1}: ${step.action.wireName}")
        status = "${index + 1}단계: ${step.action.wireName}"
    }

    override fun onCompleted(plan: ActionPlan) {
        status = "모든 UI 명령 전달 완료 (실제 결과 확인 필요)"
        Log.i(TAG, "Action Plan dispatched")
        ttsController?.speak("화면 조작을 마쳤습니다. 결과를 확인해 주세요")
        observer?.invoke(PlanOutcome.Completed)
    }

    override fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription) {
        // target/value는 사용자 입력일 수 있으므로 로그에 포함하지 않습니다.
        Log.e(TAG, "Action step ${index + 1} (${step.action.wireName}) failed: $message")
        status = "${index + 1}단계 실패: $message"
        observer?.invoke(PlanOutcome.Failed(index, step, message, screen))
    }

    internal fun swipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.78f)
            lineTo(x, metrics.heightPixels * 0.22f)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
                .build(),
            null,
            null,
        )
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

        fun cancelActionPlan() {
            Handler(Looper.getMainLooper()).post { activeService?.get()?.onInterrupt() }
        }

        /** LLM 연결부가 생성한 JSON을 현재 활성화된 서비스에 전달합니다. */
        fun submitActionPlan(json: String): Result<Unit> {
            val service = activeService?.get()
                ?: return Result.failure(IllegalStateException("AEMX 접근성 서비스를 먼저 활성화해 주세요."))
            return service.submit(json)
        }

        fun isConnected(): Boolean = activeService?.get() != null
    }
}
