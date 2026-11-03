package com.sihoo.aemx.accessibility

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.sihoo.aemx.pipeline.model.TextMatch

internal class ActionExecutor(
    private val service: AemxAccessibilityService,
    private val listener: Listener,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val appLauncher = AppLauncher(service)
    // 이번 계획이 끝나며 알릴, 실행모듈이 직접 확인한 결과(메시지 전송 확인). 계획을 시작할 때 비운다.
    private var evidence: String? = null
    private val messageSender = MessageSender(service, handler, appLauncher, onEvidence = { evidence = it })
    private var executionId = 0L
    // 메인 스레드에서 바뀌고 다른 스레드(파이프라인)가 읽는다.
    @Volatile
    var isRunning = false
        private set
    private var lastTreeChange = 0L

    fun execute(plan: ActionPlan) {
        check(!isRunning) { "이미 실행 중입니다. 먼저 중단해 주세요." }
        isRunning = true
        evidence = null
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
        // 계획이 아무 말 없이 멈추는 것을 나중에 추적할 수 있게 남긴다(누가 불렀는지 호출 위치를 함께).
        Log.i(TAG, "계획 취소: 실행 중=$isRunning", Throwable("cancel 호출 위치"))
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
            listener.onCompleted(plan, evidence)
            return
        }
        val step = plan.steps[index]
        listener.onStepStarted(index, step)
        // N번째 항목을 세는 기록은 step 마다 새로 시작한다.
        ordinalTracker = null
        shortsSince = 0L
        shortsScrolled = false
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
            // 영상 id 로 유튜브 영상 화면을 바로 연다. 검색과 스크롤을 거치지 않는다.
            ActionType.OPEN_VIDEO -> appLauncher.openVideo(step.target).fold(
                onSuccess = { retryUntilReady(plan, index, id) },
                onFailure = { fail(index, step, it.message ?: "영상 열기 실패", id) },
            )
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
        var popupDismisses = 0
        var prePopupChecked = false
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (id != executionId) return@Runnable
            pendingRetry = null
            // 늦게 뜬 광고 팝업은 첫 탭을 받아 버리고 조작은 성공으로 보인다. 누르거나 입력하기 전에 한 번 닫아 본다.
            if (!prePopupChecked && step.action in setOf(ActionType.TAP, ActionType.INPUT_TEXT)) {
                prePopupChecked = true
                if (popupDismisses < MAX_POPUP_DISMISSES && tryDismissPopup(plan.app, step)) {
                    popupDismisses++
                    pendingRetry = attempt
                    handler.postDelayed(attempt, RETRY_INTERVAL_MS)
                    return@Runnable
                }
            }
            val observed = try {
                performUiAction(plan.app, step)
            } catch (_: RuntimeException) {
                StepResult.Fatal("화면 조작 중 오류가 발생했습니다.")
            }
            if (observed == StepResult.NotReady && step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH)) {
                probeForeground(plan.app)
            }
            // 열기 뒤에 뜬 광고·안내 팝업, 또는 누를 대상을 못 찾은 조작 앞의 팝업을 먼저 닫는다. 한 단계에서 최대 2번.
            val popupCheckHere = (step.action == ActionType.OPEN_APP && observed == StepResult.Success) ||
                (step.action in setOf(ActionType.TAP, ActionType.INPUT_TEXT) && observed == StepResult.NotReady)
            if (popupCheckHere && popupDismisses < MAX_POPUP_DISMISSES && tryDismissPopup(plan.app, step)) {
                popupDismisses++
                pendingRetry = attempt
                handler.postDelayed(attempt, RETRY_INTERVAL_MS)
                return@Runnable
            }
            val result = if (step.action in setOf(ActionType.OPEN_APP, ActionType.SEARCH) && observed == StepResult.Success) {
                val now = SystemClock.uptimeMillis()
                if (foregroundSince == 0L) foregroundSince = now
                // 검색은 검색어가 보이는 것으로 충분하니 안정 대기를 기다리지 않는다. 열기는 앱이 잠시 앞에 머물러야 한다.
                if (step.action == ActionType.SEARCH || now - foregroundSince >= OPEN_APP_STABLE_MS) StepResult.Success
                else StepResult.NotReady
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
                        val message = when (step.action) {
                            ActionType.SEARCH -> "검색 결과 화면에 검색어가 보이지 않았습니다."
                            ActionType.OPEN_APP -> "앱 화면이 열리지 않았습니다. 앱이 종료되거나 접근이 차단되었는지 확인해 주세요."
                            else -> "대상 화면 또는 조작 가능한 요소를 8초 안에 찾지 못했습니다."
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
    private var ordinalTracker: OrdinalTracker? = null

    /** 쇼츠만 보이는 화면에서 기다리기 시작한 때와 한 번 스크롤했는지. 단계마다 새로 시작한다. */
    private var shortsSince = 0L
    private var shortsScrolled = false

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
            val own = service.packageName
            // 우리 오버레이 창(캡슐)은 앱 화면이 아니므로 목록에서 뺀다. 몇 개였는지만 남긴다.
            val all = service.windows
            val shown = all.filterNot { NodeFilter.isOwnOverlay(it.type, it.root?.packageName, own) }
            val windows = shown.joinToString { window ->
                "type=${window.type} layer=${window.layer} active=${window.isActive} " +
                    "focused=${window.isFocused} pkg=${window.root?.packageName}"
            }
            Log.i(
                TAG,
                "앱 화면 대기: 기대=${appLauncher.resolve(app)} root=${root?.packageName}/${root?.className} " +
                    "오버레이 제외=${all.size - shown.size} windows=[$windows]"
            )
            logIfOverlayFront(root)
        }
    }

    /**
     * 맨 앞 창(rootInActiveWindow)이 우리 패키지면 남긴다. 오버레이는 포커스를 받지 않으니 앞에 나오면 안 되는데,
     * 나온다면 패키지 비교(root.packageName)가 모든 단계를 NotReady 로 만든다. 실제로 그런지 폰 로그로 먼저
     * 확인하려는 것이다(개발 화면이 앞일 때도 같은 로그가 나므로 로그만 보고 판단한다).
     */
    private fun logIfOverlayFront(root: AccessibilityNodeInfo?) {
        if (!NodeFilter.isOwnPackage(root?.packageName, service.packageName)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayLogAt < PROBE_INTERVAL_MS) return
        lastOverlayLogAt = now
        Log.w(TAG, "맨 앞 창이 우리 앱이다(오버레이가 앞이거나 개발 화면이 앞): root=${root?.className}")
    }

    private var lastOverlayLogAt = 0L

    private fun performUiAction(app: String, step: ActionStep): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult.NotReady
        // Event package filters do not restrict rootInActiveWindow. Never act on another app.
        val appPackage = appLauncher.resolve(app)
            ?: return StepResult.Fatal("설치되지 않았거나 찾을 수 없는 앱입니다.")
        if (root.packageName?.toString() != appPackage) {
            logIfOverlayFront(root)
            return StepResult.NotReady
        }
        return when (step.action) {
            ActionType.OPEN_APP, ActionType.OPEN_VIDEO -> StepResult.Success
            // 검색은 이번 검색어가 화면에 보일 때만 성공이다. 같은 앱의 옛 검색 결과도 앱 화면이라 패키지만으로는 구별되지 않는다.
            ActionType.SEARCH ->
                if (TextMatch.queryVisible(visibleTexts(root), step.target.orEmpty())) StepResult.Success
                else StepResult.NotReady
            ActionType.TAP -> tap(root, step.target)
            ActionType.INPUT_TEXT -> inputText(root, step.target, step.value.orEmpty())
            ActionType.PLAY_FIRST -> {
                val ordinal = step.value?.let(Ordinal::find) ?: 1
                if (ordinal > 1) return playNth(root, appPackage, ordinal, includeShorts = wantsShorts(step))
                val first = if (appPackage == AppLauncher.YOUTUBE_MUSIC_PACKAGE) {
                    NodeFinder.findFirstSong(root) ?: return tapSongChip(root)
                } else {
                    NodeFinder.findFirstPlayable(root, includeShorts = wantsShorts(step))
                        ?: return shortsOnlyFallback(root, wantsShorts(step))
                }
                val node = first
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
     * N번째(2 이상) 항목을 누른다. 유튜브는 광고를 뺀 영상, 유튜브 뮤직은 "노래" 칩이 선택된 목록의 노래 행을 센다.
     * 화면에 N번째가 안 보이면 한 번만 스크롤해서 이어 세고, 그래도 없으면 실패한다.
     */
    /** 사용자가 쇼츠를 말한 명령인가. 쇼츠는 그때만 고른다(일반 영상을 우선한다). */
    private fun wantsShorts(step: ActionStep): Boolean =
        step.target.orEmpty().let { it.contains("쇼츠") || it.contains("shorts", ignoreCase = true) }

    /**
     * 화면에 일반 영상은 없고 쇼츠만 보일 때. 바로 쇼츠를 누르지 않는다. 일반 영상이 그려지길 [SHORTS_WAIT_MS] 기다리고,
     * 그래도 없으면 한 번 스크롤한 뒤 다시 기다린다. 그래도 없을 때만 쇼츠를 누른다.
     */
    private fun shortsOnlyFallback(root: AccessibilityNodeInfo, wantsShorts: Boolean): StepResult {
        if (wantsShorts) return StepResult.NotReady
        val shorts = NodeFinder.playableItems(root, includeShorts = true)
        if (shorts.isEmpty()) return StepResult.NotReady
        val now = SystemClock.uptimeMillis()
        if (shortsSince == 0L) shortsSince = now
        if (now - shortsSince < SHORTS_WAIT_MS) return StepResult.NotReady
        if (!shortsScrolled) {
            shortsScrolled = true
            shortsSince = now
            val list = findVerticallyScrollable(root, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
            list?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
            return StepResult.NotReady
        }
        return if (clickOrTap(shorts[0])) StepResult.Success else StepResult.NotReady
    }

    private fun playNth(root: AccessibilityNodeInfo, appPackage: String, ordinal: Int, includeShorts: Boolean): StepResult {
        val music = appPackage == AppLauncher.YOUTUBE_MUSIC_PACKAGE
        val items = if (music) NodeFinder.songRows(root) ?: return tapSongChip(root) else NodeFinder.playableItems(root, includeShorts)
        val tracker = ordinalTracker ?: OrdinalTracker(ordinal).also { ordinalTracker = it }
        val now = SystemClock.uptimeMillis()
        // 스크롤한 직후에는 목록이 움직이는 중이라 세지 않는다.
        if (tracker.settling(now)) return StepResult.NotReady
        if (items.isEmpty()) return StepResult.NotReady

        val keys = items.map { if (music) NodeFinder.songKey(it) else NodeFinder.playableKey(it) }
        tracker.pick(keys)?.let { position ->
            return if (clickOrTap(items[position])) StepResult.Success else StepResult.NotReady
        }
        // 스크롤했는데 처음 보는 항목이 없으면 목록 끝까지 본 것이다. 스크롤 상한에 닿아도 같다.
        if (tracker.noNewSinceLastScroll() || tracker.scrollCount >= MAX_NTH_SCROLLS) {
            return StepResult.Fatal("${ordinal}번째 항목을 찾지 못했습니다.")
        }

        tracker.markScrolled(now)
        val node = findVerticallyScrollable(root, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
        val moved = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) == true ||
            service.swipeUp()
        return if (moved) StepResult.NotReady else StepResult.Fatal("${ordinal}번째 항목을 찾으려 스크롤하지 못했습니다.")
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

    private fun insideWebView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.className?.toString() == "android.webkit.WebView") return true
            current = current.parent
        }
        return false
    }

    /**
     * 눌 수 있는 조상이 있고 클릭이 받아들여지면 그것으로 끝, 아니면 노드 bounds 중앙을 제스처로 탭한다.
     * 제스처는 눌렀는지 알려 주지 않으므로 호출한 쪽이 화면 변화로 확인해야 한다.
     */
    private fun clickOrTap(node: AccessibilityNodeInfo): Boolean {
        // 웹뷰 안의 버튼은 접근성 클릭을 받아 놓고 아무 일도 하지 않는 일이 있어, 처음부터 좌표로 누른다.
        if (!insideWebView(node)) {
            NodeFinder.clickableAncestor(node)?.let { clickable ->
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return service.tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /**
     * 누르기. 이름으로 먼저 찾고, 실패하면 역할로 찾는다. 검색 입력칸 말이면 칸을 누르고, 검색 버튼 말이면 버튼을 누른다.
     */
    private fun tap(root: AccessibilityNodeInfo, target: String): StepResult {
        NodeFinder.find(root, target)?.let { node ->
            val clickable = NodeFinder.clickableAncestor(node) ?: return StepResult.NotReady
            return if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) StepResult.Success else StepResult.NotReady
        }
        if (SearchFieldRole.isButtonRequest(target)) return pressSearchButton(root)
        if (SearchFieldRole.isFieldRequest(target)) {
            val field = findSearchField(root) ?: return StepResult.NotReady
            return if (clickOrTap(field)) StepResult.Success else StepResult.NotReady
        }
        return StepResult.NotReady
    }

    /**
     * 입력. 이름으로 먼저 찾고(편집 가능한 노드만), 실패하면 검색 입력칸을 역할로 찾는다.
     * SET_TEXT 를 받으면 칸에 보이는지 확인한다. 받지 않는 입력칸(웹뷰 등)은 포커스 → 전체 선택 → 붙여넣기로 넣는다.
     */
    private fun inputText(root: AccessibilityNodeInfo, target: String, text: String): StepResult {
        val node = NodeFinder.find(root, target, editableOnly = true)
            ?: (if (SearchFieldRole.isFieldRequest(target)) findSearchField(root) else null)
            ?: return StepResult.NotReady
        val probe = text.trim().take(TextMatch.QUERY_KEY_LENGTH)
        // SET_TEXT 를 받으면 끝이다. 칸의 글자는 바로 다시 읽히지 않는 경우가 있어 확인하지 않는다(기존 동작).
        // SET_TEXT 는 덧붙이지 않고 바꾸므로 다시 넣어도 중복되지 않는다.
        if (setNodeText(node, text) == StepResult.Success) return StepResult.Success
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return StepResult.NotReady
        // 기존 글자를 모두 고른 뒤 붙여넣으면 바뀐다(선택 범위 지정은 API 18 부터 있다).
        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)
        if (!copyToClipboard(text)) return StepResult.NotReady
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return StepResult.NotReady
        node.refresh()
        return if (showsOtherText(node, probe)) StepResult.NotReady else StepResult.Success
    }

    /**
     * 칸에 글자가 보이는데 넣으려던 글자가 아니면 true. 글자를 읽을 수 없거나(비어 있음) 맞으면 false.
     * 붙여넣기처럼 칸에 바로 안 보이는 경우를 실패로 몰지 않기 위해 빈 칸은 확인하지 않는다.
     */
    private fun showsOtherText(node: AccessibilityNodeInfo, probe: String): Boolean {
        // 힌트 글자만 보이는 칸은 비어 있는 것이다(힌트를 글자로 착각하지 않는다).
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return false
        val shown = node.text?.toString()?.takeIf { it.isNotBlank() } ?: return false
        return probe.isNotEmpty() && !TextMatch.contains(shown, probe)
    }

    private fun copyToClipboard(text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("aemx", text))
        return true
    }

    /** 역할로 찾은 검색 입력칸. 편집 가능한 노드 중에서 [SearchFieldRole.pickField] 가 고른다. */
    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val fields = editableNodes(root)
        val index = SearchFieldRole.pickField(
            fields.map { node ->
                SearchFieldRole.Field(
                    id = node.viewIdResourceName,
                    hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null,
                    description = node.contentDescription?.toString(),
                    box = boxOf(node),
                    focused = node.isFocused,
                )
            },
            service.resources.displayMetrics.heightPixels,
        ) ?: return null
        return fields[index]
    }

    /**
     * 검색 버튼 누르기. 입력칸 오른쪽의 검색 버튼을 누르고, 없으면 입력칸에 엔터(IME 엔터, API 30 이상)를 보낸다.
     */
    private fun pressSearchButton(root: AccessibilityNodeInfo): StepResult {
        val field = findSearchField(root) ?: return StepResult.NotReady
        val buttons = clickableNodes(root)
        val index = SearchFieldRole.pickButton(
            buttons.map { node ->
                SearchFieldRole.Button(node.text?.toString(), node.contentDescription?.toString(), boxOf(node))
            },
            boxOf(field),
        )
        if (index != null) return if (clickOrTap(buttons[index])) StepResult.Success else StepResult.NotReady
        return if (imeEnter(field)) StepResult.Success else StepResult.NotReady
    }

    @SuppressLint("NewApi")
    private fun imeEnter(field: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= 30 &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    private fun editableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        collect(root) { node ->
            node.isVisibleToUser && node.isEnabled &&
                (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
        }

    private fun clickableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        collect(root) { node ->
            node.isVisibleToUser && node.isEnabled &&
                (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }

    /** 화면 트리를 위에서부터 훑어 [keep] 에 맞는 노드를 모은다. 노드 수에는 상한을 둔다. */
    private fun collect(root: AccessibilityNodeInfo, keep: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TEXT_NODES) {
            val node = queue.removeFirst()
            visited++
            if (keep(node)) out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun boxOf(node: AccessibilityNodeInfo): SearchFieldRole.Box {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return SearchFieldRole.Box(rect.left, rect.top, rect.right, rect.bottom)
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
        /** [evidence] 는 실행모듈이 직접 확인한 결과(메시지 전송 확인). 없으면 null. */
        fun onCompleted(plan: ActionPlan, evidence: String?)

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

    /**
     * 대상 앱 화면에 닫을 수 있는 팝업 버튼이 있으면 누른다. 누르면 true.
     * 대상 앱의 창이 앞에 있을 때만 본다(권한 대화상자 등 다른 창은 건드리지 않는다). 고르는 규칙은 [PopupDismiss].
     */
    private fun tryDismissPopup(app: String, step: ActionStep): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val appPackage = appLauncher.resolve(app) ?: return false
        if (root.packageName?.toString() != appPackage) return false

        val candidates = mutableListOf<PopupDismiss.Candidate>()
        val clickables = mutableListOf<AccessibilityNodeInfo?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TEXT_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.text?.toString()
            val description = node.contentDescription?.toString()
            if (label != null || description != null) {
                val clickable = NodeFinder.clickableAncestor(node)
                candidates += PopupDismiss.Candidate(
                    label = label,
                    description = description,
                    actionable = clickable != null && node.isVisibleToUser && node.isEnabled,
                )
                clickables += clickable
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }

        val index = PopupDismiss.choose(candidates) ?: return false
        // 웹뷰 팝업은 접근성 클릭을 받고도 닫히지 않는 일이 있어 실제 좌표 탭으로 누른다.
        val target = clickables[index]
        val bounds = Rect().also { target?.getBoundsInScreen(it) }
        val clicked = if (target != null && bounds.width() > 0 && bounds.height() > 0) {
            service.tap(bounds.exactCenterX(), bounds.exactCenterY())
        } else {
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        if (clicked) Log.i(TAG, "팝업 닫기: ${step.action.name} 단계 앞에서 닫을 버튼을 눌렀다")
        return clicked
    }

    /** 화면의 글자와 설명(content-desc)을 모은다. 검색어 확인에 쓴다. 노드 수는 상한으로 자른다. */
    private fun visibleTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && queue.size + out.size < MAX_TEXT_NODES) {
            val node = queue.removeFirst()
            node.text?.let { out += it.toString() }
            node.contentDescription?.let { out += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private companion object {
        const val MAX_TEXT_NODES = 600
        const val MAX_POPUP_DISMISSES = 2

        /** N번째 항목을 찾으려 한 단계 안에서 스크롤하는 상한. 유튜브 결과는 쇼츠·광고가 섞여 화면마다 몇 개뿐이다. */
        const val MAX_NTH_SCROLLS = 3

        /** 쇼츠만 보일 때 일반 영상이 그려지길 기다리는 시간. */
        const val SHORTS_WAIT_MS = 1_500L
        const val ACTION_TIMEOUT_MS = 8_000L
        private const val TAG = "AemxActionExecutor"
        const val PROBE_INTERVAL_MS = 1_000L
        const val CHIP_TAP_INTERVAL_MS = 5_000L
        const val OPEN_APP_TIMEOUT_MS = 15_000L
        const val OPEN_APP_STABLE_MS = 3_000L
        const val RETRY_INTERVAL_MS = 250L
    }
}
