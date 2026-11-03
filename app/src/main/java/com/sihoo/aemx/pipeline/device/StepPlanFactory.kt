package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.port.ActionExecutor.Command
import org.json.JSONArray
import org.json.JSONObject
import com.sihoo.aemx.accessibility.ActionType as A11yType

/**
 * 오케스트레이터의 [Command] 하나를 접근성 서비스가 받는 "step 하나짜리 계획 JSON" 으로 바꾼다.
 *
 * 서비스는 계획 단위로만 받으므로, step 단위 호출은 이렇게 한 개짜리 계획을 매번 제출해서 맞춘다.
 * 서비스 쪽 파서가 검증(지원 앱, action 별 제약)을 그대로 해 주니 여기서는 모양만 맞춘다.
 */
internal object StepPlanFactory {

    class Built(val json: String, val action: A11yType)

    /**
     * 서비스 파서는 wait 말고는 target 이 비어 있는 것을 거부한다.
     * 대상이 따로 없는 동작(첫 영상, 다음 영상)에는 아무 값이나 채운다. 서비스는 읽지 않는다.
     */
    private const val PLACEHOLDER_TARGET = "-"

    /**
     * @param appName 서비스가 아는 앱 이름. 서비스는 이 이름의 패키지가 맨 앞에 떠 있을 때만 조작한다.
     * @throws IllegalArgumentException 서비스가 받을 수 없는 명령일 때(메시지는 사람이 읽는 사유)
     */
    fun build(command: Command, appName: String): Built {
        val action: A11yType
        val target: String
        var value: String? = null

        when (command) {
            is Command.LaunchApp -> {
                action = A11yType.OPEN_APP
                target = appName
            }

            is Command.Tap -> {
                action = A11yType.TAP
                target = command.label
            }

            is Command.InputText -> {
                action = A11yType.INPUT_TEXT
                // 서비스는 입력란을 이름으로 찾는다. 이름이 없으면 어디에 넣을지 알 수 없다.
                target = command.fieldLabel?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("입력란 이름이 없어 어디에 넣을지 알 수 없다")
                value = command.text
            }

            // 검색어는 value 가 아니라 target 이다(서비스 규칙).
            is Command.Search -> {
                action = A11yType.SEARCH
                target = command.query
            }

            Command.PlayFirst -> {
                action = A11yType.PLAY_FIRST
                target = PLACEHOLDER_TARGET
            }

            // 순번은 value 로 간다(서비스 규칙).
            is Command.PlayNth -> {
                action = A11yType.PLAY_FIRST
                target = PLACEHOLDER_TARGET
                value = command.ordinal.toString()
            }

            is Command.OpenVideo -> {
                action = A11yType.OPEN_VIDEO
                target = command.videoId
            }

            Command.OpenShorts -> {
                action = A11yType.OPEN_SHORTS
                target = PLACEHOLDER_TARGET
            }

            is Command.NextVideo -> {
                action = A11yType.NEXT_VIDEO
                target = PLACEHOLDER_TARGET
                value = command.delay?.takeIf { it.isNotBlank() }
            }

            is Command.Scroll -> {
                action = A11yType.SCROLL
                target = if (command.direction == Command.Direction.UP) "up" else "down"
            }

            is Command.SendMessage -> {
                action = A11yType.SEND_MESSAGE
                target = command.target
                value = command.value
            }
        }

        require(target.isNotBlank()) { "대상이 비어 있다" }

        val step = JSONObject().apply {
            put("action", action.wireName)
            put("target", target)
            value?.let { put("value", it) }
        }
        val json = JSONObject().apply {
            put("app", appName)
            put("steps", JSONArray().put(step))
        }.toString()
        return Built(json, action)
    }
}

/**
 * 서비스의 고정 표에 있는 앱 이름과 패키지명 사이의 변환. 표에 없는 설치된 앱은 여기서 풀지 않고
 * [AccessibilityAppResolver] 가 푼 이름을 쓴다(서비스 파서는 표에 없는 앱의 open_app 도 받고,
 * 실행할 때 설치된 앱 라벨로 푼다).
 */
internal object AppNames {

    /** 서비스 표의 대표 이름들. 같은 패키지의 별칭("naver" 등)은 넣지 않는다. */
    private val CANONICAL = listOf("교보문고", "알라딘", "네이버", "구글", "유튜브", "인스타그램")

    /** 패키지명에 해당하는 서비스 앱 이름. 서비스가 모르는 패키지면 null. */
    fun forPackage(packageName: String?): String? {
        if (packageName == null) return null
        return CANONICAL.firstOrNull { AppLauncher.packageFor(it) == packageName }
    }

    /** 서비스가 알아듣는 앱 이름인지(별칭 포함). */
    fun isSupported(appName: String): Boolean = AppLauncher.packageFor(appName) != null
}
