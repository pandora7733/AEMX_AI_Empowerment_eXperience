package com.sihoo.aemx.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * 카카오톡 공유 선택 화면의 판정 로직 중 안드로이드 없이 돌릴 수 있는 부분.
 *
 * 원칙은 [MessageRules] 와 같다. 맞으면 보내고 불확실하면 실패한다. 이름 일치는 호출한 쪽이
 * 넘기는 [NameMatch] 로 한다(보통 [MessageRules.sameText] 또는 그것을 넓힌 함수).
 *
 * 실기기 dump(카톡 26.9.1, 공유 선택 화면)로 확인한 행 모양:
 * - 검색 결과 행: id chat_room_list_item 의 ViewGroup(clickable). content-desc 는 "이름, 마지막 말…" 이고
 *   "나와의 채팅"은 "나, 이름, …" 이다. 상태 말이 없다.
 * - 전체 목록 행: id chat_room_list_item 의 CheckBox(clickable). content-desc 는 앞에 "선택안함, " 또는
 *   "선택함, " 이 붙고, 안의 id check_box 가 checked 다.
 * - 행 안에 id name(TextView)이 이름이고, 단체방은 id members_count(TextView, 인원 수)가 더 있다.
 *   content-desc 로는 이름 다음 조각이 "6"(숫자만)이거나 "오픈채팅 29명" 모양이다.
 */
internal object KakaoShareRules {
    /** (찾는 이름, 화면에 보이는 이름) -> 같은 사람인가. */
    fun interface NameMatch {
        fun matches(wanted: String, shown: String): Boolean
    }

    /** 공유 선택 화면의 행 하나를 읽은 값. */
    data class RowInfo(
        val desc: String?,
        /** 행 안 id name 의 글자. 없으면 null. */
        val name: String?,
        /** 행 안에 id members_count 가 있는가(단체방 표시). */
        val hasMembersCount: Boolean,
        /** 행 안 id check_box 가 checked 인가. */
        val checked: Boolean,
    )

    sealed interface RowPick {
        data class Found(val index: Int) : RowPick
        data object None : RowPick

        /** 이름이 맞는 행이 단체방뿐이다. */
        data object GroupOnly : RowPick
        data class Ambiguous(val count: Int) : RowPick
    }

    private const val SELECTED_STATE = "선택함"
    private const val UNSELECTED_STATE = "선택안함"
    private val MEMBER_COUNT_PIECE = Regex("^\\d+$|\\d+\\s*명")

    /** content-desc 를 쉼표로 나눠 다듬는다. 맨 앞 상태 말("선택함"/"선택안함")은 뺀다. */
    fun pieces(desc: String?): List<String> {
        if (desc.isNullOrBlank()) return emptyList()
        val all = desc.split(',').map(String::trim).filter(String::isNotEmpty)
        return if (all.firstOrNull() == SELECTED_STATE || all.firstOrNull() == UNSELECTED_STATE) all.drop(1) else all
    }

    /** 행의 선택 상태. content-desc 앞 상태 말을 못 읽으면 null. */
    fun selectedByDesc(desc: String?): Boolean? = when (desc?.split(',')?.firstOrNull()?.trim()) {
        SELECTED_STATE -> true
        UNSELECTED_STATE -> false
        else -> null
    }

    /**
     * 행에 보이는 이름 후보. 대화 상대 이름은 상태 말을 뺀 첫 조각이고, "나와의 채팅"은 첫 조각이 "나" 라서
     * 둘째 조각이 이름이다. 이름 위치를 못 정하면 빈 목록이다.
     */
    private fun nameIndex(pieces: List<String>): Int = if (pieces.firstOrNull() == "나" && pieces.size > 1) 1 else 0

    /** 단체방(또는 오픈채팅)처럼 보이는가. 의심스러우면 true 로 둔다. */
    fun isGroup(row: RowInfo): Boolean {
        if (row.hasMembersCount) return true
        val pieces = pieces(row.desc)
        val next = pieces.getOrNull(nameIndex(pieces) + 1) ?: return false
        return MEMBER_COUNT_PIECE.containsMatchIn(next)
    }

    /**
     * 행의 이름이 [wanted] 와 같은가. content-desc 의 이름 조각과 id name 글자가 둘 다 있으면 둘 다 맞아야 한다.
     * 마지막 말 같은 다른 조각에 이름이 있다고 맞는 것으로 보지 않는다.
     */
    fun rowNameMatches(row: RowInfo, wanted: String, match: NameMatch): Boolean {
        val pieces = pieces(row.desc)
        val fromDesc = pieces.getOrNull(nameIndex(pieces))
        val fromName = row.name?.trim()?.takeIf(String::isNotEmpty)
        if (fromDesc == null && fromName == null) return false
        if (fromDesc != null && !match.matches(wanted, fromDesc)) return false
        if (fromName != null && !match.matches(wanted, fromName)) return false
        return true
    }

    /** 행에 보이는 실제 이름. id name 의 글자를 먼저, 없으면 content-desc 의 이름 조각. 못 읽으면 null. */
    fun shownName(row: RowInfo): String? {
        val pieces = pieces(row.desc)
        return row.name?.trim()?.takeIf(String::isNotEmpty) ?: pieces.getOrNull(nameIndex(pieces))
    }

    /**
     * 이름이 맞고 단체방이 아닌 행이 정확히 하나일 때만 [RowPick.Found].
     * [match] 로 맞는 행이 하나도 없을 때만 [loose] 로 다시 찾는다("21105홍길동" 처럼 숫자·기호가 붙은 이름).
     * [match] 로 맞는 행이 있으면(단체방뿐이어도) [loose] 는 보지 않는다.
     */
    fun pickRow(
        rows: List<RowInfo>,
        wanted: String,
        match: NameMatch,
        loose: NameMatch? = null,
        alternate: String? = null,
    ): RowPick {
        val first = pickRowFor(rows, wanted, match, loose)
        // 말한 이름으로 하나도 안 맞을 때만 숫자를 정리한 이름([alternate])으로 한 번 더 찾는다.
        return if (first == RowPick.None && alternate != null) pickRowFor(rows, alternate, match, loose) else first
    }

