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
    /** 코어별 최대 클럭(MHz). 막대의 끝이다. */
    val coreMaxMhz: List<Int?>,
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
    /** CPU 와 GPU 온도 센서 중 가장 뜨거운 값(°C). 센서 파일이 막힌 기기는 null. */
    val cpuTempC: Double?,
    val gpuTempC: Double?,
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

    /**
     * 온도 센서 파일의 값을 °C 로 바꾼다. 대부분 1/1000 도 단위지만 도 단위로 주는 기기가 있어 크기로 가린다.
     * 온도로 보기 어려운 값(0 이하, 150 이상)은 버린다. 센서 목록에는 전압이나 경보 단계 같은 것도 섞여 있다.
     */
    fun zoneCelsius(raw: Long): Double? {
        val celsius = if (raw >= MILLI_DEGREE_FLOOR) raw / 1000.0 else raw.toDouble()
        return celsius.takeIf { it > 0.0 && it < MAX_PLAUSIBLE_CELSIUS }
    }

    /** 센서 이름으로 CPU 와 GPU 를 가린다. 그 밖의 센서(배터리, 카메라, 모뎀 등)는 null. */
    fun zoneKind(type: String): ZoneKind? {
        val name = type.lowercase(Locale.US)
        return when {
            "gpu" in name -> ZoneKind.GPU
            "cpu" in name -> ZoneKind.CPU
            else -> null
        }
    }

    enum class ZoneKind { CPU, GPU }

    /** 전체에서 차지하는 비율(0~100). 전체를 모르면 0. */
    fun percent(part: Long, total: Long): Int =
        if (total <= 0) 0 else (part * 100 / total).toInt().coerceIn(0, 100)

    /** 앱 CPU 를 기기 전체 코어에 대한 비율(0~100)로 바꾼다. 막대에 쓴다. */
    fun cpuBarPercent(sample: HardwareSample): Int =
        sample.appCpuPercent?.let { (it / sample.cores.coerceAtLeast(1)).toInt().coerceIn(0, 100) } ?: 0

    fun cpuText(sample: HardwareSample): String = sample.appCpuPercent?.let {
        "%.0f%% (코어 %.1f개 / %d개)".format(Locale.US, it, it / 100.0, sample.cores)
    } ?: "재는 중"

    /** 앱이 차지한 메모리. 실사용을 못 읽으면 네이티브 힙과 자바 힙의 합으로 대신한다. */
    fun appMemoryMb(sample: HardwareSample): Long = sample.appRssMb ?: (sample.nativeHeapMb + sample.javaHeapMb)

    fun appMemoryText(sample: HardwareSample): String {
        val used = appMemoryMb(sample)
        return "${used}MB / 기기 전체 ${sample.systemTotalMb}MB (${percent(used, sample.systemTotalMb)}%)"
    }

    fun appMemoryDetail(sample: HardwareSample): String =
        "네이티브 힙 ${sample.nativeHeapMb}MB · 자바 힙 ${sample.javaHeapMb}MB"

    fun systemUsedMb(sample: HardwareSample): Long = (sample.systemTotalMb - sample.systemAvailMb).coerceAtLeast(0)

    fun systemMemoryText(sample: HardwareSample): String {
        val used = systemUsedMb(sample)
        return "사용 ${used}MB / 전체 ${sample.systemTotalMb}MB (${percent(used, sample.systemTotalMb)}%) · 남음 ${sample.systemAvailMb}MB" +
            if (sample.lowMemory) " (부족)" else ""
    }

    fun batteryText(sample: HardwareSample): String = listOfNotNull(
        sample.batteryMilliAmps?.let { "%.0fmA".format(Locale.US, it) },
        sample.batteryVolts?.let { "%.2fV".format(Locale.US, it) },
        sample.batteryTempC?.let { "%.1f°C".format(Locale.US, it) },
    ).joinToString(" · ").ifEmpty { UNREADABLE }

    /** 잰 온도를 앞에 두고 발열 단계를 뒤에 붙인다. 온도를 못 읽으면 단계만 보인다. */
    fun thermalText(sample: HardwareSample): String = listOfNotNull(
        sample.cpuTempC?.let { "CPU %.1f°C".format(Locale.US, it) },
        sample.gpuTempC?.let { "GPU %.1f°C".format(Locale.US, it) },
        thermalLabel(sample.thermalStatus),
    ).joinToString(" · ")

    fun thermalLabel(status: Int?): String = when (status) {
        null -> "단계 $UNREADABLE"
        0 -> "정상(0)"
        1 -> "약간 더움(1)"
        2 -> "더움(2)"
        3 -> "심함(3)"
        4 -> "위험(4)"
        5 -> "비상(5)"
        6 -> "꺼짐 직전(6)"
        else -> "알 수 없음($status)"
    }

    const val UNREADABLE = "읽을 수 없음"

    /** 이 값 이상이면 µA 로 본다. 20mA 를 µA 로 쓴 값이다. */
    private const val MICRO_AMP_FLOOR = 20_000

    /** 이 값 이상이면 1/1000 도 단위로 본다. */
    private const val MILLI_DEGREE_FLOOR = 1_000L
    private const val MAX_PLAUSIBLE_CELSIUS = 150.0
}
