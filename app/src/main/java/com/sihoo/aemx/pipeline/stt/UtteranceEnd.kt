package com.sihoo.aemx.pipeline.stt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 한 문장이 왜 거기서 끝났는가. 이름이나 내용이 긴 말이 중간에 잘리는지 폰에서 재 볼 때 쓴다.
 *
 * 문장 끝을 정하는 곳은 둘이다.
 * - VAD: 소리 크기가 문턱 아래로 [SherpaSpeechSource] 의 VAD hangover 동안 이어져 문이 닫혔다.
 * - 인식기 끝점 규칙: 규칙 2(글자를 낸 뒤 무음)와 규칙 3(문장 최대 길이). 규칙 1(글자 없이 무음)은 문장을 내지 않는다.
 * 어느 끝점 규칙인지는 인식기가 알려 주지 않아서, 문장 길이가 규칙 3 의 길이 이상이면 규칙 3 으로 본다.
 */
internal enum class UtteranceEnd(val label: String) {
    VAD_SILENCE("VAD 무음"),
    ENDPOINT_SILENCE("끝점 규칙(무음)"),
    ENDPOINT_MAX_LENGTH("끝점 규칙(최대 길이)"),
}

/**
 * @param viaGate VAD 문이 닫혀서 끝냈는가(아니면 인식기 끝점)
 * @param durationMs 첫 말소리부터 끝날 때까지
 * @param maxLengthMs 끝점 규칙 3 의 길이
 */
internal fun classifyUtteranceEnd(viaGate: Boolean, durationMs: Long, maxLengthMs: Long): UtteranceEnd = when {
    viaGate -> UtteranceEnd.VAD_SILENCE
    durationMs >= maxLengthMs -> UtteranceEnd.ENDPOINT_MAX_LENGTH
    else -> UtteranceEnd.ENDPOINT_SILENCE
}

/** 끝에서부터 이어진 조용한 프레임 수. [loud] 는 프레임마다 말소리 크기를 넘었는지다. */
internal fun trailingQuietFrames(loud: List<Boolean>): Int {
    var count = 0
    for (index in loud.indices.reversed()) {
        if (loud[index]) break
        count++
    }
    return count
}

private val clock = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

internal fun clockText(epochMs: Long): String = clock.get()!!.format(Date(epochMs))

/**
 * 측정용 한 줄. "문장 끝:" 으로 시작한다. 말한 내용은 [showText] 가 켜졌을 때만 넣고,
 * 아니면 글자 수만 남긴다(디버그 빌드가 아니면 사용자 말을 로그에 남기지 않는다).
 */
internal fun formatUtteranceEndLine(
    startMs: Long,
    endMs: Long,
    reason: UtteranceEnd,
    trailingQuietMs: Long,
    lastPartial: String,
    finalText: String,
    showText: Boolean,
): String {
    val duration = endMs - startMs
    val partial = if (showText) "\"$lastPartial\"" else "(${lastPartial.length}자)"
    val final = if (showText) "\"$finalText\"" else "(${finalText.length}자)"
    return "문장 끝: 시작=${clockText(startMs)} 끝=${clockText(endMs)} 길이=${duration}ms " +
        "이유=${reason.label} 끝무음=${trailingQuietMs}ms 마지막중간=$partial 최종=$final"
}