    private fun pickRowFor(rows: List<RowInfo>, wanted: String, match: NameMatch, loose: NameMatch?): RowPick {
        val named = rows.withIndex().filter { rowNameMatches(it.value, wanted, match) }
            .ifEmpty {
                if (loose == null) emptyList() else rows.withIndex().filter { rowNameMatches(it.value, wanted, loose) }
            }
        if (named.isEmpty()) return RowPick.None
        val people = named.filter { !isGroup(it.value) }
        return when {
            people.isEmpty() -> RowPick.GroupOnly
            people.size == 1 -> RowPick.Found(people.single().index)
            else -> RowPick.Ambiguous(people.size)
        }
    }

    /**
     * 보내기 버튼을 누르기 전에 카톡 화면을 벗어나거나 공유 시트가 사라졌을 때 처음부터 다시 할지.
     * 보내기를 누른 뒤([KakaoShareFlow.Phase.SENT])에는 절대 다시 하지 않는다. 이미 보내졌을 수 있어서 두 번 보내질 수 있다.
     * 다시 하는 것은 한 번뿐이고, 전체 제한 시간이 [MIN_RETRY_REMAINING_MS] 이상 남았을 때만 한다.
     *
     * @param attemptsUsed 지금까지 다시 한 횟수
     * @param remainingMs 전체 제한 시간 중 남은 시간
     */
    fun mayRetry(phase: KakaoShareFlow.Phase, attemptsUsed: Int, remainingMs: Long): Boolean =
        phase != KakaoShareFlow.Phase.SENT && attemptsUsed < MAX_RETRIES && remainingMs >= MIN_RETRY_REMAINING_MS

    /** 공유 시트의 요소가 안 보인 지 [SHEET_GONE_MS] 가 지났으면 시트가 사라진 것으로 본다(화면 전환 중 잠깐 비는 것은 넘긴다). */
    fun sheetGone(sinceGoneMs: Long): Boolean = sinceGoneMs >= SHEET_GONE_MS

    const val MAX_RETRIES = 1
    const val MIN_RETRY_REMAINING_MS = 20_000L
    const val SHEET_GONE_MS = 1_500L

    sealed interface OpenMove {
        data object Wait : OpenMove

        /** 홈으로 나갔다가 공유 인텐트를 한 번 더 연다. */
        data object Retry : OpenMove
        data object Fail : OpenMove
    }

    /**
     * 공유 화면이 뜨기를 기다리는 동안의 판정. 인텐트를 연 지 [OPEN_WAIT_MS] 가 지나도 화면이 안 보이면 한 번만
     * 다시 열고, 다시 연 뒤에도 안 보이면 실패한다. 이미 떠 있는 카톡 task 로 인텐트가 들어가면 공유 시트가 안 뜰 수 있다.
     *
     * @param sinceOpenMs 마지막으로 인텐트를 연 뒤 지난 시간(다시 열기 전 기다리는 동안은 음수일 수 있다)
     */
    fun openMove(sinceOpenMs: Long, retried: Boolean): OpenMove = when {
        sinceOpenMs < OPEN_WAIT_MS -> OpenMove.Wait
        !retried -> OpenMove.Retry
        else -> OpenMove.Fail
    }

    const val OPEN_WAIT_MS = 3_000L

    /** 검색란을 깨우는 방법. 순서대로 한 번씩만 쓴다. */
    enum class SearchStage { OPEN, PASTE, FOCUS, ENTER }

    sealed interface SearchMove {
        data object Wait : SearchMove

        /** 이 방법으로 넘어간다. */
        data class Advance(val to: SearchStage) : SearchMove

        /** 방법이 다 떨어졌다. 검색을 버리고 전체 목록에서 찾는다. */
        data object GiveUp : SearchMove
    }

    /**
     * 카톡 공유 검색은 SET_TEXT 만으로는 돌지 않는다(실기기 확인). 사람이 검색란에 한 번 들어갔다 나오면
     * 결과가 뜨는 걸로 보아 입력 끝 신호가 포커스 변화로 들어가는 것 같다. 그래서 순서대로 한 번씩 시도한다.
     *
     * 1. [SearchStage.OPEN]  클립보드에 이름을 넣고 검색란을 누른다(열기만 해도 결과가 뜨는 것을 사용자가 확인했다)
     * 2. [SearchStage.PASTE] 붙여넣기를 보낸다
     * 3. [SearchStage.FOCUS] 포커스를 넣고 SET_TEXT 한 뒤 포커스를 뺐다가 다시 넣고 뺀다
     * 4. [SearchStage.ENTER] 엔터를 보낸다(API 30 부터. 그 아래에서는 건너뛴다)
     * 5. 그래도 안 되면 포기하고 전체 목록을 훑는다
     *
     * 각 방법을 쓴 뒤 [WATCH_MS](엔터는 [RESULT_AFTER_ENTER_MS]) 동안 결과 행이 뜨는지 본다. 행이 하나라도 보이면
     * (이름이 안 맞아도) 더 시도하지 않는다.
     *
     * @param stage 마지막으로 쓴 방법(아직 안 썼으면 null)
     * @param rowCount 검색어가 반영된 상태에서 보이는 검색 결과 행 수
     * @param sinceStageMs 그 방법을 쓴 뒤 지난 시간
     */
    fun searchMove(stage: SearchStage?, rowCount: Int, sinceStageMs: Long, enterSupported: Boolean): SearchMove {
        if (stage == null) return SearchMove.Advance(SearchStage.OPEN)
        if (rowCount > 0) return SearchMove.Wait
        val watch = if (stage == SearchStage.ENTER) RESULT_AFTER_ENTER_MS else WATCH_MS
        if (sinceStageMs < watch) return SearchMove.Wait
        return when (stage) {
            SearchStage.OPEN -> SearchMove.Advance(SearchStage.PASTE)
            SearchStage.PASTE -> SearchMove.Advance(SearchStage.FOCUS)
            SearchStage.FOCUS -> if (enterSupported) SearchMove.Advance(SearchStage.ENTER) else SearchMove.GiveUp
            SearchStage.ENTER -> SearchMove.GiveUp
        }
    }

    const val WATCH_MS = 1_500L
    const val RESULT_AFTER_ENTER_MS = 4_000L

