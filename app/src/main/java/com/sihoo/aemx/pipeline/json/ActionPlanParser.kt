package com.sihoo.aemx.pipeline.json

import com.sihoo.aemx.pipeline.model.ActionPlan
import com.sihoo.aemx.pipeline.model.ActionStep
import com.sihoo.aemx.pipeline.model.ActionType
import com.sihoo.aemx.pipeline.model.Expectation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * LLM 출력 문자열을 [ActionPlan] 으로 바꾸는 파서.
 *
 * Qwen3 1.7B 는 JSON 앞뒤에 설명을 붙이거나 ``` 울타리를 두르는 일이 있고,
 * thinking 모드가 켜져 있어 `<think>` 블록이 앞에 붙어 나올 수도 있다.
 * 그래서 엄격하게 파싱하지 않고, 본문에서 JSON 객체를 도려낸 뒤 읽는다.
 *
 * 읽다가 애매한 부분은 버리지 않고 [ParseResult.Success.warnings] 에 모아 둔다.
 */
object ActionPlanParser {

    fun parse(raw: String): ParseResult {
        val json = extractJsonObject(raw)
            ?: return ParseResult.Failure("응답에서 JSON 객체를 찾지 못했다", raw)

        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return ParseResult.Failure("JSON 문법 오류: ${e.message}", raw)
        }

        val warnings = mutableListOf<String>()

        val app = root.optStringOrNull("app")
        if (app == null) warnings += "app 이름이 없다"

        val stepsArray = root.optJSONArray("steps")
            ?: return ParseResult.Failure("steps 배열이 없다", raw)

        val steps = mutableListOf<ActionStep>()
        for (i in 0 until stepsArray.length()) {
            val obj = stepsArray.optJSONObject(i)
            if (obj == null) {
                warnings += "steps[$i] 가 객체가 아니라 건너뛴다"
                continue
            }
            steps += readStep(obj, index = steps.size, warnings = warnings)
        }

        if (steps.isEmpty()) {
            return ParseResult.Failure("실행할 step 이 하나도 없다", raw)
        }

        return ParseResult.Success(
            plan = ActionPlan(app = app.orEmpty(), steps = steps),
            warnings = warnings
        )
    }

    private fun readStep(
        obj: JSONObject,
        index: Int,
        warnings: MutableList<String>
    ): ActionStep {
        val rawAction = obj.optStringOrNull("action")
        val action = ActionType.from(rawAction)
        if (action == ActionType.UNKNOWN) {
            warnings += "step ${index + 1}: 모르는 action \"$rawAction\""
        }

        // 지금 프롬프트는 expect 를 만들지 않는다. 나중에 프롬프트가 바뀌어
        // 모델이 직접 주게 되면 추론값 대신 이 값이 쓰인다.
        val expect = obj.optJSONObject("expect")?.let { readExpectation(it) } ?: Expectation.NONE

        return ActionStep(
            index = index,
            action = action,
            target = obj.optStringOrNull("target"),
            value = obj.optStringOrNull("value"),
            expect = expect,
            timeoutMs = obj.optLong("timeoutMs", ActionStep.DEFAULT_TIMEOUT_MS)
                .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        )
    }

    private fun readExpectation(obj: JSONObject): Expectation = Expectation(
        packageName = obj.optStringOrNull("packageName"),
        anyText = obj.optStringList("anyText"),
        absentText = obj.optStringList("absentText"),
        requireScreenChange = obj.optBoolean("requireScreenChange", false),
        note = obj.optStringOrNull("note").orEmpty()
    )

    /**
     * 본문에서 첫 번째 완결된 JSON 객체를 도려낸다.
     *
     * 문자열 리터럴 안의 중괄호에 속지 않도록 따옴표와 역슬래시 이스케이프를 따라가며 센다.
     * 울타리(```)나 `<think>` 블록, 앞뒤 설명 문장은 자연히 버려진다.
     */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** org.json 은 없는 값을 문자열 "null" 로 돌려주기도 해서 직접 걸러낸다. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key).trim()
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** 문자열 배열을 읽되, 모델이 배열 대신 문자열 하나를 준 경우도 받아 준다. */
    private fun JSONObject.optStringList(key: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        optJSONArray(key)?.let { return it.toStringList() }
        val single = optString(key).trim()
        return if (single.isEmpty() || single == "null") emptyList() else listOf(single)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i ->
            if (isNull(i)) null else optString(i).trim().takeIf { it.isNotEmpty() }
        }

    private const val MIN_TIMEOUT_MS = 500L
    private const val MAX_TIMEOUT_MS = 30_000L
}

sealed interface ParseResult {
    data class Success(val plan: ActionPlan, val warnings: List<String> = emptyList()) : ParseResult

    /** 파싱 실패. [raw] 는 재프롬프트할 때 모델에게 되돌려 주기 위해 들고 있는다. */
    data class Failure(val reason: String, val raw: String) : ParseResult
}
