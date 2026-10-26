package com.sihoo.aemx.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

internal class ActionExecutor(
    private val service: AemxAccessibilityService,
    private val listener: Listener,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val appLauncher = AppLauncher(service)
    private val messageSender = MessageSender(service, handler, appLauncher)
    private var executionId = 0L
    // 메인 스레드에서 바뀌고 다른 스레드(파이프라인)가 읽는다.
    @Volatile
    var isRunning = false
        private set
    private var lastTreeChange = 0L

    fun execute(plan: ActionPlan) {
        check(!isRunning) { "이미 실행 중입니다. 먼저 중단해 주세요." }
        isRunning = true
        val currentId = ++executionId
        executeStep(plan, 0, currentId)
    }

    fun onAccessibilityTreeChanged() {
        lastTreeChange = SystemClock.uptimeMillis()
        // Retry callbacks already poll briefly; removing the delay makes the next attempt event-driven.
        pendingRetry?.let {
            handler.removeCallbacks(it)
            handler.post(it)
        }
    }

    fun cancel() {
        isRunning = false
        executionId++
        handler.removeCallbacksAndMessages(null)
        pendingRetry = null
    }

    private var pendingRetry: Runnable? = null

    private fun executeStep(plan: ActionPlan, index: Int, id: Long) {
        if (id != executionId) return
        if (index >= plan.steps.size) {
            isRunning = false
            listener.onCompleted(plan)
            return
        }
        val step = plan.steps[index]
        listener.onStepStarted(index, step)
        when (step.action) {
            ActionType.OPEN_APP -> appLauncher.launch(step.target).fold(
                onSuccess = { retryUntilReady(plan, index, id) },
                onFailure = { fail(index, step, it.message ?: "앱 실행 실패", id) },
            )
            ActionType.SEARCH -> appLauncher.search(plan.app, step.target).fold(
                onSuccess = { retryUntilReady(plan, index, id) },
                onFailure = { fail(index, step, it.message ?: "검색 실행 실패", id) },
            )
            ActionType.NEXT_VIDEO -> {
                val delayMs = step.value?.let(WaitDuration::millis) ?: 0L
                handler.postDelayed({ retryUntilReady(plan, index, id) }, delayMs)
            }
            ActionType.WAIT -> {
                val delayMs = WaitDuration.millis(step.value ?: step.target)
                handler.postDelayed({ executeStep(plan, index + 1, id) }, delayMs)
            }
            // 화면을 여러 단계 거치는 흐름이라 retryUntilReady 의 한 번짜리 조작으로는 못 한다.
            ActionType.SEND_MESSAGE -> messageSender.send(
                app = plan.app,
                step = step,
                isCurrent = { id == executionId },
                onDone = { awaitSettled(plan, index + 1, id) },
                onFailed = { fail(index, step, it, id) },
            )
            else -> retryUntilReady(plan, index, id)
        }
    }

    private fun retryUntilReady(plan: ActionPlan, index: Int, id: Long) {
        val step = plan.steps[index]
        val deadline = SystemClock.uptimeMillis() +
            if (step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH)) OPEN_APP_TIMEOUT_MS else ACTION_TIMEOUT_MS
        var foregroundSince = 0L
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (id != executionId) return@Runnable
            pendingRetry = null
            val observed = try {
                performUiAction(plan.app, step)
            } catch (_: RuntimeException) {
                StepResult.Fatal("화면 조작 중 오류가 발생했습니다.")
            }
            if (observed == StepResult.NotReady && step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH)) {
                probeForeground(plan.app)
            }
            val result = if (step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH) && observed == StepResult.Success) {
                val now = SystemClock.uptimeMillis()
                if (foregroundSince == 0L) foregroundSince = now
                if (now - foregroundSince >= OPEN_APP_STABLE_MS) StepResult.Success else StepResult.NotReady
            } else {
                if (step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH)) foregroundSince = 0L
                observed
            }
            when (result) {
                is StepResult.Success -> {
                    awaitSettled(plan, index + 1, id)
                }
                is StepResult.Fatal -> fail(index, step, result.message, id)
                StepResult.NotReady -> {
                    if (SystemClock.uptimeMillis() >= deadline) {
                        val message = if (step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH)) {
                            "앱 화면이 열리지 않았습니다. 앱이 종료되거나 접근이 차단되었는지 확인해 주세요."
                        } else {
                            "대상 화면 또는 조작 가능한 요소를 8초 안에 찾지 못했습니다."
                        }
                        fail(index, step, message, id)
                    } else {
                        pendingRetry = attempt
                        handler.postDelayed(attempt, RETRY_INTERVAL_MS)
                    }
                }
            }
        }
        pendingRetry = attempt
        handler.post(attempt)
    }

    private fun awaitSettled(plan: ActionPlan, next: Int, id: Long) {
        val started = SystemClock.uptimeMillis()
        lastTreeChange = started
        lateinit var check: Runnable
        check = Runnable {
            if (id != executionId) return@Runnable
            val now = SystemClock.uptimeMillis()
            if (now - lastTreeChange >= 300L || now - started >= 1_500L) {
                executeStep(plan, next, id)
            } else handler.postDelayed(check, 100L)
        }
        handler.postDelayed(check, 300L)
    }

    private var lastProbeAt = 0L
    private var lastChipTapAt = 0L

    /**
     * 앱 화면이 뜨기를 기다리는 동안 1초마다 맨 앞 창이 무엇으로 보이는지 남긴다.
     * "앱 화면이 열리지 않았습니다" 가 (1) 활성 창을 못 읽어서(root=null, 앱이 접근성 데이터를
     * 막은 경우 등) 인지 (2) 다른 패키지 창이 앞에 있어서인지 (3) 창은 맞는데 패키지 비교가
     * 어긋나서인지 가르는 용도다. 진단이 끝나면 지워도 된다.
     */
    private fun probeForeground(app: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeAt < PROBE_INTERVAL_MS) return
        lastProbeAt = now
        runCatching {
            val root = service.rootInActiveWindow
            val windows = service.windows.joinToString { window ->
                "type=${window.type} layer=${window.layer} active=${window.isActive} " +
                    "focused=${window.isFocused} pkg=${window.root?.packageName}"
            }
            Log.i(
                TAG,
                "앱 화면 대기: 기대=${appLauncher.resolve(app)} root=${root?.packageName}/${root?.className} " +
                    "windows=[$windows]"
            )
        }
    }

    private fun performUiAction(app: String, step: ActionStep): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult.NotReady
        // Event package filters do not restrict rootInActiveWindow. Never act on another app.
        val appPackage = appLauncher.resolve(app)
            ?: return StepResult.Fatal("설치되지 않았거나 찾을 수 없는 앱입니다.")
        if (root.packageName?.toString() != appPackage) return StepResult.NotReady
        return when (step.action) {
            ActionType.OPEN_APP, ActionType.SEARCH -> StepResult.Success
            ActionType.TAP -> {
                val node = NodeFinder.find(root, step.target) ?: return StepResult.NotReady
                val clickable = NodeFinder.clickableAncestor(node) ?: return StepResult.NotReady
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) StepResult.Success
                else StepResult.NotReady
            }
            ActionType.INPUT_TEXT -> setText(root, step.target, step.value.orEmpty())
            ActionType.PLAY_FIRST -> {
                val first = if (appPackage == AppLauncher.YOUTUBE_MUSIC_PACKAGE) {
                    NodeFinder.findFirstSong(root) ?: return tapSongChip(root)
                } else {
                    NodeFinder.findFirstPlayable(root)
                }
                val node = first ?: return StepResult.NotReady
                // 유튜브 뮤직 행은 눌 수 있다고 알리는 노드가 없어서(dump 확인) 노드 클릭이 안 되면 좌표 탭으로 넘어간다.
                if (clickOrTap(node)) StepResult.Success else StepResult.NotReady
            }
            ActionType.NEXT_VIDEO -> {
                // ACTION_SCROLL_FORWARD 는 방향이 없다. 인스타그램처럼 바깥에 좌우 페이저(홈/메시지)가 있는
                // 앱에서는 그게 먼저 잡혀 메시지 화면으로 넘어간다. 세로 스크롤(SCROLL_DOWN)을 지원하는
                // 노드에만 보내고, 없으면 위로 쓸어 올리는 제스처로 넘긴다.
                val node = findVerticallyScrollable(root, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                when {
                    node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) == true -> StepResult.Success
                    service.swipeUp() -> StepResult.Success
                    else -> StepResult.NotReady
                }
            }
            ActionType.SCROLL -> {
                val backward = step.target.equals("up", true) || step.target.equals("backward", true)
                // NEXT_VIDEO 와 같은 이유로 방향 없는 FORWARD/BACKWARD 를 쓰지 않는다. 가로 페이저가 먼저
                // 잡히면 "아래로"가 옆으로 간다. 방향 있는 액션을 지원하는 노드가 없으면 쓸기 제스처로 넘긴다.
                val action = if (backward) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                } else {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                }
                val node = findVerticallyScrollable(root, action)
                when {
                    node?.performAction(action.id) == true -> StepResult.Success
                    (if (backward) service.swipeDown() else service.swipeUp()) -> StepResult.Success
                    else -> StepResult.NotReady
                }
            }
            else -> StepResult.Fatal("UI Action이 아닌 단계입니다: ${step.action.wireName}")
        }
    }

    /**
     * 유튜브 뮤직 "전체" 탭에서는 첫 노래를 가릴 수 없어 "노래" 칩을 먼저 누른다.
     * 칩이 이미 선택됐거나 방금 눌렀으면 누르지 않고 화면이 바뀌길 기다린다(NotReady).
     */
    private fun tapSongChip(root: AccessibilityNodeInfo): StepResult {
        val chip = NodeFinder.findSongFilterChip(root) ?: return StepResult.NotReady
        if (NodeFinder.isSelectedChip(chip)) return StepResult.NotReady
        val now = SystemClock.uptimeMillis()
        if (now - lastChipTapAt < CHIP_TAP_INTERVAL_MS) return StepResult.NotReady
        // 칩은 글자 Button 부터 목록까지 clickable 이 전부 false 다(dump 확인). 노드 클릭이 안 되면 좌표 탭으로 넘어간다.
        if (clickOrTap(chip)) lastChipTapAt = now
        return StepResult.NotReady
    }

    /**
     * 눌 수 있는 조상이 있고 클릭이 받아들여지면 그것으로 끝, 아니면 노드 bounds 중앙을 제스처로 탭한다.
     * 제스처는 눌렀는지 알려 주지 않으므로 호출한 쪽이 화면 변화로 확인해야 한다.
     */
    private fun clickOrTap(node: AccessibilityNodeInfo): Boolean {
        NodeFinder.clickableAncestor(node)?.let { clickable ->
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return service.tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun setText(root: AccessibilityNodeInfo, target: String, text: CharSequence): StepResult {
        val node = NodeFinder.find(root, target, editableOnly = true) ?: return StepResult.NotReady
        return setNodeText(node, text)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: CharSequence): StepResult {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            StepResult.Success
        } else {
            StepResult.NotReady
        }
    }

    /** [action](SCROLL_DOWN/UP)을 지원하는, 즉 세로로 움직이는 노드. 가로 페이저는 이 액션을 내놓지 않아 걸러진다. */
    private fun findVerticallyScrollable(
        root: AccessibilityNodeInfo,
        action: AccessibilityNodeInfo.AccessibilityAction
    ): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 2_000) return null
            val node = queue.removeFirst()
            if (node.isScrollable && node.isEnabled && node.isVisibleToUser &&
                node.actionList.any { it.id == action.id }
            ) {
                return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private fun fail(index: Int, step: ActionStep, message: String, id: Long) {
        if (id != executionId) return
        // 화면은 지금 찍어야 한다. cancel() 뒤나 다음 이벤트가 오면 이미 달라져 있다.
        val screen = NodeFinder.describeScreen(service.rootInActiveWindow)
        listener.onFailed(index, step, message, screen)
        cancel()
    }

    interface Listener {
        fun onStepStarted(index: Int, step: ActionStep)
        fun onCompleted(plan: ActionPlan)

        /**
         * 단계 하나가 실패해 계획이 중단됐다.
         *
         * [screen] 은 실패한 순간의 화면 요약이다. 이걸 원래 명령·지금까지의 기록과 함께
         * LLM 에 넘겨 새 계획을 받는 것이 "화면 변화/오류 시 재분석"의 입력이 된다.
         * 이 정보 없이는 모델이 무엇이 잘못됐는지 알 수 없어 아무거나 고르게 된다.
         */
        fun onFailed(index: Int, step: ActionStep, message: String, screen: ScreenDescription)
    }

    private sealed interface StepResult {
        data object Success : StepResult
        data object NotReady : StepResult
        data class Fatal(val message: String) : StepResult
    }

    private companion object {
        const val ACTION_TIMEOUT_MS = 8_000L
        private const val TAG = "AemxActionExecutor"
        const val PROBE_INTERVAL_MS = 1_000L
        const val CHIP_TAP_INTERVAL_MS = 5_000L
        const val OPEN_APP_TIMEOUT_MS = 15_000L
        const val OPEN_APP_STABLE_MS = 3_000L
        const val RETRY_INTERVAL_MS = 250L
    }
}
