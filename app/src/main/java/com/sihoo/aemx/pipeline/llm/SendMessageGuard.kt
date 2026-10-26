package com.sihoo.aemx.pipeline.llm

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionType

/**
 * send_message 계획이 사용자가 말한 내용만 보내는지 코드로 확인한다.
 *
 * 메시지는 한 번 보내면 되돌릴 수 없는데 1.7B 모델은 보낼 말을 지어내거나 바꿔 낼 수 있다.
 * 그래서 "~라고/~다고 보내줘" 꼴에서 말한 내용을 뽑을 수 있으면 모델의 value 보다 그 값을 먼저 쓴다.
 * 모델이 "테스트라고 보내줘" 처럼 지시 꼬리까지 value 에 넣어도 발화에 그대로 있어 통과해 버리기 때문이다.
 * 뽑을 수 없을 때만 value 가 사용자 문장에 그대로 들어 있는지로 판단하고, 지시 꼬리로 끝나는
 * value 이거나 문장에 없으면 null 을 돌려 계획을 버리게 한다.
 * 앱 이름은 "카톡" -> "카카오톡", "문자" -> "메시지" 로 맞춘다.
 *
 * "라고" 없이 "배재준한테 안녕 보내줘" 처럼 말한 경우도 "받는 사람+조사, 수단 말(선택), 내용, 보내줘"
 * 꼴로 내용을 뽑는다. 뽑은 내용이 비었거나 "문자" 같은 수단 말뿐이면("배재준한테 문자 보내줘")
 * 보낼 내용이 없는 말이므로, 모델이 value 에 수단 말만 넣어 내도 계획을 버린다.
 *
 * send_message 가 없는 계획은 그대로 돌려준다.
 */
object SendMessageGuard {

    /** 계획을 써도 되면 (필요하면 바로잡은) 계획, 버려야 하면 null. */
    fun check(utterance: String, plan: ActionPlan): ActionPlan? {
        if (plan.steps.none { it.action == ActionType.SEND_MESSAGE }) return plan

        val app = appName(plan.app)
        val steps = plan.steps.map { step ->
            if (step.action != ActionType.SEND_MESSAGE) return@map step
            val value = step.value?.trim().orEmpty()
            val said = extractContent(utterance, step.target) ?: extractLoose(utterance, step.target)
            when {
                said != null -> if (squash(said) == squash(value)) step else step.copy(value = said)
                value.isNotEmpty() && !endsWithInstruction(value) &&
                    !isEmptyContent(value, step.target) &&
                    squash(utterance).contains(squash(value)) -> step
                else -> return null
            }
        }
        if (app == plan.app && steps == plan.steps) return plan
        return plan.copy(app = app, steps = steps)
    }

    internal fun appName(app: String): String = when (app.trim()) {
        "카톡" -> "카카오톡"
        "문자" -> "메시지"
        else -> app
    }

    /** "엄마한테 늦는다고 보내줘" 에서 "늦는다". 받는 사람과 조사는 걷어 낸다. 뽑을 수 없으면 null. */
    internal fun extractContent(utterance: String, recipient: String?): String? {
        val match = SAID.find(utterance.trim()) ?: return null
        return stripFraming(match.groupValues[1], recipient).ifEmpty { null }
    }

    /**
     * "라고" 없이 "배재준한테 안녕 보내줘" 처럼 말한 문장에서 내용을 뽑는다. 뽑은 내용이 비었거나
     * 수단 말("문자", "카톡")뿐이면 보낼 내용이 없는 말이라 null 이다.
     */
    internal fun extractLoose(utterance: String, recipient: String?): String? {
        val match = LOOSE.find(utterance.trim()) ?: return null
        val text = stripFraming(match.groupValues[1], recipient)
        return text.takeUnless { it.isEmpty() || isChannelWord(it) }
    }

    /** value 에서 지시 꼬리, 받는 사람, 수단 말을 걷어 낸 나머지가 비었거나 수단 말뿐인지. */
    internal fun isEmptyContent(value: String, recipient: String?): Boolean {
        val rest = stripFraming(TAIL.replace(value.trim(), ""), recipient)
        val who = recipient?.trim().orEmpty()
        return rest.isEmpty() || isChannelWord(rest) || (who.isNotEmpty() && squash(rest) == squash(who))
    }

    /** 앞의 받는 사람과 조사, 수단 말(으로/로)을 걷어 내고 따옴표를 벗긴다. */
    private fun stripFraming(raw: String, recipient: String?): String {
        var text = raw.trim().trim('"', '\'', '“', '”').trim()
        val who = recipient?.trim().orEmpty()
        if (who.isNotEmpty() && text.startsWith(who)) {
            text = text.substring(who.length).trimStart()
            RECIPIENT_PARTICLES.firstOrNull { text.startsWith(it) }?.let { text = text.substring(it.length).trimStart() }
        }
        text = CHANNEL.replace(text, "")
        return text.trim('"', '\'', '“', '”').trim()
    }

    private fun isChannelWord(text: String): Boolean = squash(text) in CHANNEL_WORDS

    /** "보내줘", "라고 보내줘", "전해줘" 같은 지시 꼬리로 끝나면 보낼 말이 아니라 명령문이다. */
    internal fun endsWithInstruction(value: String): Boolean = TAIL.containsMatchIn(value.trim())

    /** 공백과 따옴표는 STT 와 모델마다 달라서 비교할 때는 없앤다. */
    private fun squash(text: String): String = text.filterNot { it.isWhitespace() || it in "\"'“”" }

    private val SAID = Regex("^(.+?)\\s*(?:이?라고|이?라구|(?<=다)고)\\s*(?:문자|카톡|카카오톡|메시지|메세지)?\\s*(?:보내|전해|남겨|말해|써)")

    /** "라고" 없이 내용만 말한 꼴. 내용 뒤에 바로 보내 가 온다. "전해줘"는 안부를 대신 전하라는 말일 수 있어 뺀다. */
    private val LOOSE = Regex("^(.+?)\\s*(?:보내)")

    private val CHANNEL_WORDS = setOf("문자", "카톡", "카카오톡", "메시지", "메세지", "톡")

    private val TAIL = Regex("(?:이?라고|이?라구)?\\s*(?:보내|전해|남겨|말해|써)[^\\s]*$")

    /** 받는 사람 뒤에 오는 "카톡으로", "문자로" 같은 수단 말. */
    private val CHANNEL = Regex("^(?:카카오톡|카톡|문자|메시지|메세지)(?:으로|로)\\s*")

    /** 긴 조사가 먼저 와야 한다. */
    private val RECIPIENT_PARTICLES = listOf("에게", "한테", "께")
}
