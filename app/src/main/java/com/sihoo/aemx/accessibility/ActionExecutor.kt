package com.sihoo.aemx.accessibility

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

internal class ActionExecutor(
    private val service: AemxAccessibilityService,
    private val listener: Listener,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val appLauncher = AppLauncher(service)
    private var executionId = 0L
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

    private fun performUiAction(app: String, step: ActionStep): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult.NotReady
        // Event package filters do not restrict rootInActiveWindow. Never act on another app.
        if (root.packageName?.toString() != AppLauncher.packageFor(app)) return StepResult.NotReady
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
                val node = NodeFinder.findFirstPlayable(root) ?: return StepResult.NotReady
                val clickable = NodeFinder.clickableAncestor(node) ?: return StepResult.NotReady
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) StepResult.Success
                else StepResult.NotReady
            }
            ActionType.NEXT_VIDEO -> {
                val node = findScrollable(root)
                when {
                    node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true -> StepResult.Success
                    service.swipeUp() -> StepResult.Success
                    else -> StepResult.NotReady
                }
            }
            ActionType.SCROLL -> {
                val backward = step.target.equals("up", true) || step.target.equals("backward", true)
                val action = if (backward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val node = findScrollable(root) ?: return StepResult.NotReady
                if (node.performAction(action)) StepResult.Success else StepResult.NotReady
            }
            else -> StepResult.Fatal("UI Action이 아닌 단계입니다: ${step.action.wireName}")
        }
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

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 2_000) return null
            val node = queue.removeFirst()
            if (node.isScrollable && node.isEnabled && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private fun fail(index: Int, step: ActionStep, message: String, id: Long) {
        if (id != executionId) return
        listener.onFailed(index, step, message)
        cancel()
    }

    interface Listener {
        fun onStepStarted(index: Int, step: ActionStep)
        fun onCompleted(plan: ActionPlan)
        fun onFailed(index: Int, step: ActionStep, message: String)
    }

    private sealed interface StepResult {
        data object Success : StepResult
        data object NotReady : StepResult
        data class Fatal(val message: String) : StepResult
    }

    private companion object {
        const val ACTION_TIMEOUT_MS = 8_000L
        const val OPEN_APP_TIMEOUT_MS = 15_000L
        const val OPEN_APP_STABLE_MS = 3_000L
        const val RETRY_INTERVAL_MS = 250L
    }
}
