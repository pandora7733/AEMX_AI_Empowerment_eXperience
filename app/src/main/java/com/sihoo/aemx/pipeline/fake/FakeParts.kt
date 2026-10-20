package com.sihoo.aemx.pipeline.fake

import com.sihoo.aemx.pipeline.json.ActionPlanParser
import com.sihoo.aemx.pipeline.json.ParseResult
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ActionExecutor
import com.sihoo.aemx.pipeline.port.AppResolver
import com.sihoo.aemx.pipeline.port.ExecutionResult
import com.sihoo.aemx.pipeline.port.PlanGenerator
import com.sihoo.aemx.pipeline.port.PlanRequest
import com.sihoo.aemx.pipeline.port.PlanResult
import com.sihoo.aemx.pipeline.port.ScreenChange
import com.sihoo.aemx.pipeline.port.ScreenObserver
import com.sihoo.aemx.pipeline.port.SpeechResult
import com.sihoo.aemx.pipeline.port.SpeechSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 아직 합류하지 않은 파트를 대신하는 가짜 구현들.
 *
 * STT·LLM·실행모듈이 모두 다른 브랜치에 있는 동안에도 파이프라인 전체 흐름을
 * 돌려 보고 단위테스트로 검증하기 위한 것이다. 실제 모듈이 준비되면
 * 같은 인터페이스를 구현한 어댑터로 갈아 끼우기만 하면 된다.
 */

/**
 * 미리 정해 둔 응답을 순서대로 내놓는 가짜 LLM.
 *
 * 응답 문자열은 실제 프롬프트가 내놓는 형식 그대로 쓴다
 * (`LLM/system_prompt.txt` 의 예시 출력과 동일한 모양).
 */
class FakePlanGenerator(
    private val responses: List<String>,
    /** 실기기 추론이 20~35초 걸리는 것을 흉내 낸다. 테스트에서는 0 으로 둔다. */
    private val latencyMs: Long = 0L
) : PlanGenerator {

    /** 어떤 요청이 들어왔는지 확인하기 위한 기록. */
    val requests = mutableListOf<PlanRequest>()

    private var cursor = 0

    override fun isReady(): Boolean = true

    override suspend fun generate(request: PlanRequest): PlanResult {
        requests += request
        if (latencyMs > 0) delay(latencyMs)

        val raw = responses.getOrNull(cursor)
            ?: return PlanResult.EngineError("준비된 가짜 응답이 더 없다")
        cursor++

        return when (val parsed = ActionPlanParser.parse(raw)) {
            is ParseResult.Success ->
                PlanResult.Success(parsed.plan, raw, parsed.warnings)

            is ParseResult.Failure ->
                PlanResult.Malformed(raw, parsed.reason)
        }
    }
}

/** 받은 명령을 기록만 하는 가짜 실행모듈. */
class FakeActionExecutor(
    private var ready: Boolean = true,
    /** 특정 명령에 대해 실패를 흉내 내고 싶을 때. */
    private val resultFor: (ActionExecutor.Command) -> ExecutionResult = { ExecutionResult.Performed }
) : ActionExecutor {

    val performed = mutableListOf<ActionExecutor.Command>()

    override fun isReady(): Boolean = ready

    override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
        performed += command
        return resultFor(command)
    }

    fun setReady(value: Boolean) {
        ready = value
    }
}

/**
 * 명령을 받을 때마다 대본에 적힌 화면으로 넘어가는 가짜 실행모듈.
 *
 * 실제 조작이 화면을 바꾸는 효과를 흉내 낸다. [screensAfterEach] 의 n번째 값이
 * n번째 명령을 수행한 뒤의 화면이 되고, **null 이면 화면이 그대로다** —
 * 눌렀는데 아무 반응이 없는 경우를 만들어 재분석 경로를 태울 수 있다.
 * 대본이 동나면 그 뒤로는 화면이 바뀌지 않는다.
 */
class ScriptedActionExecutor(
    private val observer: FakeScreenObserver,
    private val screensAfterEach: List<ScreenSnapshot?>,
    private val ready: Boolean = true
) : ActionExecutor {

    val performed = mutableListOf<ActionExecutor.Command>()

    override fun isReady(): Boolean = ready

    override suspend fun execute(command: ActionExecutor.Command): ExecutionResult {
        val next = screensAfterEach.getOrNull(performed.size)
        performed += command
        if (next != null) observer.advanceTo(next)
        return ExecutionResult.Performed
    }
}

/**
 * 대본대로 화면을 바꿔 주는 가짜 화면 관찰자.
 *
 * [advanceTo] 를 부르면 그 화면으로 바뀌고 변화 이벤트를 흘린다.
 * 아무도 바꾸지 않으면 같은 화면이 유지돼 "화면이 안 바뀌었다" 케이스를 만들 수 있다.
 */
class FakeScreenObserver(
    initial: ScreenSnapshot = ScreenSnapshot.EMPTY,
    private var ready: Boolean = true
) : ScreenObserver {

    private var current: ScreenSnapshot = initial
    private val _changes = MutableSharedFlow<ScreenChange>(extraBufferCapacity = 64)

    override fun isReady(): Boolean = ready

    override val changes: Flow<ScreenChange> = _changes.asSharedFlow()

    override fun capture(): ScreenSnapshot? = if (ready) current else null

    suspend fun advanceTo(
        snapshot: ScreenSnapshot,
        kind: ScreenChange.Kind = ScreenChange.Kind.WINDOW_CHANGED
    ) {
        current = snapshot
        _changes.emit(ScreenChange(kind, snapshot))
    }
}

/** 앱 이름 앞에 고정 접두사를 붙여 패키지명을 흉내 내는 가짜 해석기. */
class FakeAppResolver(
    private val known: Map<String, String> = mapOf(
        "알라딘" to "com.example.fake.aladin",
        "교보문고" to "com.example.fake.kyobo",
        "유튜브" to "com.example.fake.youtube"
    )
) : AppResolver {
    override fun resolvePackage(appName: String): String? = known[appName.trim()]
}

/** 원할 때 인식 결과를 내보내는 가짜 STT. */
class FakeSpeechSource(private var ready: Boolean = true) : SpeechSource {

    private val _results = MutableSharedFlow<SpeechResult>(extraBufferCapacity = 16)
    var started = false
        private set

    override fun isReady(): Boolean = ready

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    override val results: Flow<SpeechResult> = _results.asSharedFlow()

    suspend fun emitPartial(text: String) = _results.emit(SpeechResult.Partial(text))

    suspend fun emitFinal(text: String) = _results.emit(SpeechResult.Final(text))
}
