package com.sihoo.aemx.assistant.overlay

import com.sihoo.aemx.assistant.AssistantPhase
import com.sihoo.aemx.assistant.AssistantUiState

/**
 * 듣는 중 캡슐에 실시간으로 보이는 말(STT 중간 결과)을 다듬는 순수 규칙.
 *
 * - 줄바꿈과 여러 칸 공백은 한 칸으로 줄인다.
 * - 한 줄에 맞게 말하는 중에는 최근 말이 보이도록 앞을 "…"으로 줄이고 끝 [WIDTH] 글자만 보인다.
 * - 갱신은 [MIN_INTERVAL_MS] 간격으로만 반영한다. 그 사이 온 값은 마지막 것만 남는다.
 * - 앞부분이 거의 같지 않은 큰 변동은 [STABLE_MS] 동안 그 값이 그대로일 때만 반영한다(글자가 뒤집히는 깜빡임을 막는다).
 * - 빈 중간 결과는 무시하고 이전 글자를 유지한다.
 */
object LiveCaption {

    /** 캡슐 한 줄에 보이는 최근 글자 수(앞의 "…" 은 따로). */
    const val WIDTH = 24

    /** 두 번 반영하는 최소 간격. */
    const val MIN_INTERVAL_MS = 200L

    /** 앞부분이 바뀌는 큰 변동을 반영하기 전에 기다리는 시간. */
    const val STABLE_MS = 300L

    /** 큰 변동으로 볼 최소 길이. 짧은 말은 바뀌어도 깜빡임이 작다. */
    private const val BIG_MIN_LENGTH = 4

    /** 줄바꿈과 공백을 정리한다. */
    fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * 듣기가 새로 시작됐는지. 명령(runId)이 바뀌었거나 듣기 아닌 상태에서 듣기로 들어가면 참이다.
     * 이전 듣기의 글자는 이때 버려야 한다. 화면 상태가 듣기 사이를 거치지 않고 바뀌면 중간의 Idle 을 보지 못한다.
     */
    fun startsNewListening(previous: AssistantUiState, next: AssistantUiState): Boolean {
        if (previous.runId != next.runId) return true
        return next.phase == AssistantPhase.Listening && previous.phase != AssistantPhase.Listening
    }

    /** 한 줄에 보일 꼴. 길면 앞을 줄이고 끝 [WIDTH] 글자만 보인다. */
    fun display(text: String): String {
        val oneLine = normalize(text)
        return if (oneLine.length <= WIDTH) oneLine else "…" + oneLine.takeLast(WIDTH)
    }

    /** 공통 접두사가 거의 없어 앞부분이 바뀐 것으로 보이면 참이다. */
    fun isLargeChange(shown: String, next: String): Boolean {
        val shortest = minOf(shown.length, next.length)
        if (shortest < BIG_MIN_LENGTH) return false
        return commonPrefixLength(shown, next) < shortest / 2
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    /**
     * 갱신 간격과 큰 변동 유예를 맡는 상태. 화면은 중간 결과가 올 때마다 [offer] 로 넘기고,
     * [waiting] 이 참인 동안에는 잠깐 뒤에 다시 [offer] 를 불러 보류된 값을 반영할 기회를 준다.
     */
    class Throttle {

        /** 지금 화면에 반영된 전체 문장(정리한 뒤). 아직 없으면 빈 문자열. */
        var shown: String = ""
            private set

        private var shownAt = 0L
        private var candidate: String? = null
        private var candidateSince = 0L

        /** 아직 반영하지 못한 값이 있어 다시 불러야 하는가. */
        val waiting: Boolean get() = candidate != null

        /**
         * 새 중간 결과를 넘긴다. 지금 반영해야 하면 그 값을 돌려주고, 바뀐 것이 없거나 보류하면 null 이다.
         * 돌려준 값은 [shown] 에도 들어 있다.
         */
        fun offer(raw: String, now: Long): String? {
            val next = normalize(raw)
            // 빈 결과는 이전 글자를 유지한다.
            if (next.isEmpty()) return null
            if (next == shown) {
                candidate = null
                return null
            }
            // 처음 들어온 말은 바로 보인다.
            if (shown.isEmpty()) return accept(next, now)
            // 너무 자주 바뀌면 마지막 값만 남기고 간격이 지난 뒤 반영한다.
            if (now - shownAt < MIN_INTERVAL_MS) {
                candidate = next
                if (candidateSince == 0L) candidateSince = now
                return null
            }
            if (isLargeChange(shown, next)) {
                if (candidate != next) {
                    candidate = next
                    candidateSince = now
                    return null
                }
                // 같은 값이 충분히 머물렀으면 반영한다.
                if (now - candidateSince < STABLE_MS) return null
            }
            return accept(next, now)
        }

        private fun accept(next: String, now: Long): String {
            shown = next
            shownAt = now
            candidate = null
            candidateSince = 0L
            return next
        }

        /** 새 명령이나 듣기 시작 때 지운다. */
        fun reset() {
            shown = ""
            shownAt = 0L
            candidate = null
            candidateSince = 0L
        }
    }
}