    /** 전체 목록을 훑는 화면 수 상한. 끝에 닿기 전에 이 수를 넘기면 끝까지 확인하지 못한 것이다. */
    const val MAX_SCAN_SCREENS = 10

    /**
     * 검색 없이 전체 목록을 스크롤하며 본 행을 모은다. 같은 행은 한 번만 센다(같은 사람이 화면마다 다시 보이므로).
     * 행의 같음은 상태 말을 뺀 content-desc 로 판단한다. 설명까지 똑같은 서로 다른 두 사람은 한 행으로 합쳐진다.
     */
    class ListScan(private val maxScreens: Int = MAX_SCAN_SCREENS) {
        enum class State { MORE, END, TOO_LONG }

        private val seen = LinkedHashMap<String, RowInfo>()
        private var lastKeys: List<String>? = null
        private var ended = false

        var screens = 0
            private set

        /** 지금 화면에 보이는 행을 더한다. 직전 화면과 행이 같으면 더 내려갈 곳이 없는 것으로 본다. */
        fun add(visible: List<RowInfo>) {
            screens++
            val keys = visible.map { key(it) }
            if (keys == lastKeys) ended = true
            lastKeys = keys
            visible.forEach { seen.putIfAbsent(key(it), it) }
        }

        /** 스크롤 액션이 거절됐다. 끝에 닿은 것으로 본다. */
        fun markEnd() {
            ended = true
        }

        val state: State
            get() = when {
                ended -> State.END
                screens >= maxScreens -> State.TOO_LONG
                else -> State.MORE
            }

        fun rows(): List<RowInfo> = seen.values.toList()

        /** 끝까지 훑은 뒤에만 의미가 있다. */
        fun pick(wanted: String, match: NameMatch, loose: NameMatch?, alternate: String? = null): RowPick =
            pickRow(rows(), wanted, match, loose, alternate)
    }

    /** 같은 행인지 가리는 값. 상태 말을 뺀 content-desc, 없으면 이름 글자. */
    fun key(row: RowInfo): String = pieces(row.desc).joinToString(",").ifEmpty { row.name.orEmpty() }

