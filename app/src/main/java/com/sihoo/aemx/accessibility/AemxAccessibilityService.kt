package com.sihoo.aemx.accessibility

import android.accessibilityservice.AccessibilityService
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
        executor = ActionExecutor(this, UnconfiguredCredentialProvider, this)
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
    }

    override fun onFailed(index: Int, step: ActionStep, message: String) {
        // target value나 credential은 로그에 포함하지 않습니다.
        Log.e(TAG, "Action step ${index + 1} (${step.action.wireName}) failed: $message")
        status = "${index + 1}단계 실패: $message"
    }

    companion object {
        private const val TAG = "AemxAccessibility"
        @Volatile private var activeService: WeakReference<AemxAccessibilityService>? = null
        @Volatile var status: String = "접근성 서비스 연결 대기"
            private set

        fun cancelActionPlan() {
            Handler(Looper.getMainLooper()).post { activeService?.get()?.onInterrupt() }
        }

        /** Install the team's provider on the main thread after connection, while idle. Not persisted. */
        fun configureCredentialProvider(provider: CredentialProvider): Result<Unit> = runCatching {
            check(Looper.myLooper() == Looper.getMainLooper()) { "메인 스레드에서 설정해 주세요." }
            val service = activeService?.get() ?: error("접근성 서비스를 먼저 활성화해 주세요.")
            check(!service.executor.isRunning) { "실행을 중단한 뒤 설정해 주세요." }
            service.executor.cancel()
            service.executor = ActionExecutor(service, provider, service)
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
