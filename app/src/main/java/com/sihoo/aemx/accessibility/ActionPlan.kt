package com.sihoo.aemx.accessibility

import org.json.JSONException
import org.json.JSONObject

data class ActionPlan(
    val app: String,
    val steps: List<ActionStep>,
)

/** 계획 하나를 끝까지 실행한 결과. 실패하면 그 시점 화면을 함께 돌려준다. */
sealed interface PlanOutcome {
    data object Completed : PlanOutcome

    data class Failed(
        val stepIndex: Int,
        val step: ActionStep,
        val message: String,
        val screen: ScreenDescription,
    ) : PlanOutcome
}

data class ActionStep(
    val action: ActionType,
    val target: String,
    val value: String? = null,
)

enum class ActionType(val wireName: String) {
    OPEN_APP("open_app"),
    TAP("tap"),
    INPUT_TEXT("input_text"),
    SEARCH("search"),
    PLAY_FIRST("play_first"),
    NEXT_VIDEO("next_video"),
    SCROLL("scroll"),
    WAIT("wait"),

    /** 카카오톡·문자로 메시지를 보낸다. target 은 받는 사람, value 는 사용자가 말한 내용. */
    SEND_MESSAGE("send_message");

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
            // 앱 이름이 고정 표에 없어도 통과시킨다. 설치된 앱의 이름은 실행할 때 풀어 보고,
            // 못 찾으면 그 단계에서 "설치되지 않았거나 찾을 수 없는 앱"으로 실패한다.

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
                    if (action == ActionType.SEND_MESSAGE) validateSendMessage(app, value, index)
                    require(action != ActionType.OPEN_APP || sameApp(target, app)) {
                        "open_app target은 plan.app과 같아야 합니다."
                    }
                    require(action != ActionType.SEARCH || AppLauncher.supportsDirectSearch(app)) {
                        "search는 네이버, 구글, 유튜브, 유튜브 뮤직에서만 지원합니다."
                    }
                    require(action != ActionType.PLAY_FIRST || AppLauncher.packageFor(app) in setOf(
                        AppLauncher.YOUTUBE_PACKAGE, AppLauncher.YOUTUBE_MUSIC_PACKAGE,
                    )) { "play_first는 유튜브와 유튜브 뮤직에서만 지원합니다." }
                    require(action != ActionType.NEXT_VIDEO || AppLauncher.packageFor(app) in setOf(
                        AppLauncher.YOUTUBE_PACKAGE, AppLauncher.INSTAGRAM_PACKAGE,
                    )) { "next_video는 유튜브와 인스타그램에서만 지원합니다." }
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

    /** 같은 앱을 가리키는지. 하나라도 고정 표에 있으면 패키지로, 둘 다 없으면 이름(공백·대소문자 무시)으로 비교한다. */
    private fun sameApp(a: String, b: String): Boolean {
        val packageA = AppLauncher.packageFor(a)
        val packageB = AppLauncher.packageFor(b)
        return if (packageA != null || packageB != null) {
            packageA == packageB
        } else {
            AppLauncher.normalizeName(a) == AppLauncher.normalizeName(b)
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
        require(action != ActionType.SEARCH || value == null) { "search 검색어는 target에 넣어 주세요." }
        require(action != ActionType.PLAY_FIRST || value == null) { "play_first에는 value를 사용하지 않습니다." }
        if (action == ActionType.NEXT_VIDEO && value != null) WaitDuration.millis(value)
        require(action != ActionType.SCROLL || target in setOf("up", "down", "forward", "backward")) {
            "scroll target은 up/down/forward/backward만 지원합니다."
        }
        if (action == ActionType.WAIT) WaitDuration.millis(value ?: target)
    }

    private fun validateSendMessage(app: String, value: String?, index: Int) {
        if (MessageRules.channelFor(app) == null) {
            throw ActionPlanParseException("steps[$index].send_message는 카카오톡과 메시지(문자) 앱에서만 지원합니다.")
        }
        if (value.isNullOrBlank()) {
            throw ActionPlanParseException("steps[$index].send_message에는 value(보낼 내용)가 필요합니다.")
        }
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