    /** 보내기 버튼 글자 "1 보내기" 에서 고른 대상 수. 못 읽으면 null. */
    fun submitCount(text: String?): Int? {
        val trimmed = text?.trim() ?: return null
        val match = Regex("^(\\d+)\\s*보내기$").find(trimmed) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    sealed interface CheckVerdict {
        data object Ready : CheckVerdict
        data class Reject(val reason: String) : CheckVerdict
    }

    /**
     * 보내기 직전 확인. 다음이 모두 맞을 때만 [CheckVerdict.Ready].
     * - 보내기 버튼의 대상 수가 1
     * - 위쪽에 고른 대상 칩(id name_text)이 정확히 하나이고 이름이 맞다
     * - 화면에 보이는 체크된 행은 0개여도 된다. 체크하면 검색어가 지워지고 전체 목록이 다시 떠서, 체크된 행이
     *   화면 밖에 있을 수 있다(dump 확인). 보이는 것이 있으면 모두 이름이 맞고 단체방이 아니어야 한다
     *   (체크 표시는 id check_box 의 checked 와 content-desc 의 "선택함" 둘 다 본다)
     */
    fun verifyChecked(
        rows: List<RowInfo>,
        chipNames: List<String>,
        submitText: String?,
        wanted: String,
        match: NameMatch,
    ): CheckVerdict {
        val count = submitCount(submitText)
            ?: return CheckVerdict.Reject("보내기 버튼의 대상 수를 읽지 못했습니다.")
        if (count != 1) return CheckVerdict.Reject("고른 대상이 ${count}개라 보내지 않았습니다.")
        if (chipNames.size != 1 || !match.matches(wanted, chipNames.single().trim())) {
            return CheckVerdict.Reject("고른 대상의 이름이 \"$wanted\" 와(과) 달라 보내지 않았습니다.")
        }
        val checked = rows.filter { it.checked || selectedByDesc(it.desc) == true }
        for (row in checked) {
            if (!row.checked || selectedByDesc(row.desc) == false) {
                return CheckVerdict.Reject("체크 표시가 서로 맞지 않아 보내지 않았습니다.")
            }
            if (!rowNameMatches(row, wanted, match)) {
                return CheckVerdict.Reject("체크된 행의 이름이 \"$wanted\" 와(과) 달라 보내지 않았습니다.")
            }
            if (isGroup(row)) return CheckVerdict.Reject("단체방이라 보내지 않았습니다.")
        }
        return CheckVerdict.Ready
    }
}

/**
 * 카카오톡 공유 인텐트로 메시지를 보낸다.
 *
 * 카톡 26.9.1 부터 채팅방 화면의 접근성 노드가 비어서 채팅방에 들어가지 않고, 공유 선택 화면에서
 * 받는 사람을 골라 보낸다. 흐름은 이렇다.
 * 1. ACTION_SEND text/plain(EXTRA_TEXT=내용) 을 카톡에 연다.
 * 2. 검색란(id edit_text)에 받는 사람 이름을 넣는다.
 * 3. 검색 결과(id search_list)에서 이름이 맞는 단체방 아닌 행이 정확히 하나로 잠시 유지되면 그 행을 누른다.
 * 4. 고른 대상이 그 사람 하나뿐인지 다시 확인하고(칩, 체크된 행, 보내기 버튼의 개수) 보내기를 누른다.
 * 5. 공유 화면이 닫히면 성공이다. 확인 창이 뜨면 그 안에 이름이 보일 때만 한 번 누른다.
 *
 * 보내기를 누른 뒤에는 다시 누르지 않는다. 같은 메시지를 두 번 보내지 않기 위해서다.
 *
 * 확인 창과 보낸 직후 화면은 dump 가 없어서 추정이다. 알아보지 못하면 실패로 끝낸다.
 */
internal class KakaoShareFlow(
    private val service: AemxAccessibilityService,
    private val handler: Handler,
    private val nameMatch: KakaoShareRules.NameMatch = KakaoShareRules.NameMatch(MessageRules::sameText),
    /** 정확히 같은 이름이 없을 때만 쓰는 넓은 판정. 없으면 쓰지 않는다. */
    private val looseMatch: KakaoShareRules.NameMatch? = null,
) {
    internal enum class Phase { OPEN, TYPE, RESULT, CLEAR, SCAN, BACK, CHECK, SENT }

    private sealed interface Outcome {
        data object Wait : Outcome
        data object Done : Outcome

        /** 보내기 전에 화면을 잃어서 처음부터 한 번 다시 한다. */
        data object Restart : Outcome
        data class Fail(val message: String) : Outcome
    }

    /** 화면에서 읽은 행: 눌러야 할 노드와 판정용 값. */
    private class Row(val node: AccessibilityNodeInfo, val info: KakaoShareRules.RowInfo)

    private class Snapshot(
        val editText: AccessibilityNodeInfo?,
        val searchRows: List<Row>,
        val listRows: List<Row>,
        val chipNames: List<String>,
        val submit: AccessibilityNodeInfo?,
        val clearButton: AccessibilityNodeInfo?,
        val scrollList: AccessibilityNodeInfo?,
        val pickerVisible: Boolean,
        val otherTexts: List<String>,
        val buttons: List<AccessibilityNodeInfo>,
    )

    /**
     * @param recipient 받는 사람 이름(대화 목록에 보이는 이름)
     * @param value 보낼 내용
     * @param isCurrent 이 실행이 아직 유효한가(아니면 조용히 멈춘다)
     * @param onDone 보내기를 누르고 공유 화면이 닫힌 것을 확인했다
     * @param onFailed 보내지 않았거나 보냈는지 확인하지 못했다(메시지는 사람이 읽는 사유)
     */
    fun send(
        recipient: String,
        value: String,
        isCurrent: () -> Boolean,
        onDone: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (recipient.isBlank()) return onFailed("받는 사람이 비어 있습니다.")
        if (value.isBlank()) return onFailed("보낼 내용이 비어 있습니다.")
        Log.i(TAG, "카톡 공유 시작")
        try {
            if (!startShare(value, fresh = false)) return onFailed("카카오톡 공유 화면을 열지 못했습니다.")
            handler.post(Run(recipient, value, isCurrent, onDone, onFailed, attempt = 0, overallStart = SystemClock.uptimeMillis()))
        } catch (error: Throwable) {
            // 어떤 예외든 호출한 쪽이 끝나지 않는 채로 남지 않게 실패로 알린다.
            Log.e(TAG, "카톡 공유를 시작하지 못했다", error)
            onFailed("카톡 공유를 시작하지 못했습니다.")
        }
    }

    /**
     * 카톡 공유 인텐트를 연다.
     *
     * 처음에는 NEW_TASK + MULTIPLE_TASK 다. NEW_TASK 만 쓰면 카톡이 이미 떠 있을 때 그 task 로 인텐트가 들어가
     * 공유 시트가 안 뜬 적이 있다(실기기 확인). MULTIPLE_TASK 는 NEW_TASK 와 같이 쓰면 이미 있는 task 를 앞으로
     * 가져오지 않고 새 task 를 만든다. 사용자가 보던 카톡 화면은 건드리지 않아서 이쪽을 먼저 쓴다.
     * 그래도 안 뜨면 [fresh] 로 다시 열 때 CLEAR_TASK 를 더해 카톡 task 를 비우고 새로 띄운다. 보던 화면을 잃지만
     * 공유 화면이 뜨는 것은 확실하다.
     */
    private fun startShare(value: String, fresh: Boolean): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            setPackage(AppLauncher.KAKAOTALK_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        return try {
            service.startActivity(intent)
            true
        } catch (error: RuntimeException) {
            false
        }
    }

    private inner class Run(
        private val recipient: String,
        private val value: String,
        private val isCurrent: () -> Boolean,
        private val onDone: () -> Unit,
        private val onFailed: (String) -> Unit,
        private val attempt: Int,
        overallStart: Long,
    ) : Runnable {
        /** 전체 제한 시간의 시작. 다시 하는 실행은 처음 실행의 시작을 이어받아 전체가 한도를 넘지 않게 한다. */
        private val startedAt = overallStart
        private var phase = Phase.OPEN
        private var openedAt = SystemClock.uptimeMillis()
        private var goneSince = 0L
        private var finished = false

        /** 이 실행이 예약한 모든 지연 작업의 표. 끝나면 한꺼번에 지워서 다음 실행에 새어 나가지 않게 한다. */
        private val token = Any()
        private var retried = false
        private var phaseSince = startedAt
        private var typedAt = 0L
        private var stableKey: String? = null
        private var stableSince = 0L
        private var readySince = 0L
        private var sentAt = 0L
        private var confirmTapped = false
        private var stage: KakaoShareRules.SearchStage? = null
        private var stageAt = 0L
        private var previousClip: ClipData? = null
        private val scan = KakaoShareRules.ListScan()
        private var targetKey: String? = null
        private var lastScrollAt = 0L
        private var backScrolls = 0
        private var clearedAt = 0L

        /**
         * 말한 이름에서 한글 숫자를 바꾼 이름. 바뀐 게 없으면 null. 행을 고를 때 말한 이름으로 하나도 안 맞으면 이걸로 한 번 더 찾는다.
         * 검색란에는 이 이름을 넣는다(없으면 말한 이름). 검색에서 안 나와도 전체 목록 훑기에서 말한 이름으로 다시 찾는다.
         */
        private val alternate: String? = MessageRules.digitizeName(recipient).takeIf { it != recipient }
        private val query: String = alternate ?: recipient

        /** 누른 행의 실제 이름. 느슨하게 찾았을 때 칩·체크된 행도 이 이름으로 다시 확인한다. */
        private var chosenName: String? = null

        private fun goto(next: Phase) {
            phase = next
            phaseSince = SystemClock.uptimeMillis()
            if (next == Phase.CHECK) restoreClipboard()
            stableKey = null
            Log.i(TAG, "카톡 공유 단계: $next")
        }

        private fun fail(message: String) = onFailed(message)

        /** 성공이나 실패 콜백은 한 번만 부른다. 부르기 전에 예약한 작업과 클립보드를 정리한다. */
        private fun finish(block: () -> Unit) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(token)
            restoreClipboard()
            block()
        }

        override fun run() {
            if (finished) return
            if (!isCurrent()) {
                // 계획이 취소됐다. 취소한 쪽이 이미 알고 있으니 콜백은 부르지 않는다.
                Log.i(TAG, "카톡 공유 취소로 멈춤: $phase")
                finished = true
                handler.removeCallbacksAndMessages(token)
                restoreClipboard()
                return
            }
            val now = SystemClock.uptimeMillis()
            val outcome = try {
                advance(now)
            } catch (error: RuntimeException) {
                Log.e(TAG, "카톡 공유 중 오류: $phase", error)
                Outcome.Fail("화면 조작 중 오류가 발생했습니다.")
            }
            when (outcome) {
                Outcome.Done -> finish {
                    Log.i(TAG, "카톡 공유 끝: 성공")
                    onDone()
                }
                is Outcome.Fail -> finish {
                    Log.i(TAG, "카톡 공유 끝: 실패(${outcome.message}) 단계=$phase")
                    onFailed(outcome.message)
                }
                Outcome.Restart -> restart()
                Outcome.Wait -> {
                    val deadline = if (phase == Phase.SENT) sentAt + VERIFY_MS else startedAt + TIMEOUT_MS
                    if (now >= deadline) {
                        finish {
                            Log.i(TAG, "카톡 공유 끝: 시간 초과 단계=$phase")
                            onFailed(timeoutMessage())
                        }
                    } else {
                        if (phase == Phase.OPEN && now - lastBeatAt >= HEARTBEAT_MS) {
                            lastBeatAt = now
                            Log.i(TAG, "카톡 공유 화면을 기다리는 중 ${now - startedAt}ms")
                        }
                        handler.postDelayed(this, token, POLL_MS)
                    }
                }
            }
        }

        private var lastBeatAt = 0L

        /**
         * 보내기 전에 화면을 잃었다. 인텐트를 새 task 로 다시 열고 처음부터 한 번 더 한다.
         * 여기까지 온 것은 [KakaoShareRules.mayRetry] 가 허용한 경우뿐이다.
         */
        private fun restart() {
            Log.i(TAG, "카톡 공유 화면을 잃어서 처음부터 다시 한다: 단계=$phase")
            handler.removeCallbacksAndMessages(token)
            restoreClipboard()
            finished = true
            val reopened = try {
                startShare(value, fresh = true)
            } catch (error: Throwable) {
                false
            }
            if (!reopened) {
                onFailed("카카오톡 공유 화면을 열지 못했습니다.")
                return
            }
            handler.post(Run(recipient, value, isCurrent, onDone, onFailed, attempt + 1, startedAt))
        }

        /** 보내기 전이면 한 번만 다시 하고, 아니면 실패한다. */
        private fun retryOrFail(message: String, now: Long): Outcome =
            if (KakaoShareRules.mayRetry(phase, attempt, startedAt + TIMEOUT_MS - now)) Outcome.Restart else Outcome.Fail(message)

        private fun timeoutMessage(): String = when (phase) {
            Phase.OPEN -> "카카오톡 공유 화면이 열리지 않았습니다."
            Phase.TYPE -> "카카오톡 공유 화면의 검색란을 찾지 못했습니다."
            Phase.RESULT -> "카카오톡 공유 화면에서 \"$recipient\" 을(를) 찾지 못했습니다."
            Phase.CLEAR -> "검색어를 지우지 못해 보내지 않았습니다."
            Phase.SCAN -> "카카오톡 전체 목록을 훑다가 시간이 다 되어 보내지 않았습니다."
            Phase.BACK -> "찾은 \"$recipient\" 행으로 돌아가지 못해 보내지 않았습니다."
            Phase.CHECK -> "\"$recipient\" 선택을 확인하지 못해 보내지 않았습니다."
            Phase.SENT -> SENT_UNVERIFIED
        }

        /** 공유 화면이 뜨기를 기다린다. 안 뜨면 홈으로 나갔다가 한 번만 다시 열고, 그래도 안 뜨면 바로 실패한다. */
        private fun opening(now: Long): Outcome = when (KakaoShareRules.openMove(now - openedAt, retried)) {
            KakaoShareRules.OpenMove.Wait -> Outcome.Wait
            KakaoShareRules.OpenMove.Retry -> {
                retried = true
                Log.i(TAG, "공유 화면이 안 떠서 홈으로 나갔다가 다시 연다")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                openedAt = now + HOME_WAIT_MS
                handler.postDelayed({ if (isCurrent()) startShare(value, fresh = true) }, token, HOME_WAIT_MS)
                Outcome.Wait
            }
            KakaoShareRules.OpenMove.Fail -> Outcome.Fail("카톡 공유 화면이 열리지 않아 보내지 않았습니다.")
        }

        private fun advance(now: Long): Outcome {
            val root = service.rootInActiveWindow
            val inKakao = root?.packageName?.toString() == AppLauncher.KAKAOTALK_PACKAGE
            if (phase == Phase.OPEN && (root == null || !inKakao || !read(root).pickerVisible)) return opening(now)
            if (root == null) return Outcome.Wait
            if (phase == Phase.SENT) {
                // 공유 화면이 닫혔는지는 카톡 밖으로 나갔거나, 카톡 안이어도 공유 화면의 요소가 사라졌는지로 본다.
                if (!inKakao) return Outcome.Done
                return sent(read(root), now)
            }
            if (!inKakao) {
                return if (phase == Phase.OPEN) Outcome.Wait else retryOrFail("카카오톡 화면을 벗어나 보내지 않았습니다.", now)
            }
            val snapshot = read(root)
            if (!snapshot.pickerVisible) {
                // 공유 시트가 사라졌다. 잠깐 비는 것은 넘기고, 이어서 안 보이면 한 번 처음부터 다시 한다.
                if (phase == Phase.OPEN) return Outcome.Wait
                if (goneSince == 0L) goneSince = now
                return if (KakaoShareRules.sheetGone(now - goneSince)) retryOrFail("카톡 공유 화면이 사라져 보내지 않았습니다.", now) else Outcome.Wait
            }
            goneSince = 0L
            if (phase == Phase.OPEN) goto(Phase.TYPE)
            return when (phase) {
                Phase.TYPE -> type(snapshot, now)
                Phase.RESULT -> result(snapshot, now)
                Phase.CLEAR -> clear(snapshot, now)
                Phase.SCAN -> scan(snapshot, now)
                Phase.BACK -> back(snapshot, now)
                Phase.CHECK -> check(snapshot, now)
                else -> Outcome.Wait
            }
        }

        /**
         * 검색란을 연다(첫 방법 [KakaoShareRules.SearchStage.OPEN]). 클립보드에 이름을 넣고 검색란을 누른다.
         * 검색란에 이미 다른 글자가 있으면 먼저 지운다. 화면이 자리 잡을 때까지 잠시 기다린다.
         */
        private fun type(snapshot: Snapshot, now: Long): Outcome {
            val edit = snapshot.editText ?: return Outcome.Wait
            if (readySince == 0L) readySince = now
            if (now - readySince < SETTLE_MS) return Outcome.Wait
            if (!edit.isShowingHintText && !edit.text.isNullOrBlank()) setText(edit, "")
            putClipboard(query)
            if (!edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            stage = KakaoShareRules.SearchStage.OPEN
            stageAt = now
            goto(Phase.RESULT)
            return Outcome.Wait
        }

        /**
         * 클립보드에 이름만 넣는다(보낼 내용은 넣지 않는다). 앞서 있던 클립보드를 읽을 수 있었으면 [restoreClipboard] 가
         * 되돌린다. 앱 밖에서는 읽기가 막혀 못 읽을 수 있고, 그때는 되돌리지 못한다.
         */
        private fun putClipboard(text: String) {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return
            previousClip = runCatching { clipboard.primaryClip }.getOrNull()
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("aemx", text)) }
        }

