package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.ActionStep
import com.sihoo.aemx.accessibility.PlanOutcome
import com.sihoo.aemx.accessibility.ScreenDescription
import com.sihoo.aemx.pipeline.port.ActionExecutor.Command
import com.sihoo.aemx.pipeline.port.ExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sihoo.aemx.accessibility.ActionType as A11yType

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityStepExecutorTest {

    /** 제출된 계획을 기록하고, 정해 둔 방식으로 끝을 알리는 가짜 서비스 입구. */
    private class FakeBridge(
        var connected: Boolean = true,
        /** 제출을 받았을 때 서비스가 할 일. null 이면 끝을 알리지 않는다(멈춘 서비스). */
        var onSubmit: ((json: String, observer: ((PlanOutcome) -> Unit)?) -> Unit)? = null,
        var submitResult: Result<Unit> = Result.success(Unit)
    ) : PlanBridge {
        val submitted = mutableListOf<String>()
        val announceFlags = mutableListOf<Boolean>()
        var currentObserver: ((PlanOutcome) -> Unit)? = null
        var cancelCount = 0

        override fun isConnected() = connected

        override fun submit(json: String, announceCompletion: Boolean): Result<Unit> {
            submitted += json
            announceFlags += announceCompletion
            if (submitResult.isSuccess) onSubmit?.invoke(json, currentObserver)
            return submitResult
        }

        override fun setObserver(callback: ((PlanOutcome) -> Unit)?) {
            currentObserver = callback
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private fun failed(action: A11yType, message: String) = PlanOutcome.Failed(
        stepIndex = 0,
        step = ActionStep(action, "x"),
        message = message,
        screen = ScreenDescription()
    )

    private fun TestScope.executor(
        bridge: FakeBridge,
        foreground: String? = null,
        restore: () -> ((PlanOutcome) -> Unit)? = { null }
    ) = AccessibilityStepExecutor(
        bridge = bridge,
        foregroundPackage = { foreground },
        restoreObserver = restore,
        mainDispatcher = UnconfinedTestDispatcher(testScheduler)
    )

    @Test
    fun `서비스가 완료를 알리면 Performed 다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val result = executor(bridge).execute(Command.Search("유튜브", "노트북"))

        assertEquals(ExecutionResult.Performed, result)
        // step 한 개짜리 계획이 제출됐다.
        val plan = JSONObject(bridge.submitted.single())
        assertEquals("유튜브", plan.getString("app"))
        assertEquals(1, plan.getJSONArray("steps").length())
        // step 마다 완료 안내가 나오지 않도록 안내 끔으로 제출한다.
        assertEquals(listOf(false), bridge.announceFlags)
    }

    @Test
    fun `첫 영상 재생은 앞 화면이 네이버여도 유튜브로 보낸다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        executor(bridge, foreground = "com.nhn.android.search").execute(Command.PlayFirst)

        assertEquals("유튜브", JSONObject(bridge.submitted.single()).getString("app"))
    }

    @Test
    fun `첫 영상 재생은 앞 화면을 몰라도 직전 앱이 네이버여도 유튜브로 보낸다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val exec = executor(bridge, foreground = null)
        exec.execute(Command.Search("네이버", "노트북"))
        exec.execute(Command.PlayFirst)

        assertEquals("유튜브", JSONObject(bridge.submitted.last()).getString("app"))
    }

    @Test
    fun `검색을 지원하지 않는 앱이면 Failed 이고 제출하지 않는다`() = runTest {
        val bridge = FakeBridge()
        val result = executor(bridge).execute(Command.Search("인스타그램", "고양이"))

        assertTrue(result is ExecutionResult.Failed)
        assertTrue(bridge.submitted.isEmpty())
        assertNull(bridge.currentObserver)
    }

    @Test
    fun `네이버 구글 유튜브 검색은 그 앱으로 제출한다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val exec = executor(bridge)
        listOf("네이버", "구글", "유튜브").forEach { exec.execute(Command.Search(it, "a")) }

        assertEquals(
            listOf("네이버", "구글", "유튜브"),
            bridge.submitted.map { JSONObject(it).getString("app") }
        )
    }

    @Test
    fun `연결이 없으면 NotReady 이고 아무것도 제출하지 않는다`() = runTest {
        val bridge = FakeBridge(connected = false)
        val result = executor(bridge).execute(Command.Tap("로그인"))

        assertTrue(result is ExecutionResult.NotReady)
        assertTrue(bridge.submitted.isEmpty())
    }

    @Test
    fun `누르기에서 대상을 못 찾으면 TargetNotFound 다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs ->
            obs?.invoke(failed(A11yType.TAP, "대상 화면 또는 조작 가능한 요소를 8초 안에 찾지 못했습니다."))
        })
        val result = executor(bridge, foreground = "com.google.android.youtube")
            .execute(Command.Tap("구독"))

        assertTrue(result is ExecutionResult.TargetNotFound)
    }

    @Test
    fun `고정 표에 없는 앱의 LaunchApp 도 풀린 이름으로 제출된다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val resolver = AccessibilityAppResolver { name -> "com.kurly.android".takeIf { name == "마켓컬리" } }
        // 오케스트레이터가 이름을 패키지로 풀 때 기억한 이름을 서비스에 넘긴다.
        assertEquals("com.kurly.android", resolver.resolvePackage("마켓컬리"))

        val exec = AccessibilityStepExecutor(
            bridge = bridge,
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
            nameForPackage = { pkg -> AppNames.forPackage(pkg) ?: resolver.nameFor(pkg) },
            isSupportedApp = resolver::isResolvable
        )
        val result = exec.execute(Command.LaunchApp("com.kurly.android"))

        assertEquals(ExecutionResult.Performed, result)
        val plan = JSONObject(bridge.submitted.single())
        assertEquals("마켓컬리", plan.getString("app"))
        assertEquals("마켓컬리", plan.getJSONArray("steps").getJSONObject(0).getString("target"))
    }

    @Test
    fun `풀린 적 없는 패키지의 LaunchApp 은 서비스에 제출하지 않고 실패한다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val result = executor(bridge).execute(Command.LaunchApp("com.unknown.app"))

        assertTrue(result is ExecutionResult.Failed)
        assertTrue(bridge.submitted.isEmpty())
    }

    @Test
    fun `앱 실행 실패는 Failed 다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs ->
            obs?.invoke(failed(A11yType.OPEN_APP, "앱 화면이 열리지 않았습니다."))
        })
        val result = executor(bridge).execute(Command.LaunchApp("com.google.android.youtube"))

        assertTrue(result is ExecutionResult.Failed)
    }

    @Test
    fun `제출이 거절되면 Failed 이고 중단 요청은 하지 않는다`() = runTest {
        val bridge = FakeBridge(submitResult = Result.failure(IllegalStateException("이미 실행 중입니다.")))
        val result = executor(bridge).execute(Command.Search("유튜브", "a"))

        assertEquals(ExecutionResult.Failed("이미 실행 중입니다."), result)
        assertEquals(0, bridge.cancelCount)
    }

    @Test
    fun `서비스가 끝을 알리지 않으면 시간 초과로 Failed 이고 계획을 중단한다`() = runTest {
        val bridge = FakeBridge(onSubmit = null)
        val result = executor(bridge).execute(Command.Search("유튜브", "a"))

        assertTrue(result is ExecutionResult.Failed)
        assertEquals(1, bridge.cancelCount)
    }

    @Test
    fun `끝나면 화면이 원래 걸어 둔 콜백으로 되돌린다`() = runTest {
        val original: (PlanOutcome) -> Unit = {}
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        executor(bridge, restore = { original }).execute(Command.Search("유튜브", "a"))

        assertSame(original, bridge.currentObserver)
    }

    @Test
    fun `화면이 멈춰 있으면 끝나고 콜백을 걸지 않는다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        executor(bridge, restore = { null }).execute(Command.Search("유튜브", "a"))

        assertNull(bridge.currentObserver)
    }

    @Test
    fun `실행 도중 화면이 콜백을 지우려 해도 어댑터는 완료를 받는다`() = runTest {
        lateinit var exec: AccessibilityStepExecutor
        lateinit var bridge: FakeBridge
        bridge = FakeBridge(onSubmit = { _, _ ->
            // 네이버가 앞으로 와서 개발 화면이 onStop 되는 상황.
            exec.setObserverIfIdle(null)
            // 서비스는 제출 뒤 지금 걸려 있는 콜백으로 끝을 알린다.
            bridge.currentObserver?.invoke(PlanOutcome.Completed)
        })
        exec = executor(bridge)

        assertEquals(ExecutionResult.Performed, exec.execute(Command.Search("유튜브", "a")))
    }

    @Test
    fun `실행이 끝난 뒤에는 화면이 콜백을 바꿀 수 있다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val exec = executor(bridge)
        exec.execute(Command.Search("유튜브", "a"))
        val screenCallback: (PlanOutcome) -> Unit = {}
        exec.setObserverIfIdle(screenCallback)

        assertSame(screenCallback, bridge.currentObserver)
    }

    @Test
    fun `앱 이름이 없는 명령은 맨 앞 화면의 앱으로 보낸다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        executor(bridge, foreground = "kr.co.aladin.third_shop").execute(Command.Tap("로그인"))

        assertEquals("알라딘", JSONObject(bridge.submitted.single()).getString("app"))
    }

    @Test
    fun `앞 화면을 모르면 직전에 연 앱을 쓴다`() = runTest {
        val bridge = FakeBridge(onSubmit = { _, obs -> obs?.invoke(PlanOutcome.Completed) })
        val exec = executor(bridge, foreground = null)
        exec.execute(Command.LaunchApp("com.google.android.youtube"))
        exec.execute(Command.PlayFirst)

        assertEquals("유튜브", JSONObject(bridge.submitted.last()).getString("app"))
    }

    @Test
    fun `앱을 정할 수 없으면 Failed 이고 제출하지 않는다`() = runTest {
        val bridge = FakeBridge()
        val result = executor(bridge, foreground = "com.android.launcher").execute(Command.Tap("로그인"))

        assertTrue(result is ExecutionResult.Failed)
        assertTrue(bridge.submitted.isEmpty())
        assertNull(bridge.currentObserver)
    }
}

