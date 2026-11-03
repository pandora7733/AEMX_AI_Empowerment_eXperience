package com.sihoo.aemx.pipeline.youtube

import java.util.Calendar
import java.util.TimeZone

/**
 * YouTube Data API 의 하루 한도를 넘었는지 기억한다. 한도는 태평양 시간 자정에 다시 찬다.
 *
 * 한도 초과 응답(403, reason quotaExceeded/dailyLimitExceeded)을 받으면 그날 자정까지 API 를 부르지 않는다.
 * 기억은 메모리에만 둔다. 앱을 다시 시작하면 한 번 다시 시도해 보며, 그때도 막히면 또 기억한다.
 */
class YouTubeQuota(private val now: () -> Long = { System.currentTimeMillis() }) {

    @Volatile
    private var blockedUntil = 0L

    fun isBlocked(): Boolean = now() < blockedUntil

    /** 한도 초과를 받았을 때 부른다. 다음 태평양 시간 자정까지 막는다. */
    fun blockUntilPacificMidnight() {
        blockedUntil = nextPacificMidnight(now())
    }

    private var dayEnd = 0L
    private var dayUnits = 0

    /**
     * 이번 호출의 유닛을 오늘 예산에서 뗀다. 막혀 있거나 예산을 넘으면 false 다. 예산을 넘는 순간에는
     * 오늘 자정까지 막는다(한도 초과 응답과 같은 처리).
     */
    @Synchronized
    fun reserve(units: Int): Boolean {
        if (isBlocked()) return false
        val current = now()
        if (current >= dayEnd) {
            dayEnd = nextPacificMidnight(current)
            dayUnits = 0
        }
        if (dayUnits + units > DAILY_UNITS) {
            blockUntilPacificMidnight()
            return false
        }
        dayUnits += units
        return true
    }

    /** 오늘 쓴 유닛(확인용). 날이 바뀌었으면 0 이다. */
    @Synchronized
    fun unitsToday(): Int = if (now() >= dayEnd) 0 else dayUnits

    companion object {
        private val PACIFIC = TimeZone.getTimeZone("America/Los_Angeles")

        /** 무료 하루 예산(유닛). search 한 번은 100, videos.list 한 번은 1. */
        const val DAILY_UNITS = 10_000
        const val SEARCH_UNITS = 100
        const val VIDEOS_UNITS = 1

        /** [nowMs] 다음에 오는 태평양 시간 자정의 밀리초. 날짜 계산은 달력으로 하므로 서머타임도 맞는다. */
        fun nextPacificMidnight(nowMs: Long): Long {
            val calendar = Calendar.getInstance(PACIFIC).apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return calendar.timeInMillis
        }

        /** 앱 전체가 함께 쓰는 한도 기억. 화면이 다시 만들어져도 유지된다. */
        val shared = YouTubeQuota()
    }
}