        private fun restoreClipboard() {
            val previous = previousClip ?: return
            previousClip = null
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return
            runCatching { clipboard.setPrimaryClip(previous) }
        }

        /** 다음 방법으로 검색란을 깨운다. 각 방법은 한 번만 쓴다. */
        private fun applyStage(edit: AccessibilityNodeInfo, next: KakaoShareRules.SearchStage, now: Long) {
            stage = next
            stageAt = now
            Log.i(TAG, "카톡 공유 검색 방법: $next")
            when (next) {
                KakaoShareRules.SearchStage.OPEN -> Unit
                KakaoShareRules.SearchStage.PASTE -> {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    handler.postDelayed({ restoreClipboard() }, token, CLIPBOARD_RESTORE_MS)
                }
                KakaoShareRules.SearchStage.FOCUS -> {
                    // 포커스 넣기 -> SET_TEXT -> 빼기 -> 다시 넣기 -> 빼기. 단계마다 화면이 반응할 틈을 준다.
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    handler.postDelayed({ setText(edit, query) }, token, FOCUS_STEP_MS)
                    handler.postDelayed({ edit.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }, token, FOCUS_STEP_MS * 2)
                    handler.postDelayed({ edit.performAction(AccessibilityNodeInfo.ACTION_CLICK) }, token, FOCUS_STEP_MS * 3)
                    handler.postDelayed({ edit.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }, token, FOCUS_STEP_MS * 4)
                }
                KakaoShareRules.SearchStage.ENTER -> sendEnter(edit)
            }
        }

