package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.accessibility.MessageRules
import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * send_message 계획의 app 이 사용자가 말한 수단과 맞는지 확인한다.
 *
 * "김경환한테 인스타로 …라고 보내줘" 를 모델이 카카오톡 send_message 로 내면 엉뚱한 앱으로
 * 나가고 되돌릴 수 없다. 그래서 발화에서 수단 말을 읽어
 * - 지원하는 수단(카카오톡, 메시지)이고 계획 app 과 다르면 app 을 바로잡고,
 * - 지원하지 않는 수단(인스타그램 등)이면 [Result.Unsupported] 로 계획을 버리게 한다.
 * - 수단 말이 없으면 그대로 둔다.
 *
 * 수단은 "받는 사람+조사" 바로 뒤나 문장 맨 앞("카톡으로 …"), 또는 "보내" 바로 앞에 올 때만 읽는다.
 * 보낼 내용 안의 "인스타 올렸다고 보내줘" 같은 말을 수단으로 오해하지 않기 위해서다.
 * send_message 가 없는 계획은 건드리지 않는다.
 */
object MessageChannelGuard {

    sealed interface Result {
        data class Ok(val plan: ActionPlan) : Result

        /** 지원하지 않는 수단. [reason] 은 사용자에게 보여 줄 문장이다. */
        data class Unsupported(val channel: String, val reason: String) : Result
    }

    fun apply(utterance: String, plan: ActionPlan): Result {
        if (plan.steps.none { it.action == ActionType.SEND_MESSAGE }) return Result.Ok(plan)
        val word = channelWord(utterance) ?: return Result.Ok(plan)

        SUPPORTED.firstOrNull { it.words.any(word::equals) }?.let { channel ->
            val app = SendMessageGuard.appName(plan.app.trim())
            return Result.Ok(if (app == channel.app) plan else plan.copy(app = channel.app))
        }
        val label = UNSUPPORTED.firstOrNull { it.words.any(word::equals) }?.label ?: word
        return Result.Unsupported(label, "$label 메시지는 아직 보낼 수 없어요")
    }

    /** "받는 사람+한테/에게/께" 와 "보내" 가 함께 있는 보내기 요청인지. "전해"는 안부 전달일 수 있어 뺀다. */
    fun asksToSend(utterance: String): Boolean = SEND_REQUEST.containsMatchIn(utterance.trim())

    /**
     * send_message 가 든 계획을 받아들이면 안 될 때 사용자에게 읽어 줄 이유, 괜찮으면 null.
     * 모델은 잡담("안녕. 오 좋네.")에도 send_message 를 지어내므로 다시 묻지 않고 바로 거절한다.
     * - 발화가 보내기 요청([asksToSend])이 아니다.
     * - 받는 사람(target)이 발화에 없다(공백 무시, 한글 숫자는 [MessageRules.digitizeName] 로 맞춘다).
     * - value 가 발화 전체와 같다. 보내기 동사가 없는 문장 전체를 그대로 넣은 경우도 포함한다.
     */
    fun refusal(utterance: String, plan: ActionPlan): String? {
        val steps = plan.steps.filter { it.action == ActionType.SEND_MESSAGE }
        if (steps.isEmpty()) return null
        if (!asksToSend(utterance)) return "메시지를 보내 달라는 말이 아니라서 보내지 않았어요"

        val said = squash(utterance)
        val saidDigits = squash(MessageRules.digitizeName(utterance))
        for (step in steps) {
            val who = squash(step.target.orEmpty())
            if (who.isEmpty() || !(said.contains(who) || saidDigits.contains(squash(MessageRules.digitizeName(step.target.orEmpty()))))) {
                return "받는 사람을 알아듣지 못해 보내지 않았어요"
            }
            val value = squash(step.value.orEmpty())
            val wholeSentence = value == said ||
                (!SEND_VERB.containsMatchIn(utterance) && value.contains(who) && said.contains(value))
            if (wholeSentence) return "보낼 내용을 알아듣지 못해 보내지 않았어요"
        }
        return null
    }

    private fun squash(text: String): String = text.filterNot { it.isWhitespace() || it in ".,!?\"'“”" }

    /** 발화에서 읽은 수단 말(소문자). 못 읽으면 null. */
    internal fun channelWord(utterance: String): String? {
        val text = utterance.trim().lowercase()
        return (AFTER_RECIPIENT.find(text) ?: LEADING.find(text) ?: BEFORE_SEND.find(text))
            ?.groupValues?.get(1)
    }

    private class Channel(val app: String, val words: List<String>)
    private class Other(val label: String, val words: List<String>)

    private val SUPPORTED = listOf(
        Channel("카카오톡", listOf("카카오톡", "카톡")),
        Channel("메시지", listOf("문자메시지", "문자", "메시지", "메세지")),
    )

    private val UNSUPPORTED = listOf(
        Other("인스타그램", listOf("인스타그램", "인스타", "인스타dm")),
        Other("DM", listOf("dm", "디엠")),
        Other("페이스북", listOf("페이스북", "페북")),
        Other("텔레그램", listOf("텔레그램", "텔레")),
        Other("라인", listOf("라인")),
        Other("왓츠앱", listOf("왓츠앱", "왓쏩")),
        Other("이메일", listOf("이메일", "메일")),
        Other("디스코드", listOf("디스코드")),
        Other("슬랙", listOf("슬랙")),
        Other("트위터", listOf("트위터")),
    )

    private val ALL_WORDS = (SUPPORTED.flatMap { it.words } + UNSUPPORTED.flatMap { it.words })
        .sortedByDescending { it.length }
        .joinToString("|")

    private const val END = "(?:으로|로)?(?=\\s|$)"

    private val SEND_VERB = Regex("보내|전해|남겨")

    private val SEND_REQUEST = Regex("[가-힣A-Za-z0-9]+(?:한테|에게|께)\\s.*보내")

    /** "배재준한테 인스타로", "배재준한테 카톡 보내줘". */
    private val AFTER_RECIPIENT = Regex("(?:한테|에게|께)\\s*($ALL_WORDS)$END")

    /** "카톡으로 배재준한테 …". */
    private val LEADING = Regex("^($ALL_WORDS)(?:으로|로)(?=\\s)")

    /** "…라고 인스타 보내줘", "…다고 디엠으로 보내줘". */
    private val BEFORE_SEND = Regex("(?<![가-힣])($ALL_WORDS)(?:으로|로)?\\s*(?:메시지|메세지|를|을)?\\s*보내[^\\s]*\\s*$")
}
