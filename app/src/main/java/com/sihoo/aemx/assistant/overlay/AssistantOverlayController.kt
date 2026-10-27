package com.sihoo.aemx.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.sihoo.aemx.R
import com.sihoo.aemx.assistant.AssistantStore
import com.sihoo.aemx.assistant.AssistantUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 다른 앱 위에 접힌 캡슐을 띄우는 접근성 서비스의 오버레이.
 *
 * 창은 TYPE_ACCESSIBILITY_OVERLAY 다. 별도의 "다른 앱 위에 표시" 허용 없이 접근성 서비스가 켜져 있으면
 * 쓸 수 있고, 포커스를 받지 않아서(FLAG_NOT_FOCUSABLE) 맨 앞 창(rootInActiveWindow)은 계속 아래 앱이다.
 * 창 크기를 캡슐에 딱 맞춰서(WRAP_CONTENT) 캡슐 밖의 터치는 아래 앱으로 지나간다.
 *
 * 캡슐을 보일 때만 창을 추가하고 숨길 때는 떼어 낸다. 서비스가 닫힐 때 [detach] 를 꼭 불러야 한다
 * (안 그러면 창이 새어 남는다). 모든 메서드는 메인 스레드에서 부른다.
 */
internal class AssistantOverlayController(
    private val service: AccessibilityService,
    private val store: AssistantStore = AssistantStore.shared
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 좌표 제스처를 보내는 동안 캡슐이 터치를 먹지 않게 한다. */
    val touchGate = PassThroughGate(handler, ::applyPassThrough)

    private var scope: CoroutineScope? = null
    private var view: CapsuleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var dock = Dock.BOTTOM
    private var offset = 0
    private var imeBottom = 0
    private var enabled = true
    private var dismissedRunId: String? = null
    private var lastState = AssistantUiState()

    /** 캡슐 창이 지금 화면에 떠 있는지. */
    val isShowing: Boolean get() = added

    fun attach() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch { store.state.collect { render(it) } }
    }

    /** 서비스가 닫힐 때. 창을 떼고 구독을 끊는다. */
    fun detach() {
        scope?.cancel()
        scope = null
        hide()
        view = null
        touchGate.reset()
    }

    /** 개발 화면의 "오버레이 캡슐 보이기" 스위치. */
    fun setEnabled(value: Boolean) {
        enabled = value
        render(lastState)
    }

    /** 다크 모드나 글자 크기가 바뀌었다. 색과 크기를 다시 읽도록 캡슐을 새로 만든다. */
    fun onConfigurationChanged() {
        val showing = added
        hide()
        view = null
        if (showing) render(lastState)
    }

    private fun render(state: AssistantUiState) {
        lastState = state
        val text = CapsuleTextFactory.from(state)
        if (!text.visible || !CapsuleVisibility.shouldShow(state, enabled, dismissedRunId)) {
            hide()
            return
        }
        show(DockPolicy.dockFor(state.phase))
        view?.bind(text, dismissible = state.phase.isTerminal)

        handler.removeCallbacks(autoHide)
        if (CapsuleVisibility.autoHides(state.phase)) {
            autoHideRunId = state.runId
            handler.postDelayed(autoHide, CapsuleVisibility.AUTO_HIDE_MS)
        }
    }

    private var autoHideRunId: String? = null
    private val autoHide = Runnable { dismiss(autoHideRunId) }

    private fun dismiss(runId: String?) {
        dismissedRunId = runId
        hide()
    }

    private fun show(newDock: Dock) {
        val capsule = view ?: CapsuleView(
            service,
            onStop = { store.requestCancellation() },
            onDismiss = { dismiss(lastState.runId) }
        ).also {
            view = it
            it.setOnApplyWindowInsetsListener { v, insets -> onInsets(insets); v.onApplyWindowInsets(insets) }
        }

        val dockChanged = newDock != dock
        dock = newDock
        if (!added) {
            val lp = buildParams()
            params = lp
            try {
                windowManager.addView(capsule, lp)
                added = true
            } catch (e: RuntimeException) {
                // 창을 못 붙여도 실행에는 영향이 없다. 다음 상태 변화 때 다시 시도한다.
                Log.w(TAG, "캡슐 창을 붙이지 못했다", e)
                return
            }
            capsule.post { correctPosition(0) }
        } else if (dockChanged) {
            val lp = params ?: return
            lp.gravity = gravityFor(dock)
            offset = OverlayGeometry.initialOffset(dock, readInsets(), marginPx())
            lp.y = offset
            update(lp)
            capsule.post { correctPosition(0) }
        }
    }

    private fun hide() {
        handler.removeCallbacks(autoHide)
        val capsule = view
        if (added && capsule != null) {
            try {
                windowManager.removeView(capsule)
            } catch (e: RuntimeException) {
                Log.w(TAG, "캡슐 창을 떼지 못했다", e)
            }
        }
        added = false
        touchGate.reset()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val width = service.resources.displayMetrics.widthPixels -
            2 * service.resources.getDimensionPixelSize(R.dimen.aemx_side_inset)
        offset = OverlayGeometry.initialOffset(dock, readInsets(), marginPx())
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityFor(dock)
            y = offset
            title = "AEMX 캡슐"
        }
    }

    private fun gravityFor(dock: Dock) =
        (if (dock == Dock.BOTTOM) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL

    private fun marginPx() = (OverlayGeometry.MARGIN_DP * service.resources.displayMetrics.density).toInt()

    // ---- 인셋과 위치

    /** 상태 표시줄·내비게이션 바·컷아웃·키보드의 두께. */
    private fun readInsets(): OverlayInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return OverlayInsets(top = bars.top, bottom = bars.bottom, ime = imeBottom)
        }
        return OverlayInsets(
            top = systemDimen("status_bar_height"),
            bottom = systemDimen("navigation_bar_height"),
            ime = imeBottom
        )
    }

    private fun systemDimen(name: String): Int {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    /** 키보드 높이가 바뀌면 아래 도킹 위치를 다시 잡는다. */
    private fun onInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (ime == imeBottom) return
        imeBottom = ime
        val lp = params ?: return
        if (!added || dock != Dock.BOTTOM) return
        offset = OverlayGeometry.initialOffset(dock, readInsets(), marginPx())
        lp.y = offset
        update(lp)
        view?.post { correctPosition(0) }
    }

    /**
     * 그려진 위치를 재서 있어야 할 곳과 다르면 y 를 한 번 고친다.
     * 오버레이 창이 시스템 바를 이미 피해서 배치되는 기기에서 인셋을 두 번 빼는 것을 막는다.
     */
    private fun correctPosition(attempt: Int) {
        val capsule = view ?: return
        val lp = params ?: return
        if (!added) return
        if (capsule.height == 0) {
            if (attempt < MAX_CORRECTIONS) capsule.postDelayed({ correctPosition(attempt + 1) }, CORRECT_DELAY_MS)
            return
        }
        val at = IntArray(2)
        capsule.getLocationOnScreen(at)
        val actual = if (dock == Dock.BOTTOM) at[1] + capsule.height else at[1]
        val desired = OverlayGeometry.desiredEdge(dock, screenHeight(), readInsets(), marginPx())
        val corrected = OverlayGeometry.correctedOffset(dock, offset, actual, desired)
        Log.i(TAG, "캡슐 위치: dock=$dock 실제=$actual 목표=$desired y=$offset→$corrected")
        if (corrected == offset || attempt >= MAX_CORRECTIONS) return
        offset = corrected
        lp.y = corrected
        update(lp)
        capsule.postDelayed({ correctPosition(attempt + 1) }, CORRECT_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun update(lp: WindowManager.LayoutParams) {
        val capsule = view ?: return
        if (!added) return
        try {
            windowManager.updateViewLayout(capsule, lp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "캡슐 창을 고치지 못했다", e)
        }
    }

    /** [PassThroughGate] 가 부른다. 터치 통과면 FLAG_NOT_TOUCHABLE 을 켠다. */
    private fun applyPassThrough(pass: Boolean) {
        val lp = params ?: return
        lp.flags = if (pass) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        update(lp)
    }

    private companion object {
        const val TAG = "AemxOverlay"
        const val MAX_CORRECTIONS = 3
        const val CORRECT_DELAY_MS = 80L
    }
}
