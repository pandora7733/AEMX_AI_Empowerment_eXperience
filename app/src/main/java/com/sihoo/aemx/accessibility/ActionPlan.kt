package com.sihoo.aemx.accessibility

import org.json.JSONException
import org.json.JSONObject

data class ActionPlan(
    val app: String,
    val steps: List<ActionStep>,
)

data class ActionStep(
    val action: ActionType,
    val target: String,
    val value: String? = null,
)

enum class ActionType(val wireName: String) {
    OPEN_APP("open_app"),
    TAP("tap"),
    INPUT_CREDENTIAL("input_credential"),
    INPUT_TEXT("input_text"),
    SCROLL("scroll"),
    WAIT("wait");

    companion object {
        fun fromWireName(value: String): ActionType = entries.firstOrNull { it.wireName == value }
            ?: throw ActionPlanParseException("지원하지 않는 action입니다.")
    }
}

object ActionPlanParser {
    fun parse(json: String): ActionPlan {
        try {
            require(json.length <= 100_000) { "Action Plan이 너무 큽니다." }
            val root = JSONObject(json)
            val app = string(root, "app").trim()
            require(app.isNotEmpty()) { "app은 비어 있을 수 없습니다." }
            require(AppLauncher.packageFor(app) != null) { "지원하지 않는 앱입니다." }

            val jsonSteps = root.getJSONArray("steps")
            require(jsonSteps.length() > 0) { "steps는 한 개 이상이어야 합니다." }
            require(jsonSteps.length() <= MAX_STEPS) { "steps는 최대 ${MAX_STEPS}개까지 허용합니다." }
            val steps = buildList {
                for (index in 0 until jsonSteps.length()) {
                    val item = jsonSteps.getJSONObject(index)
                    val action = ActionType.fromWireName(string(item, "action").trim())
                    val target = if (item.has("target")) string(item, "target").trim() else ""
                    val value = if (item.has("value") && !item.isNull("value")) {
                        string(item, "value")
                    } else {
                        null
                    }
                    validate(action, target, value, index)
                    require(action != ActionType.OPEN_APP || AppLauncher.packageFor(target) == AppLauncher.packageFor(app)) {
                        "open_app target은 plan.app과 같아야 합니다."
                    }
                    add(ActionStep(action, target, value))
                }
            }
            return ActionPlan(app, steps)
        } catch (error: ActionPlanParseException) {
            throw error
        } catch (error: JSONException) {
            throw ActionPlanParseException("Action Plan JSON 형식이 올바르지 않습니다.")
        } catch (error: IllegalArgumentException) {
            throw ActionPlanParseException(error.message ?: "Action Plan 값이 올바르지 않습니다.", error)
        }
    }

    private fun string(item: JSONObject, key: String): String =
        item.get(key) as? String ?: throw ActionPlanParseException("$key 필드는 문자열이어야 합니다.")

    private fun validate(action: ActionType, target: String, value: String?, index: Int) {
        if (action != ActionType.WAIT && target.isEmpty()) {
            throw ActionPlanParseException("steps[$index].target은 비어 있을 수 없습니다.")
        }
        if (action == ActionType.INPUT_TEXT && value == null) {
            throw ActionPlanParseException("steps[$index].input_text에는 value가 필요합니다.")
        }
        if (target.length > MAX_FIELD_LENGTH || (value?.length ?: 0) > MAX_FIELD_LENGTH) {
            throw ActionPlanParseException("steps[$index]의 target/value가 너무 깁니다.")
        }
        if (action == ActionType.INPUT_CREDENTIAL && target !in setOf("id", "password")) {
            throw ActionPlanParseException("steps[$index].input_credential target은 id 또는 password여야 합니다.")
        }
        require(action != ActionType.INPUT_CREDENTIAL || value == null) { "credential value는 JSON으로 받지 않습니다." }
        require(action != ActionType.SCROLL || target in setOf("up", "down", "forward", "backward")) {
            "scroll target은 up/down/forward/backward만 지원합니다."
        }
        if (action == ActionType.WAIT) WaitDuration.millis(value ?: target)
    }

    private const val MAX_STEPS = 50
    private const val MAX_FIELD_LENGTH = 2_000
}

/** Local extension until the LLM team defines wait units. Bare integers are seconds. */
internal object WaitDuration {
    fun millis(raw: String): Long {
        if (raw.isBlank()) return 1_000L
        val match = Regex("^(\\d+)(ms|s)?$").matchEntire(raw.trim())
            ?: throw ActionPlanParseException("wait는 정수 초 또는 ms/s 단위여야 합니다.")
        val number = match.groupValues[1].toLongOrNull()
            ?: throw ActionPlanParseException("wait 시간이 너무 큽니다.")
        val multiplier = if (match.groupValues[2] == "ms") 1L else 1_000L
        require(number <= 30_000L / multiplier) { "wait는 최대 30초입니다." }
        return number * multiplier
    }
}

class ActionPlanParseException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
