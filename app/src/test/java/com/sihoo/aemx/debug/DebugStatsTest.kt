package com.sihoo.aemx.debug

import com.sihoo.aemx.pipeline.port.SpeechResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugStatsTest {

    private val sample = HardwareSample(
        appCpuPercent = 132.0,
        cores = 8,
        coreMhz = listOf(1804, null, 2400),
        coreMaxMhz = listOf(2265, 2265, 3302),
        appRssMb = 1820,
        nativeHeapMb = 310,
        javaHeapMb = 12,
        systemAvailMb = 3210,
        systemTotalMb = 11400,
        lowMemory = false,
        batteryMilliAmps = -412.0,
        batteryVolts = 4.01,
        batteryTempC = 31.5,
        cpuTempC = 34.2,
        gpuTempC = 32.8,
        thermalStatus = 0,
    )

    @Test
    fun `CPU 시간 차이를 경과 시간으로 나눈다`() {
        assertEquals(50.0, HardwareStats.cpuPercent(500, 1000)!!, 1e-9)
        assertEquals(250.0, HardwareStats.cpuPercent(2500, 1000)!!, 1e-9)
    }

    @Test
    fun `경과 시간이 없으면 CPU 값을 내지 않는다`() {
        assertNull(HardwareStats.cpuPercent(10, 0))
    }

    @Test
    fun `VmRSS 줄에서 kB 값을 꺼낸다`() {
        val status = "Name:\taemx\nVmPeak:\t 9000000 kB\nVmRSS:\t 1863680 kB\nThreads:\t40\n"
        assertEquals(1863680L, HardwareStats.parseVmRssKb(status))
        assertNull(HardwareStats.parseVmRssKb("Name:\taemx\n"))
    }

    @Test
    fun `전류는 큰 값이면 마이크로암페어로 보고 지원하지 않으면 비운다`() {
        assertEquals(-412.0, HardwareStats.currentMilliAmps(-412_000)!!, 1e-9)
        assertEquals(-412.0, HardwareStats.currentMilliAmps(-412)!!, 1e-9)
        assertNull(HardwareStats.currentMilliAmps(Int.MIN_VALUE))
    }

    @Test
    fun `측정값을 화면 글자로 만든다`() {
        assertEquals("132% (코어 1.3개 / 8개)", HardwareStats.cpuText(sample))
        assertEquals("1820MB / 기기 전체 11400MB (15%)", HardwareStats.appMemoryText(sample))
        assertEquals("네이티브 힙 310MB · 자바 힙 12MB", HardwareStats.appMemoryDetail(sample))
        assertEquals("사용 8190MB / 전체 11400MB (71%) · 남음 3210MB", HardwareStats.systemMemoryText(sample))
        assertEquals("-412mA · 4.01V · 31.5°C", HardwareStats.batteryText(sample))
        assertEquals("CPU 34.2°C · GPU 32.8°C · 정상(0)", HardwareStats.thermalText(sample))
    }

    @Test
    fun `읽지 못한 값은 빼거나 읽을 수 없음으로 보인다`() {
        val blocked = sample.copy(
            appCpuPercent = null, appRssMb = null,
            batteryMilliAmps = null, batteryVolts = null, batteryTempC = null,
            cpuTempC = null, gpuTempC = null, thermalStatus = null,
        )
        assertEquals("재는 중", HardwareStats.cpuText(blocked))
        assertEquals(322L, HardwareStats.appMemoryMb(blocked))
        assertEquals(HardwareStats.UNREADABLE, HardwareStats.batteryText(blocked))
        assertEquals("단계 읽을 수 없음", HardwareStats.thermalText(blocked))
    }

    @Test
    fun `온도를 읽으면 발열 단계 앞에 붙인다`() {
        assertEquals("CPU 34.2°C · 정상(0)", HardwareStats.thermalText(sample.copy(gpuTempC = null)))
    }

    @Test
    fun `막대의 비율은 0에서 100 사이다`() {
        assertEquals(50, HardwareStats.percent(1, 2))
        assertEquals(100, HardwareStats.percent(5, 2))
        assertEquals(0, HardwareStats.percent(5, 0))
        // 코어 8개 중 1.32개를 쓰면 전체의 16%다.
        assertEquals(16, HardwareStats.cpuBarPercent(sample))
        assertEquals(0, HardwareStats.cpuBarPercent(sample.copy(appCpuPercent = null)))
    }

    @Test
    fun `온도 센서 값은 천분의 일 도와 도 단위를 모두 받는다`() {
        assertEquals(33.5, HardwareStats.zoneCelsius(33500)!!, 1e-9)
        assertEquals(41.0, HardwareStats.zoneCelsius(41)!!, 1e-9)
        // 경보 단계 0 이나 전압 같은 값은 온도가 아니다.
        assertNull(HardwareStats.zoneCelsius(0))
        assertNull(HardwareStats.zoneCelsius(4_185_000))
    }

    @Test
    fun `센서 이름으로 CPU 와 GPU 를 가린다`() {
        assertEquals(HardwareStats.ZoneKind.CPU, HardwareStats.zoneKind("cpu-1-2-0"))
        assertEquals(HardwareStats.ZoneKind.CPU, HardwareStats.zoneKind("cpuss-0"))
        assertEquals(HardwareStats.ZoneKind.GPU, HardwareStats.zoneKind("gpuss-3"))
        assertNull(HardwareStats.zoneKind("camera-0"))
        assertNull(HardwareStats.zoneKind("vbat"))
    }

    @Test
    fun `중간 결과는 지금 문장과 단어로 보인다`() {
        val state = SpeechTrace.reduce(SpeechTraceState(), SpeechResult.Partial("유튜브에서  고양이 영상"))
        assertEquals("유튜브에서  고양이 영상", state.partial)
        assertEquals(listOf("유튜브에서", "고양이", "영상"), state.words)
    }

    @Test
    fun `문장이 끝나면 지금 문장을 비우고 목록 맨 위에 쌓는다`() {
        var state = SpeechTrace.reduce(SpeechTraceState(partial = "첫 문장"), SpeechResult.Final("첫 문장"))
        state = SpeechTrace.reduce(state, SpeechResult.Final("둘째 문장"))
        assertEquals("", state.partial)
        assertEquals(listOf("둘째 문장", "첫 문장"), state.finals)
    }

    @Test
    fun `끝난 문장은 정해진 개수까지만 남긴다`() {
        var state = SpeechTraceState()
        repeat(SpeechTrace.MAX_FINALS + 3) { state = SpeechTrace.reduce(state, SpeechResult.Final("문장 $it")) }
        assertEquals(SpeechTrace.MAX_FINALS, state.finals.size)
        assertEquals("문장 ${SpeechTrace.MAX_FINALS + 2}", state.finals.first())
    }

    @Test
    fun `빈 문장은 목록에 넣지 않는다`() {
        val state = SpeechTrace.reduce(SpeechTraceState(), SpeechResult.Final(" "))
        assertEquals(emptyList<String>(), state.finals)
    }
}
