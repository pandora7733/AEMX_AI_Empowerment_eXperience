package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.ScreenDescription
import com.sihoo.aemx.pipeline.model.ScreenSnapshot
import com.sihoo.aemx.pipeline.port.ScreenChange
import com.sihoo.aemx.pipeline.port.ScreenObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.CoroutineContext

/** 어느 한 순간 접근성 서비스가 읽은 화면. [title] 은 창 제목(없으면 루트 클래스명). */
data class ScreenReading(
    val description: ScreenDescription,
    val title: String?
)

/**
 * 화면을 읽어 오는 곳. 실제 구현은 접근성 서비스([ServiceScreenSource]),
 * 테스트에서는 대본대로 돌려주는 가짜로 바꾼다.
 */
interface ScreenSource {
    /** 읽을 수 있는 상태인지(=서비스가 연결돼 있는지). */
    fun isReady(): Boolean

    /** 지금 화면. 읽을 수 없으면 null. 스레드를 가리지 않고 불려도 안전해야 한다. */
    fun read(): ScreenReading?
}

/**
 * [ScreenObserver] 를 접근성 서비스의 화면 읽기 위에 얹은 어댑터.
 *
 * ## 화면을 어떻게 찍나
 *
 * [capture] 는 [ScreenSource] 에서 지금 화면의 글자와 누를 수 있는 라벨을 읽어
 * [ScreenSnapshot] 으로 옮긴다. 서비스가 실패했을 때 재분석용으로 쓰는 요약(`ScreenDescription`)과
 * 같은 방식이라, 성공 때의 화면과 실패 때의 화면이 같은 모양으로 LLM 에 간다.
 *
 * ## 변화를 어떻게 알리나
 *
 * 서비스는 접근성 이벤트를 밖으로 내보내는 길이 없다. 그래서 [changes] 는 이벤트 대신
 * [pollMs] 마다 화면을 다시 읽어 **내용이 달라졌을 때만** 흘려보낸다. 오케스트레이터는
 * "마지막 변화 뒤로 조용한 시간이 얼마나 흘렀나"만 보므로 폴링으로도 충분하다.
 * 서비스에 이벤트 훅이 생기면 이 클래스의 [changes] 만 바꾸면 된다.
 *
 * ## 스레드
 *
 * 화면 읽기는 바인더 호출이 많아 메인 스레드에서 오래 돌리면 안 된다. 폴링은 [pollContext](기본 Default)
 * 에서 돌고, [capture] 는 부르는 쪽 스레드에서 실행된다 — 오케스트레이터를 메인이 아닌
 * 스코프(Default 등)에서 돌려야 한다.
 */
class AccessibilityScreenObserver(
    private val source: ScreenSource,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val pollContext: CoroutineContext = Dispatchers.Default
) : ScreenObserver {

    /** 마지막으로 읽은 화면. 새 구독이 "이미 지난 변화"를 새 변화로 착각하지 않게 기준으로 쓴다. */
    @Volatile
    private var lastSeen: ScreenSnapshot? = null

    override fun isReady(): Boolean = source.isReady()

    override fun capture(): ScreenSnapshot? {
        val reading: ScreenReading? = try {
            source.read()
        } catch (e: RuntimeException) {
            null // 창이 바뀌는 순간 노드가 무효가 되면 던질 수 있다.
        }
        if (reading == null) return null
        return reading.toSnapshot().also { lastSeen = it }
    }

    override val changes: Flow<ScreenChange> = flow {
        var last = lastSeen
        while (true) {
            delay(pollMs)
            val now = capture() ?: continue
            val before = last
            last = now
            if (before == null || !before.sameContent(now)) {
                emit(ScreenChange(kindOf(before, now), now))
            }
        }
    }.flowOn(pollContext)

    private fun kindOf(before: ScreenSnapshot?, now: ScreenSnapshot): ScreenChange.Kind =
        if (before == null || before.packageName != now.packageName || before.className != now.className) {
            ScreenChange.Kind.WINDOW_CHANGED
        } else {
            ScreenChange.Kind.CONTENT_CHANGED
        }

    companion object {
        /** 너무 잦으면 노드 순회 비용이 크고, 너무 뜸하면 정착 판단이 늦어진다. */
        const val DEFAULT_POLL_MS = 250L
    }
}

/**
 * 스냅샷에 담는 글자 수 상한. 서비스의 [com.sihoo.aemx.accessibility.NodeFinder.describeScreen] 이
 * 이미 40개에서 멈추지만, 재분석 프롬프트는 20개만 쓰고([LlamaPlanGenerator] 의 MAX_SCREEN_TEXTS)
 * 화면 검증(`containsText`)은 그보다 더 뒤까지 본다. 그래서 프롬프트 몫(20)보다 여유를 둔 30으로 자른다.
 * 노드를 앞(화면 위쪽)부터 도니 앞쪽이 제목·메뉴·본문이고, 뒤로 갈수록 배너·푸터다.
 */
internal const val MAX_SNAPSHOT_TEXTS = 30

/** 누를 수 있는 라벨 상한. 프롬프트가 쓰는 개수(20)와 맞춘다. 더 줘도 LLM 은 잘라 버린다. */
internal const val MAX_SNAPSHOT_CLICKABLES = 20

/** 입력칸 이름 상한. 검색창 하나를 고르는 데 충분하다. */
internal const val MAX_SNAPSHOT_INPUTS = 5

internal fun ScreenReading.toSnapshot(): ScreenSnapshot = ScreenSnapshot(
    packageName = description.packageName,
    className = title,
    texts = description.texts.take(MAX_SNAPSHOT_TEXTS),
    clickableLabels = description.clickableLabels.take(MAX_SNAPSHOT_CLICKABLES),
    inputLabels = description.inputLabels.take(MAX_SNAPSHOT_INPUTS),
    // 다이얼로그는 접근성 창 정보만으로 믿고 가를 수 없어 아직 판정하지 않는다.
    isDialog = false
)

/**
 * 시각([ScreenSnapshot.capturedAt])을 뺀 "의미 있는" 내용이 같은지. 변화 감지 전용이다.
 *
 * 일반 글자는 비교하지 않는다. 배너·시계·카운터처럼 계속 바뀌는 글자 때문에 오케스트레이터가
 * 안정(조용한 시간)을 못 얻던 문제(실기기 네이버 앱, 노드 약 1500개)를 피하려는 것이다.
 * 비교하는 것은 패키지, 창 제목, 누를 수 있는 라벨 모음(순서 무시)이다.
 *
 * 진짜 화면 전환을 놓치지 않는 근거: 화면이 넘어가면 패키지나 창 제목이 바뀌거나, 누를 수 있는
 * 버튼·탭·항목 라벨이 바뀐다. 글자만 바뀌고 누를 것은 그대로인 경우는 보통 같은 화면의 갱신이다.
 * 다만 누를 수 있는 라벨이 양쪽 모두 비어 있으면 붙잡을 기준이 없으므로 글자까지 비교한다.
 *
 * 이건 "변화가 있었다"는 신호만 둔하게 만든다. 안정 판정 뒤 오케스트레이터가 [capture] 로 다시
 * 찍는 최종 화면은 글자를 전부 담고 있어, 검증(`ScreenVerifier`)은 최신 글자를 본다.
 */
internal fun ScreenSnapshot.sameContent(other: ScreenSnapshot): Boolean {
    if (packageName != other.packageName || className != other.className) return false
    if (clickableLabels.toSet() != other.clickableLabels.toSet()) return false
    if (clickableLabels.isEmpty() && other.clickableLabels.isEmpty()) return texts == other.texts
    return true
}
