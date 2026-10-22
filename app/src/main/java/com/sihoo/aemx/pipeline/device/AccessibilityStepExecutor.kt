package com.sihoo.aemx.pipeline.device

import android.util.Log
import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.accessibility.WaitDuration
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.ActionExecutor.Command
import com.sihoo.aemx.pipeline.port.ExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.sihoo.aemx.accessibility.ActionType as A11yType

/**
 * 오케스트레이터의 step 단위 [ActionExecutor] 를 접근성 서비스의 계획 단위 실행에 맞추는 어댑터.
 *
 * ## 계획 단위와 step 단위 잇기
 *
 * 서비스는 계획 JSON 을 통째로 받아 끝까지 돌리고, 끝나면 [PlanOutcome] 을 한 번 알려 준다.
 * 오케스트레이터는 명령을 하나씩 넘기고 매번 결과를 기다린다. 그래서 [execute] 한 번에
 *  1. 명령 하나를 "step 한 개짜리 계획"으로 만들어 제출하고,
 *  2. 서비스가 그 계획의 끝을 알려 줄 때까지 기다렸다가,
 *  3. 결과를 [ExecutionResult] 로 바꿔 돌려준다.
 * step 사이의 화면 검증과 재분석은 서비스가 아니라 오케스트레이터가 한다.
 *
 * ## 알아 둘 점
 * - 서비스는 `rootInActiveWindow` 의 패키지가 계획의 app 과 같을 때만 조작한다. 명령에는 앱 이름이
 *   없는 경우가 많아서, 맨 앞 화면의 패키지 -> 직전에 실행/검색한 앱 순으로 앱을 정한다.
 * - 서비스는 계획을 끝낼 때마다 "화면 조작을 마쳤습니다" 를 말한다. step 마다 말하지 않도록
 *   제출할 때 `announceCompletion = false` 로 보낸다(이 계획에만 적용).
 * - 서비스의 계획 콜백은 하나뿐이다. 실행하는 동안 이 어댑터가 가로채고, 끝나면 [restoreObserver] 가
 *   돌려주는 콜백으로 되돌린다(화면이 원래 걸어 둔 콜백. 화면이 멈춰 있으면 null).
 * - 실행하는 동안 화면이 콜백을 건드리면(onStop 등) 어댑터의 콜백이 지워져 끝 알림을 못 받는다.
 *   화면은 콜백을 직접 바꾸지 말고 [setObserverIfIdle] 로 바꾼다. 실행 중이면 무시되고,
 *   끝날 때 [restoreObserver] 가 그때의 화면 상태를 보고 정한다.
 *
 * @param foregroundPackage 지금 맨 앞 화면의 패키지명(모르면 null)
 * @param mainDispatcher 서비스가 제출을 메인 스레드에서만 받는다. 테스트에서 바꿔 끼운다.
 */