        /**
         * 검색 결과: 이름이 맞는 행이 정확히 하나로 [RESULT_STABLE_MS] 동안 유지되면 누른다.
         * 결과는 나눠서 뜰 수 있어서 후보 구성이 바뀌는 동안은 기다린다. 후보가 여럿이거나 단체방뿐이면
         * 잠시 지켜본 뒤 실패한다.
         */
        private fun result(snapshot: Snapshot, now: Long): Outcome {
            val edit = snapshot.editText ?: return Outcome.Wait
            // 검색란에 이름이 들어가 있을 때의 결과만 쓴다. 빈 검색란의 최근 검색 같은 행을 이름 결과로 보지 않는다.
            val typed = !edit.isShowingHintText && nameMatch.matches(query, edit.text?.toString().orEmpty())
            val rows = if (typed) snapshot.searchRows else emptyList()
            when (val move = KakaoShareRules.searchMove(stage, rows.size, now - stageAt, Build.VERSION.SDK_INT >= 30)) {
                KakaoShareRules.SearchMove.Wait -> Unit
                is KakaoShareRules.SearchMove.Advance -> {
                    applyStage(edit, move.to, now)
                    return Outcome.Wait
                }
                KakaoShareRules.SearchMove.GiveUp -> {
                    restoreClipboard()
                    goto(Phase.CLEAR)
                    return Outcome.Wait
                }
            }
            val pick = KakaoShareRules.pickRow(rows.map { it.info }, recipient, nameMatch, looseMatch, alternate)
            val key = when (pick) {
                is KakaoShareRules.RowPick.Found -> "found:${rows.size}"
                KakaoShareRules.RowPick.None -> "none:${rows.size}"
                KakaoShareRules.RowPick.GroupOnly -> "group:${rows.size}"
                is KakaoShareRules.RowPick.Ambiguous -> "many:${pick.count}"
            }
            if (key != stableKey) {
                stableKey = key
                stableSince = now
                return Outcome.Wait
            }
            val steady = now - stableSince
            return when (pick) {
                KakaoShareRules.RowPick.None -> Outcome.Wait
                KakaoShareRules.RowPick.GroupOnly ->
                    if (steady >= AMBIGUOUS_MS) Outcome.Fail("\"$recipient\" 은(는) 단체방뿐이라 보내지 않았습니다.") else Outcome.Wait
                is KakaoShareRules.RowPick.Ambiguous ->
                    if (steady >= AMBIGUOUS_MS) Outcome.Fail(MessageRules.tooManyMessage(recipient, pick.count)) else Outcome.Wait
                is KakaoShareRules.RowPick.Found -> {
                    if (steady < RESULT_STABLE_MS) return Outcome.Wait
                    // 체크는 눌러서 켜지는 것이라 두 번 누르면 꺼진다. 한 번만 누르고 결과는 CHECK 에서 확인한다.
                    val shown = KakaoShareRules.shownName(rows[pick.index].info)
                        ?: return Outcome.Fail("고른 행의 이름을 읽지 못해 보내지 않았습니다.")
                    if (!press(rows[pick.index].node)) return Outcome.Wait
                    chosenName = shown
                    goto(Phase.CHECK)
                    Outcome.Wait
                }
            }
        }

