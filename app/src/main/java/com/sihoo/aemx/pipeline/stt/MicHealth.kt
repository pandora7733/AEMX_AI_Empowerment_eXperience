package com.sihoo.aemx.pipeline.stt

/**
 * 마이크가 실제 소리를 주는지 보는 판정.
 *
 * 안드로이드 14 이상에서 백그라운드에서 시작한 마이크 서비스는 마이크를 막히면 소리 대신 0(무음)만 받는다.
 * 실제 방에서는 샘플이 정확히 0 인 프레임이 여러 번 이어지지 않으므로, 0만 [silentFramesLimit] 프레임
 * 이어지면 무음으로 본다. 프레임 하나가 100ms 라 기본은 3초다.
 */
class MicHealth(private val silentFramesLimit: Int = SILENT_FRAMES_LIMIT) {

    private var silentFrames = 0

    /** 지금 무음으로 보고 있는가. */
    var silenced = false
        private set

    /**
     * 프레임 하나를 본다. 무음으로 판정이 막 바뀌었으면 참을 돌려준다(로그는 한 번만 남기려는 것이다).
     * 소리가 다시 들어오면 무음을 푼다.
     */
    fun onFrame(buffer: ShortArray, read: Int): Boolean {
        if (read <= 0) return false
        val allZero = (0 until read).all { buffer[it].toInt() == 0 }
        if (!allZero) {
            silentFrames = 0
            silenced = false
            return false
        }
        silentFrames++
        if (!silenced && silentFrames >= silentFramesLimit) {
            silenced = true
            return true
        }
        return false
    }

    fun reset() {
        silentFrames = 0
        silenced = false
    }

    companion object {
        /** 무음으로 볼 연속 프레임 수. 100ms 프레임이면 3초다. */
        const val SILENT_FRAMES_LIMIT = 30
    }
}

/**
 * 지금 마이크가 무음으로 들어오고 있는지. 듣기 루프가 쓰고, 앱이 화면에 올라왔을 때 다시 시도할지 정하는 데 읽는다.
 */
object MicStatus {
    @Volatile
    var silenced = false

    /** 마지막 프레임의 소리 크기(RMS). 빠른 메뉴가 마이크에 소리가 들어오는지 보여 줄 때 읽는다. 꺼지면 0 이다. */
    @Volatile
    var level = 0.0
}