class AccessibilityStepExecutor(
    private val bridge: PlanBridge,
    private val foregroundPackage: () -> String? = { null },
    private val restoreObserver: () -> ((PlanOutcome) -> Unit)? = { null },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * 패키지에 해당하는 서비스 앱 이름. 고정 표의 앱만 알면 되면 기본값을 쓰고, 설치된 앱까지 열려면
     * [AccessibilityAppResolver.nameFor] 를 넘긴다.
     */
    private val nameForPackage: (String?) -> String? = AppNames::forPackage,
    /** 서비스가 알아듣는 앱 이름인지. 기본은 고정 표, 설치된 앱까지 보려면 [AccessibilityAppResolver.isResolvable]. */
    private val isSupportedApp: (String) -> Boolean = AppNames::isSupported
) : ActionExecutor {

    /** 서비스에는 콜백이 하나뿐이라 동시에 두 명령을 흘려보내면 결과가 뒤섞인다. */
    private val lock = Mutex()

    @Volatile
    private var currentApp: String? = null

    /** [running] 과 서비스 콜백 교체를 한 덩어리로 묶는다. 화면의 onStop 과 겹쳐도 어긋나지 않게 한다. */
    private val observerLock = Any()
    private var running = false

    /** 실행 중이 아닐 때만 서비스 콜백을 바꾼다. 실행 중이면 끝날 때 [restoreObserver] 가 정한다. */
    fun setObserverIfIdle(callback: ((PlanOutcome) -> Unit)?) {
        synchronized(observerLock) {
            if (!running) bridge.setObserver(callback)
        }
    }

    override fun isReady(): Boolean = bridge.isConnected()

    override suspend fun execute(command: Command): ExecutionResult = lock.withLock {
        if (!bridge.isConnected()) {
            // JVM 단위테스트에는 android.util.Log 가 없어 던질 수 있다. 로그 때문에 결과가 바뀌면 안 된다.
            runCatching { Log.w(TAG, "NotReady: isConnected=false, command=$command, foreground=${foregroundPackage()}") }
            return ExecutionResult.NotReady("접근성 서비스가 연결되어 있지 않다")
        }

        // 서비스 파서가 검색을 받는 앱은 정해져 있다. 제출해서 파서 문구로 거절되기 전에 먼저 막는다.
        if (command is Command.Search && !AppLauncher.supportsDirectSearch(command.appName)) {
            return ExecutionResult.Failed("${command.appName}에서는 바로 검색할 수 없다(네이버, 구글, 유튜브만 지원한다)")
        }

        val app = resolveApp(command)
            ?: return ExecutionResult.Failed("조작할 앱을 알 수 없다(접근성 서비스가 지원하지 않는 앱이거나 맨 앞 화면이 아니다)")

        val built = try {
            StepPlanFactory.build(command, app)
        } catch (e: IllegalArgumentException) {
            return ExecutionResult.Failed(e.message ?: "명령을 만들지 못했다")
        }
        currentApp = app

        val done = CompletableDeferred<PlanOutcome>()
        synchronized(observerLock) {
            running = true
            bridge.setObserver { done.complete(it) }
        }
        var finished = false
        try {
            val submitted = withContext(mainDispatcher) { bridge.submit(built.json, announceCompletion = false) }
            submitted.exceptionOrNull()?.let {
                finished = true // 제출이 거절됐으니 중단할 계획이 없다.
                return ExecutionResult.Failed(it.message ?: "계획 제출 실패")
            }

            val outcome = withTimeoutOrNull(timeoutFor(command)) { done.await() }
                ?: return ExecutionResult.Failed("서비스가 시간 안에 끝났다고 알려 주지 않았다")
            finished = true
            toResult(outcome, built.action)
        } finally {
            // 시간 초과나 취소로 빠져나오면 서비스에서 계획이 계속 돌고 있다.
            if (!finished) bridge.cancel()
            synchronized(observerLock) {
                running = false
                bridge.setObserver(restoreObserver())
            }
        }
    }

    /** 서비스가 알아듣는 앱 이름. 앞 화면 -> 직전 앱 순. 열기/검색은 명령이 앱을 알려 준다. */
    private fun resolveApp(command: Command): String? = when (command) {
        is Command.LaunchApp -> nameForPackage(command.packageName)
        is Command.Search -> command.appName.takeIf { isSupportedApp(it) }
        // 서비스가 첫 영상 재생을 받는 앱은 유튜브뿐이라 앞 화면이나 직전 앱을 보지 않는다.
        Command.PlayFirst -> YOUTUBE
        else -> nameForPackage(foregroundPackage()) ?: currentApp
    }

    /**
     * 서비스 쪽 자체 제한시간(앱 열기 15초+안정 3초, 그 외 8초, 그 뒤 화면 정착 1.5초)보다
     * 넉넉하게 잡는다. 서비스가 먼저 실패를 알리는 게 정상이고, 이건 안전망이다.
     */
    private fun timeoutFor(command: Command): Long = when (command) {
        is Command.LaunchApp, is Command.Search -> LAUNCH_TIMEOUT_MS
        is Command.NextVideo -> {
            val delay = command.delay?.let { runCatching { WaitDuration.millis(it) }.getOrNull() } ?: 0L
            ACTION_TIMEOUT_MS + delay
        }
        else -> ACTION_TIMEOUT_MS
    }

    internal companion object {
        const val LAUNCH_TIMEOUT_MS = 25_000L
        const val ACTION_TIMEOUT_MS = 13_000L
        private const val TAG = "AccessibilityStepExecutor"
        private const val YOUTUBE = "유튜브"

        /**
         * 서비스의 끝 알림을 오케스트레이터의 결과로 옮긴다.
         *
         * 서비스는 실패 사유를 문장으로만 준다. 화면 조작류가 실패하는 경로는 사실상
         * "대상을 못 찾았다"(제한시간 안에 못 찾음)와 "조작 중 예외"뿐이라, 앞의 것만 따로 가른다.
         * 대상 못 찾음은 재분석이 가장 잘 먹히는 경우라 구분해 둘 값이 있다.
         */
        fun toResult(outcome: PlanOutcome, action: A11yType): ExecutionResult = when (outcome) {
            is PlanOutcome.Completed -> ExecutionResult.Performed

            is PlanOutcome.Failed ->
                if (action in UI_ACTIONS && outcome.message.contains("찾지 못")) {
                    ExecutionResult.TargetNotFound(outcome.message)
                } else {
                    ExecutionResult.Failed(outcome.message)
                }
        }

        private val UI_ACTIONS = setOf(
            A11yType.TAP, A11yType.INPUT_TEXT, A11yType.PLAY_FIRST, A11yType.NEXT_VIDEO, A11yType.SCROLL
        )
    }
}