        /**
         * 검색을 버리고 전체 목록으로 돌아간다. 지우기 버튼(id btn_clear)을 누르거나 SET_TEXT 로 비운다.
         * 검색 결과 행이 사라지고 전체 목록 행이 보이면 훑기를 시작한다.
         */
        private fun clear(snapshot: Snapshot, now: Long): Outcome {
            if (snapshot.searchRows.isEmpty() && snapshot.listRows.isNotEmpty()) {
                goto(Phase.SCAN)
                return Outcome.Wait
            }
            if (now - clearedAt < CLEAR_RETRY_MS) return Outcome.Wait
            clearedAt = now
            val button = snapshot.clearButton
            if (button != null) press(button) else snapshot.editText?.let { setText(it, "") }
            if (now - phaseSince > CLEAR_GIVE_UP_MS) return Outcome.Fail("검색어를 지우지 못해 보내지 않았습니다.")
            return Outcome.Wait
        }

        /**
         * 전체 목록을 아래로 스크롤하며 행을 모은다. 끝에 닿아야(스크롤해도 행이 안 바뀌거나 스크롤이 거절됨)
         * 이름이 맞는 일대일 행이 정확히 하나인지 판단한다. 상한 안에 끝에 못 닿으면 다른 사람이 더 있을
         * 수 있어서 실패한다. 같은 사람이 여러 번 보여도 [KakaoShareRules.key] 가 같으면 한 행이다.
         */
        private fun scan(snapshot: Snapshot, now: Long): Outcome {
            if (now - lastScrollAt < SCROLL_SETTLE_MS) return Outcome.Wait
            val visible = snapshot.listRows
            if (visible.isEmpty()) return Outcome.Wait
            scan.add(visible.map { it.info })
            if (scan.state == KakaoShareRules.ListScan.State.MORE) {
                lastScrollAt = now
                val list = snapshot.scrollList
                if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scan.markEnd()
                if (scan.state == KakaoShareRules.ListScan.State.MORE) return Outcome.Wait
            }
            if (scan.state == KakaoShareRules.ListScan.State.TOO_LONG) {
                return Outcome.Fail("목록이 길어 \"$recipient\" 이(가) 한 명뿐인지 끝까지 확인하지 못해 보내지 않았습니다.")
            }
            return when (val pick = scan.pick(recipient, nameMatch, looseMatch, alternate)) {
                KakaoShareRules.RowPick.None -> Outcome.Fail("카카오톡 목록에서 \"$recipient\" 을(를) 찾지 못했습니다.")
                KakaoShareRules.RowPick.GroupOnly -> Outcome.Fail("\"$recipient\" 은(는) 단체방뿐이라 보내지 않았습니다.")
                is KakaoShareRules.RowPick.Ambiguous -> Outcome.Fail(MessageRules.tooManyMessage(recipient, pick.count))
                is KakaoShareRules.RowPick.Found -> {
                    val row = scan.rows()[pick.index]
                    chosenName = KakaoShareRules.shownName(row)
                        ?: return Outcome.Fail("고른 행의 이름을 읽지 못해 보내지 않았습니다.")
                    targetKey = KakaoShareRules.key(row)
                    goto(Phase.BACK)
                    Outcome.Wait
                }
            }
        }

