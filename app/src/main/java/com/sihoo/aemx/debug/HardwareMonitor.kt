package com.sihoo.aemx.debug

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * 하드웨어 사용량을 읽는다. 일반 앱 권한으로 읽을 수 있는 것만 쓴다.
 *
 * 기기 전체 CPU 사용률과 GPU 사용률은 안드로이드 8 이후 일반 앱에 막혀 있어 넣지 않았다.
 * 대신 이 앱의 CPU 시간과 코어별 클럭을 보여 준다. 파일을 읽으므로 [sample] 은 백그라운드에서 부른다.
 */
class HardwareMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    val cores: Int = Runtime.getRuntime().availableProcessors()

    private var lastCpuMs = -1L
    private var lastWallMs = 0L

    /** 코어별 최대 클럭. 바뀌지 않아 처음 한 번만 읽는다. */
    private val coreMaxMhz: List<Int?> by lazy {
        (0 until cores).map { readMhz("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
    }

    /** CPU 와 GPU 온도 센서 파일. 센서가 백 개가 넘는 기기도 있어 처음 한 번만 골라 둔다. */
    private val thermalZones: List<Pair<HardwareStats.ZoneKind, File>> by lazy {
        val zones = File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }.orEmpty()
        zones.mapNotNull { zone ->
            val kind = readText(File(zone, "type").path)?.trim()?.let(HardwareStats::zoneKind) ?: return@mapNotNull null
            kind to File(zone, "temp")
        }
    }

    fun sample(): HardwareSample {
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        // 붙어 있는 마지막 배터리 방송을 읽는다. 받는 쪽을 등록하지 않아 계속 깨우지 않는다.
        val battery: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return HardwareSample(
            appCpuPercent = appCpuPercent(),
            cores = cores,
            coreMhz = (0 until cores).map { readMhz("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") },
            coreMaxMhz = coreMaxMhz,
            appRssMb = readText("/proc/self/status")?.let(HardwareStats::parseVmRssKb)?.let { it / 1024 },
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / MB,
            javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
            systemAvailMb = memory.availMem / MB,
            systemTotalMb = memory.totalMem / MB,
            lowMemory = memory.lowMemory,
            batteryMilliAmps = HardwareStats.currentMilliAmps(
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            ),
            batteryVolts = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { it / 1000.0 },
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            cpuTempC = hottest(HardwareStats.ZoneKind.CPU),
            gpuTempC = hottest(HardwareStats.ZoneKind.GPU),
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else null,
        )
    }

    /** 직전 측정 이후 이 앱이 쓴 CPU 시간의 비율. */
    private fun appCpuPercent(): Double? {
        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val percent = if (lastCpuMs < 0) null else HardwareStats.cpuPercent(cpuMs - lastCpuMs, wallMs - lastWallMs)
        lastCpuMs = cpuMs
        lastWallMs = wallMs
        return percent
    }

    /** 그 종류의 센서 중 가장 뜨거운 값. 하나도 못 읽으면 null. */
    private fun hottest(kind: HardwareStats.ZoneKind): Double? =
        thermalZones.asSequence()
            .filter { it.first == kind }
            .mapNotNull { (_, file) -> readText(file.path)?.trim()?.toLongOrNull()?.let(HardwareStats::zoneCelsius) }
            .maxOrNull()

    /** kHz 로 적힌 클럭 파일을 MHz 로 읽는다. 꺼진 코어나 막힌 기기는 null. */
    private fun readMhz(path: String): Int? = readText(path)?.trim()?.toIntOrNull()?.let { it / 1000 }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024L
    }
}
