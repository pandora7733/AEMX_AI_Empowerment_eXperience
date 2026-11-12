package com.sihoo.aemx.debug

import java.util.Locale

/**
 * 디버깅 패널에 보여 줄 하드웨어 사용량 한 번의 측정값.
 *
 * 읽지 못한 값은 null 이다. 기기와 안드로이드 버전에 따라 막힌 항목이 있어서 화면에는 "읽을 수 없음"으로 보여 준다.
 */
data class HardwareSample(
    /** 이 앱이 쓴 CPU. 100 이 코어 하나를 꽉 쓴 것이다. 첫 측정은 비교할 값이 없어 null. */
    val appCpuPercent: Double?,
    val cores: Int,
    /** 코어별 현재 클럭(MHz). 사용률이 아니라 클럭이다. */
    val coreMhz: List<Int?>,
    /** 이 앱이 실제로 차지한 메모리(MB). 모델 파일을 올린 부분도 들어간다. */
    val appRssMb: Long?,
    val nativeHeapMb: Long,
    val javaHeapMb: Long,
    val systemAvailMb: Long,
    val systemTotalMb: Long,
    val lowMemory: Boolean,
    /** 배터리 전류(mA). 부호는 기기마다 다르다(삼성은 방전이 음수). */
    val batteryMilliAmps: Double?,
    val batteryVolts: Double?,
    val batteryTempC: Double?,
    /** PowerManager 의 발열 단계(0~6). 안드로이드 10 미만은 null. */
    val thermalStatus: Int?,
)

/** 측정값의 계산과 글자 만들기. 안드로이드 API 를 쓰지 않아 단위 테스트로 고정한다. */
object HardwareStats {

    /** CPU 시간 차이를 경과 시간으로 나눈 값(%). 코어 여러 개를 쓰면 100 을 넘는다. */
    fun cpuPercent(cpuDeltaMs: Long, wallDeltaMs: Long): Double? =
        if (wallDeltaMs <= 0 || cpuDeltaMs < 0) null else cpuDeltaMs * 100.0 / wallDeltaMs

    /** /proc/self/status 의 VmRSS 줄에서 kB 값을 꺼낸다. */
    fun parseVmRssKb(status: String): Long? =
        status.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()

    /**
     * BatteryManager 의 CURRENT_NOW 를 mA 로 바꾼다. 규격은 µA 지만 mA 로 주는 기기가 있어서
     * 절댓값이 크면 µA 로 본다. 지원하지 않는 기기는 Int.MIN_VALUE 를 준다.
     */
    fun currentMilliAmps(raw: Int): Double? = when {
        raw == Int.MIN_VALUE -> null
        kotlin.math.abs(raw) >= MICRO_AMP_FLOOR -> raw / 1000.0
        else -> raw.toDouble()
    }

    fun thermalLabel(status: Int?): String = when (status) {
        null -> UNREADABLE
        0 -> "정상(0)"
        1 -> "약간 더움(1)"
        2 -> "더움(2)"
        3 -> "심함(3)"
        4 -> "위험(4)"
        5 -> "비상(5)"
        6 -> "꺼짐 직전(6)"
        else -> "알 수 없음($status)"
    }

    /** 패널에 그릴 줄들. 이름과 값의 쌍이다. */
    fun lines(sample: HardwareSample): List<Pair<String, String>> {
        val cpu = sample.appCpuPercent?.let {
            "%.0f%% (코어 %.1f개 / %d개)".format(Locale.US, it, it / 100.0, sample.cores)
        } ?: "재는 중"
        val clocks = if (sample.coreMhz.all { it == null }) {
            UNREADABLE
        } else {
            sample.coreMhz.joinToString(" ") { it?.toString() ?: "-" } + " MHz"
        }
        val appMemory = listOfNotNull(
            sample.appRssMb?.let { "실사용 ${it}MB" },
            "네이티브 힙 ${sample.nativeHeapMb}MB",
            "자바 힙 ${sample.javaHeapMb}MB",
        ).joinToString(" · ")
        val systemMemory = "남음 ${sample.systemAvailMb}MB / 전체 ${sample.systemTotalMb}MB" +
            if (sample.lowMemory) " (부족)" else ""
        val battery = listOfNotNull(
            sample.batteryMilliAmps?.let { "%.0fmA".format(Locale.US, it) },
            sample.batteryVolts?.let { "%.2fV".format(Locale.US, it) },
            sample.batteryTempC?.let { "%.1f°C".format(Locale.US, it) },
        ).joinToString(" · ").ifEmpty { UNREADABLE }
        return listOf(
            "앱 CPU" to cpu,
            "코어 클럭" to clocks,
            "앱 메모리" to appMemory,
            "기기 메모리" to systemMemory,
            "배터리" to battery,
            "발열" to thermalLabel(sample.thermalStatus),
        )
    }

    const val UNREADABLE = "읽을 수 없음"

    /** 이 값 이상이면 µA 로 본다. 20mA 를 µA 로 쓴 값이다. */
    private const val MICRO_AMP_FLOOR = 20_000
}