        /** 찾은 행이 화면에 보일 때까지 위로 스크롤해서 한 번 누른다. */
        private fun back(snapshot: Snapshot, now: Long): Outcome {
            val key = targetKey ?: return Outcome.Fail("찾은 행을 잃어버려 보내지 않았습니다.")
            val hit = snapshot.listRows.firstOrNull { KakaoShareRules.key(it.info) == key }
            if (hit != null) {
                if (!press(hit.node)) return Outcome.Wait
                goto(Phase.CHECK)
                return Outcome.Wait
            }
            if (now - lastScrollAt < SCROLL_SETTLE_MS) return Outcome.Wait
            if (backScrolls++ > scan.screens + BACK_EXTRA) return Outcome.Fail("찾은 행으로 돌아가지 못해 보내지 않았습니다.")
            lastScrollAt = now
            val list = snapshot.scrollList
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return Outcome.Fail("찾은 행으로 돌아가지 못해 보내지 않았습니다.")
            }
            return Outcome.Wait
        }

        /** 보내기 직전 확인을 한 번 더 하고, 상태가 [CHECK_STABLE_MS] 동안 같으면 보내기를 누른다. */
        private fun check(snapshot: Snapshot, now: Long): Outcome {
            val submit = snapshot.submit ?: return Outcome.Wait
            val verdict = KakaoShareRules.verifyChecked(
                rows = snapshot.listRows.map { it.info },
                chipNames = snapshot.chipNames,
                submitText = submit.text?.toString(),
                // 느슨하게 찾았으면 말한 이름이 아니라 누른 행의 실제 이름으로 확인한다.
                wanted = chosenName ?: recipient,
                match = nameMatch,
            )
            if (verdict is KakaoShareRules.CheckVerdict.Reject) {
                // 체크가 화면에 반영되기 전일 수 있어서 잠시는 기다린다.
                if (now - phaseSince < CHECK_GRACE_MS) {
                    stableKey = null
                    return Outcome.Wait
                }
                return Outcome.Fail(verdict.reason)
            }
            if (stableKey == null) {
                stableKey = "ready"
                stableSince = now
                return Outcome.Wait
            }
            if (now - stableSince < CHECK_STABLE_MS) return Outcome.Wait
            if (!submit.isEnabled) return Outcome.Wait
            if (!press(submit)) return Outcome.Wait
            sentAt = now
            goto(Phase.SENT)
            Log.i(TAG, "카톡 공유 보내기 버튼을 눌렀다")
            return Outcome.Wait
        }

        /**
         * 보낸 뒤: 공유 화면의 요소가 사라지면 성공이다. 남아 있으면 확인 창일 수 있다.
         * 확인 창 모양은 모른다. 화면에 받는 사람 이름이 보이고 "확인"/"보내기"/"전송" 버튼이 정확히 하나일 때만
         * 한 번 누른다. 그 밖에는 기다리다 실패로 끝낸다(다시 보내기를 누르지 않는다).
         */
        private fun sent(snapshot: Snapshot, now: Long): Outcome {
            if (!snapshot.pickerVisible) return Outcome.Done
            if (confirmTapped) return Outcome.Wait
            val shown = chosenName ?: recipient
            val named = snapshot.otherTexts.any { nameMatch.matches(shown, it) || it.contains(shown) }
            val buttons = snapshot.buttons.filter { button ->
                val label = (button.text ?: button.contentDescription)?.toString()?.trim().orEmpty()
                CONFIRM_LABELS.any { it == label }
            }
            if (named && buttons.size == 1 && now - sentAt >= SETTLE_MS) {
                confirmTapped = true
                press(buttons.single())
            }
            return Outcome.Wait
        }

        /** 노드 클릭이 안 되면 bounds 중앙을 제스처로 탭한다. 제스처는 눌렸는지 알려 주지 않는다. */
        private fun press(node: AccessibilityNodeInfo): Boolean {
            if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() <= 0 || bounds.height() <= 0) return false
            return service.tap(bounds.exactCenterX(), bounds.exactCenterY())
        }

        /** 검색란에 엔터를 보낸다(API 30+). 못 보내도 호출한 쪽이 시간 제한으로 실패시킨다. */
        private fun sendEnter(node: AccessibilityNodeInfo) {
            if (Build.VERSION.SDK_INT < 30) return
            Log.i(TAG, "검색 결과가 안 떠서 엔터를 보낸다")
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    /**
     * 화면을 한 번 훑어 공유 선택 화면의 요소를 모은다. 노드 수에는 상한을 둔다.
     *
     * dump 확인: 검색란 id edit_text(너비 1100px 이상), 검색 결과 목록 id search_list, 전체 목록 id list,
     * 고른 대상 칩의 이름 id name_text, 보내기 버튼 id submit_button(글자 "1 보내기"). 행은 id chat_room_list_item.
     */
    private fun read(root: AccessibilityNodeInfo): Snapshot {
        var editText: AccessibilityNodeInfo? = null
        var submit: AccessibilityNodeInfo? = null
        var clearButton: AccessibilityNodeInfo? = null
        var scrollList: AccessibilityNodeInfo? = null
        var pickerVisible = false
        val chipNames = ArrayList<String>()
        val searchRows = ArrayList<Row>()
        val listRows = ArrayList<Row>()
        val otherTexts = ArrayList<String>()
        val buttons = ArrayList<AccessibilityNodeInfo>()

        // (노드, 조상 중 search_list 안인가)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>().apply { add(root to false) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > MAX_NODES) break
            val (node, inSearch) = queue.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            when {
                id.endsWith("/submit_button") -> {
                    submit = node
                    pickerVisible = true
                }
                id.endsWith("/edit_text") -> {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.width() >= MIN_INPUT_WIDTH) {
                        editText = node
                        pickerVisible = true
                    }
                }
                id.endsWith("/btn_clear") -> clearButton = node
                id.endsWith("/list") && node.isScrollable -> {
                    // 칩 줄 같은 안쪽 목록이 아니라 가장 큰 세로 목록을 쓴다.
                    val current = scrollList
                    if (current == null || node.height() > current.height()) scrollList = node
                }
                id.endsWith("/name_text") -> node.text?.toString()?.let(chipNames::add)
                id.endsWith("/chat_room_list_item") -> {
                    pickerVisible = true
                    val row = Row(node, rowInfo(node))
                    if (inSearch) searchRows += row else listRows += row
                    continue
                }
                id.endsWith("/chip_list") || id.endsWith("/search_list") || id.endsWith("/design_bottom_sheet") ->
                    pickerVisible = true
            }
            if (node.className?.toString()?.endsWith("Button") == true) buttons += node
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!label.isNullOrEmpty() && !id.endsWith("/edit_text")) otherTexts += label
            val nextInSearch = inSearch || id.endsWith("/search_list")
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue.addLast(it to nextInSearch) }
        }
        return Snapshot(editText, searchRows, listRows, chipNames, submit, clearButton, scrollList, pickerVisible, otherTexts, buttons)
    }

    private fun AccessibilityNodeInfo.height(): Int = Rect().also(::getBoundsInScreen).height()

    /** 행 안을 훑어 id name 의 글자, id members_count 유무, id check_box 의 checked 를 읽는다. */
    private fun rowInfo(row: AccessibilityNodeInfo): KakaoShareRules.RowInfo {
        var name: String? = null
        var members = false
        var checked = false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(row) }
        var visited = 0
        while (queue.isNotEmpty() && ++visited <= ROW_MAX_NODES) {
            val node = queue.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            when {
                id.endsWith("/name") -> name = node.text?.toString()
                id.endsWith("/members_count") -> members = true
                id.endsWith("/check_box") -> checked = node.isChecked
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return KakaoShareRules.RowInfo(row.contentDescription?.toString(), name, members, checked)
    }

    private companion object {
        const val TAG = "AemxKakaoShare"
        const val POLL_MS = 300L
        const val SETTLE_MS = 800L
        const val TIMEOUT_MS = 80_000L
        const val VERIFY_MS = 8_000L
        const val RETYPE_MS = 2_000L
        const val RESULT_STABLE_MS = 1_200L
        const val AMBIGUOUS_MS = 3_000L
        const val CHECK_GRACE_MS = 2_500L
        const val HOME_WAIT_MS = 800L
        const val HEARTBEAT_MS = 5_000L
        const val CLIPBOARD_RESTORE_MS = 700L
        const val FOCUS_STEP_MS = 250L
        const val CLEAR_RETRY_MS = 800L
        const val CLEAR_GIVE_UP_MS = 5_000L
        const val SCROLL_SETTLE_MS = 900L
        const val BACK_EXTRA = 3
        const val CHECK_STABLE_MS = 600L
        const val MAX_NODES = 2_000
        const val ROW_MAX_NODES = 40

        /** 검색란의 최소 너비(px). dump 에서 1100px 대였다. */
        const val MIN_INPUT_WIDTH = 200

        /** 보내기를 눌렀는데 결과를 확인하지 못했을 때의 실패 문구. 보냈을 수도 있다는 뜻이 들어가야 한다. */
        const val SENT_UNVERIFIED = "보냈는지 확인하지 못했습니다. 카카오톡에서 직접 확인해 주세요."

        val CONFIRM_LABELS = listOf("확인", "보내기", "전송")
    }
}
