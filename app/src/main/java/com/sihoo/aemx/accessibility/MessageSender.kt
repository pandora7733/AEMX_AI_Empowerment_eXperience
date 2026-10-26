package com.sihoo.aemx.accessibility

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

internal enum class MessageChannel { KAKAO, SMS }

/**
 * 메시지 보내기의 판정 로직 중 안드로이드 없이 돌릴 수 있는 부분.
 *
 * 원칙은 "맞으면 보내고, 불확실하면 실패"다. 받는 사람이 정확히 한 명이 아니거나 화면이
 * 기대와 다르면 보내지 않는다. 보낸 메시지는 되돌릴 수 없어서 추측으로 밀고 가지 않는다.
 */
internal object MessageRules {
    /** 연락처 한 줄. 한 사람이 번호를 여러 개 가지면 같은 [contactId] 로 여러 줄이 온다. */
    data class ContactRow(val contactId: Long, val name: String, val number: String, val type: Int)

    sealed interface ContactPick {
        data class Found(val name: String, val number: String) : ContactPick
        data object Missing : ContactPick
        data class Ambiguous(val reason: String) : ContactPick
    }

    /** ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE. 순수 로직으로 두려고 값을 그대로 적는다. */
    const val TYPE_MOBILE = 2

    /** 앱 이름이 어느 채널인지. 고정 표의 이름만 인정한다(문자는 기본 SMS 앱으로 바뀐다). */
    fun channelFor(app: String): MessageChannel? = when (AppLauncher.packageFor(app)) {
        AppLauncher.KAKAOTALK_PACKAGE -> MessageChannel.KAKAO
        AppLauncher.SAMSUNG_MESSAGES_PACKAGE -> MessageChannel.SMS
        else -> null
    }

    /**
     * 입력란으로 쳐 줄 최소 너비(px). 보이지 않는 작은 EditText 가 따로 있는 화면이 있어서(카톡 검색 화면의
     * input_focus 는 38px), 너비로 걸러 진짜 입력란(dump 확인: 1000px 대)만 남긴다.
     */
    const val MIN_INPUT_WIDTH = 200

    /** 말풍선 content-desc 에서 내용 뒤에 붙는 시각("오후 5:55", "17:55"). 공백은 [normalize] 로 이미 빠진 뒤에 본다. */
    private val BUBBLE_TIME = Regex("^(오전|오후|am|pm)?[0-9]{1,2}:[0-9]{2}")

    /**
     * 보낸 말풍선인지. 다음 중 하나면 맞다.
     * - text 나 content-desc 가 내용과 정확히 같다.
     * - content-desc 가 "내용, 시각…" 처럼 내용 뒤에 쉼표로 덧붙는다(카톡 추정).
     * - content-desc 가 "내용 오후 5:55 수신인이 읽지 않음" 처럼 내용 뒤에 공백과 시각이 붙는다.
     *   dump 확인(삼성 메시지): 서비스는 flagIncludeNotImportantViews 가 없어서 말풍선 안의 글자 노드
     *   (id content_text_view)를 못 보고, 바깥 LinearLayout(id list_item_content_container)의
     *   content-desc 만 본다. 그래서 text 가 아니라 이 모양으로 알아봐야 한다.
     */
    fun isBubbleOf(value: String, text: String?, desc: String?): Boolean {
        if (sameText(value, text) || sameText(value, desc)) return true
        val wanted = normalize(value)
        val described = desc?.let(::normalize) ?: return false
        if (wanted.isEmpty() || !described.startsWith(wanted)) return false
        val rest = described.substring(wanted.length)
        return rest.startsWith(",") || BUBBLE_TIME.containsMatchIn(rest)
    }

    /** 공백을 없애고 소문자로. 음성 인식이 "김 철수" 로 띄어 적어도 "김철수" 와 같게 본다. */
    fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    fun sameText(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val left = normalize(a)
        return left.isNotEmpty() && left == normalize(b)
    }

    /** 숫자만 남기고, 국가번호 +82 는 0 으로 바꿔 010 형태로 맞춘다. */
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (raw.trim().startsWith("+82") && digits.length > 2) "0" + digits.drop(2) else digits
    }

    private val DIGIT_WORDS = mapOf(
        '공' to 0, '영' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5,
        '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )
    private val PLACE_WORDS = mapOf('십' to 10, '백' to 100, '천' to 1_000)
    private const val TEN_THOUSAND = '만'

    private fun isNumeralWord(c: Char): Boolean = c in DIGIT_WORDS || c in PLACE_WORDS || c == TEN_THOUSAND

    /**
     * 말한 이름 속 한글 숫자 덩어리를 아라비아 숫자로 바꾼다("이일일공오배재준" -> "21105배재준",
     * "이만천백오 배재준" -> "21105 배재준"). 이름 부분은 그대로 둔다.
     *
     * 판단 규칙
     * - 숫자 글자(공영일이삼사오육륙칠팔구, 십백천만)가 **2개 이상 이어진 덩어리만** 바꾼다. 한 글자는 "이"씨
     *   성처럼 이름일 수 있어서 그대로 둔다("이민수" 는 그대로). 공백·다른 글자가 덩어리를 끊는다.
     * - 덩어리에 십/백/천/만이 없으면 한 자리씩 읽기("이일일공오" -> 21105).
     * - 있으면 자릿수 읽기("이만천백오" -> 21105, "이십일" -> 21). 자릿수 앞에 숫자가 없으면 1("천" -> 1000).
     *   자리 순서가 어긋나거나(백십천) 숫자가 연달아 두 개 오는 등 읽을 수 없으면 덩어리를 바꾸지 않는다.
     * - 한 덩어리에 이름 글자가 섞일 수 있다("이일일공오이민수" 의 "이" 는 성이다). 그래서 이 함수의 결과는
     *   정확히 맞는 후보가 없을 때만 쓰고, 틀리게 바뀌면 후보가 안 맞아 실패할 뿐 엉뚱한 사람에게 가지 않는다.
     */
    fun digitizeName(spoken: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < spoken.length) {
            if (!isNumeralWord(spoken[i])) {
                out.append(spoken[i])
                i++
                continue
            }
            var j = i
            while (j < spoken.length && isNumeralWord(spoken[j])) j++
            val run = spoken.substring(i, j)
            out.append(if (run.length >= 2) numberOfRun(run) ?: run else run)
            i = j
        }
        return out.toString()
    }

    private fun numberOfRun(run: String): String? {
        if (run.none { it in PLACE_WORDS || it == TEN_THOUSAND }) {
            return run.map { DIGIT_WORDS.getValue(it) }.joinToString("")
        }
        var total = 0L
        var section = 0L
        var digit = -1
        var lastPlace = Int.MAX_VALUE
        var sawTenThousand = false
        for (c in run) {
            val d = DIGIT_WORDS[c]
            val place = PLACE_WORDS[c]
            when {
                d != null -> {
                    // 숫자가 연달아 오거나 0 이 자릿수 읽기에 섞이면 읽을 수 없다.
                    if (digit != -1 || d == 0) return null
                    digit = d
                }
                place != null -> {
                    if (place >= lastPlace) return null
                    section += (if (digit == -1) 1 else digit).toLong() * place
                    digit = -1
                    lastPlace = place
                }
                else -> {
                    if (sawTenThousand) return null
                    sawTenThousand = true
                    val part = section + (if (digit == -1) 0 else digit)
                    total += (if (part == 0L) 1L else part) * 10_000L
                    section = 0
                    digit = -1
                    lastPlace = Int.MAX_VALUE
                }
            }
        }
        total += section + (if (digit == -1) 0 else digit)
        return total.toString()
    }

    /** 말한 이름이 이 글자 수보다 짧으면 [looselyNamed] 를 쓰지 않는다. 한 글자는 너무 많은 이름에 걸린다. */
    private const val MIN_LOOSE_NAME_LENGTH = 2

    /**
     * 후보 이름이 말한 이름에 숫자·기호·이모지만 덧붙은 모양인지("21105배재준", "배재준 010", "배재준😊").
     * 말한 이름을 한 번 빼고 남은 글자에 한글·영문 같은 문자가 하나라도 있으면 다른 사람이다
     * ("배재준엄마", "배재준형", "김배재준"). 정확히 같은 후보가 없을 때만 쓴다.
     */
    fun looselyNamed(candidate: String, spoken: String): Boolean {
        val wanted = normalize(spoken)
        if (wanted.codePointCount(0, wanted.length) < MIN_LOOSE_NAME_LENGTH) return false
        val full = normalize(candidate)
        val at = full.indexOf(wanted)
        if (at < 0) return false
        val rest = full.removeRange(at, at + wanted.length)
        return rest.codePoints().noneMatch { codePoint -> Character.isLetter(codePoint) }
    }

    /** 후보가 둘 이상일 때 사용자에게 읽어 줄 실패 문구. 문자와 카톡이 같이 쓴다. */
    fun tooManyMessage(spoken: String, count: Int): String =
        "$spoken 이라는 연락처가 ${count}명이라 보내지 않았어요. 더 정확한 이름으로 말해 주세요."

    /** 정확히 같은 이름의 행, 없으면 숫자·기호가 덧붙은 이름([looselyNamed])의 행. */
    private fun namedRows(rows: List<ContactRow>, name: String): List<ContactRow> =
        rows.filter { sameText(it.name, name) }.ifEmpty { rows.filter { looselyNamed(it.name, name) } }

    /**
     * 받는 사람이 한 명으로 정해지고 보낼 번호가 하나로 정해질 때만 [ContactPick.Found].
     *
     * - 이름이 정확히 같은 연락처가 있으면 그것만 본다. 없을 때만 "21105배재준" 같은 [looselyNamed] 를 본다.
     * - 그래도 없을 때만 말한 이름의 한글 숫자를 바꿔([digitizeName]) 같은 규칙으로 한 번 더 본다.
     * - 이름이 같은 연락처가 여러 개여도 번호가 모두 같으면(구글·SIM 중복) 한 사람으로 본다.
     * - 번호가 다른 사람이 둘 이상이면 보내지 않는다([tooManyMessage]).
     * - 연락처 하나에 번호가 여러 개면 휴대전화가 정확히 하나일 때만 그걸 쓴다.
     * - 그 밖의 부분 일치는 쓰지 않는다. "철수" 로 "김철수" 에게 보내지 않는다.
     */
    fun pickContact(rows: List<ContactRow>, name: String): ContactPick {
        val matching = namedRows(rows, name).ifEmpty {
            // 말한 이름으로 하나도 안 맞을 때만 한글 숫자를 아라비아 숫자로 바꾼 이름으로 한 번 더 찾는다.
            val digits = digitizeName(name)
            if (digits == name) emptyList() else namedRows(rows, digits)
        }
        if (matching.isEmpty()) return ContactPick.Missing
        val shown = matching.first().name

        val numbers = matching.map { normalizeNumber(it.number) }.filter(String::isNotEmpty).distinct()
        if (numbers.isEmpty()) return ContactPick.Ambiguous("$shown 연락처에 전화번호가 없어 보내지 않았어요.")

        val people = matching.map { it.contactId }.distinct()
        if (people.size > 1 && numbers.size > 1) {
            return ContactPick.Ambiguous(tooManyMessage(name, people.size))
        }
        if (numbers.size == 1) return ContactPick.Found(shown, numbers.single())

        val mobiles = matching.filter { it.type == TYPE_MOBILE }
            .map { normalizeNumber(it.number) }.filter(String::isNotEmpty).distinct()
        return if (mobiles.size == 1) {
            ContactPick.Found(shown, mobiles.single())
        } else {
            ContactPick.Ambiguous("$shown 의 전화번호가 여러 개라 어느 번호로 보낼지 몰라 보내지 않았어요.")
        }
    }

    /**
     * 화면 글자 중에 받는 사람이 있는지. 이름이 들어 있거나, 번호 뒤 8자리가 숫자열에 들어 있으면 맞다.
     * 번호는 "010-1234-5678" 처럼 구분자가 달라도 되도록 숫자만 비교한다.
     */
    fun mentionsRecipient(texts: Collection<String>, name: String, number: String): Boolean {
        val wantedName = normalize(name)
        val tail = normalizeNumber(number).takeLast(8).takeIf { it.length == 8 }
        return texts.any { text ->
            (wantedName.isNotEmpty() && normalize(text).contains(wantedName)) ||
                (tail != null && text.filter(Char::isDigit).contains(tail))
        }
    }
}

/**
 * 카카오톡·문자로 메시지를 보낸다(`send_message`).
 *
 * 화면 하나를 한 번 보고 끝내지 않고, 일정 간격으로 화면을 다시 읽으며 단계를 한 칸씩 나아간다.
 * 어느 단계에서든 기대와 다르면 기다리다 시간이 지나면 실패로 끝낸다. 보내기 버튼을 누른 뒤에는
 * 같은 메시지를 두 번 보내지 않도록 다시 누르지 않는다.
 *
 * 문자는 여기서 직접 하고, 카톡은 [KakaoShareFlow] 로 넘긴다(채팅방 화면의 접근성 노드가 비어서
 * 공유 인텐트의 선택 화면에서 보낸다). 이름 판정 규칙은 [MessageRules] 와 같이 쓴다.
 */
internal class MessageSender(
    private val service: AemxAccessibilityService,
    private val handler: Handler,
    private val appLauncher: AppLauncher,
) {
    fun send(
        app: String,
        step: ActionStep,
        isCurrent: () -> Boolean,
        onDone: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val channel = MessageRules.channelFor(app)
            ?: return onFailed("메시지는 카카오톡과 문자 앱으로만 보낼 수 있습니다.")
        val value = step.value
        if (value.isNullOrBlank()) return onFailed("보낼 내용이 비어 있습니다.")
        if (step.target.isBlank()) return onFailed("받는 사람이 비어 있습니다.")
        when (channel) {
            MessageChannel.SMS ->
                prepareSms(app, step.target, value, isCurrent, onDone, onFailed)?.let(handler::post)
            // 카톡 26.9.1 부터 채팅방 화면의 접근성 노드가 비어서, 공유 인텐트로 보낸다.
            MessageChannel.KAKAO -> KakaoShareFlow(
                service,
                handler,
                looseMatch = KakaoShareRules.NameMatch { wanted, shown -> MessageRules.looselyNamed(shown, wanted) },
            ).send(step.target, value, isCurrent, onDone, onFailed)
        }
    }

    // ---- 문자 ----

    /** 연락처를 확인하고 기본 SMS 앱을 연다. 여기서 막히면 실패를 알리고 null. */
    private fun prepareSms(
        app: String,
        target: String,
        value: String,
        isCurrent: () -> Boolean,
        onDone: () -> Unit,
        onFailed: (String) -> Unit,
    ): Runnable? {
        if (service.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            onFailed("연락처 권한이 없어 받는 사람을 찾을 수 없습니다.")
            return null
        }
        val pick = MessageRules.pickContact(readContacts(), target)
        val contact = when (pick) {
            is MessageRules.ContactPick.Found -> pick
            MessageRules.ContactPick.Missing -> {
                onFailed("연락처에서 \"$target\" 이름을 찾지 못했습니다.")
                return null
            }
            is MessageRules.ContactPick.Ambiguous -> {
                onFailed(pick.reason)
                return null
            }
        }
        val smsPackage = appLauncher.resolve(app)
        if (smsPackage == null) {
            onFailed("기본 문자 앱을 찾지 못했습니다.")
            return null
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact.number))).apply {
            putExtra("sms_body", value)
            setPackage(smsPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (error: RuntimeException) {
            onFailed("문자 앱을 열지 못했습니다.")
            return null
        }
        return SmsRun(smsPackage, contact.name, contact.number, value, isCurrent, onDone, onFailed)
    }

    private fun readContacts(): List<MessageRules.ContactRow> {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val rows = ArrayList<MessageRules.ContactRow>()
        service.contentResolver.query(phone, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += MessageRules.ContactRow(
                    contactId = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    number = cursor.getString(2).orEmpty(),
                    type = cursor.getInt(3),
                )
            }
        }
        return rows
    }

    // ---- 공통: 단계 하나를 일정 간격으로 되풀이하는 틀 ----

    private sealed interface Progress {
        data object Wait : Progress
        data object Done : Progress
        data class Fail(val message: String) : Progress
    }

    private abstract inner class Run(
        private val isCurrent: () -> Boolean,
        private val onDone: () -> Unit,
        private val onFailed: (String) -> Unit,
        private val overallTimeoutMs: Long,
    ) : Runnable {
        private val startedAt = SystemClock.uptimeMillis()

        /** 보내기를 누른 뒤의 확인 기한. 0 이면 아직 안 눌렀다. */
        protected var verifyDeadline = 0L

        /** 단계 하나를 진행한다. 기다리는 중에 시간이 다 되면 [timeoutMessage] 로 실패한다. */
        protected abstract fun advance(): Progress
        protected abstract fun timeoutMessage(): String

        final override fun run() {
            if (!isCurrent()) return
            val now = SystemClock.uptimeMillis()
            val progress = try {
                advance()
            } catch (_: RuntimeException) {
                Progress.Fail("화면 조작 중 오류가 발생했습니다.")
            }
            when (progress) {
                Progress.Done -> onDone()
                is Progress.Fail -> onFailed(progress.message)
                Progress.Wait -> {
                    val deadline = if (verifyDeadline != 0L) verifyDeadline else startedAt + overallTimeoutMs
                    if (now >= deadline) onFailed(timeoutMessage()) else handler.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    // ---- 문자 흐름 ----

    private inner class SmsRun(
        private val smsPackage: String,
        private val contactName: String,
        private val number: String,
        private val value: String,
        isCurrent: () -> Boolean,
        onDone: () -> Unit,
        onFailed: (String) -> Unit,
    ) : Run(isCurrent, onDone, onFailed, SMS_TIMEOUT_MS) {
        private var readySince = 0L
        private var sent = false
        private var bubblesBefore = 0
        private var lastBlocker = "문자 앱 화면"

        override fun timeoutMessage(): String =
            if (sent) SENT_UNVERIFIED else "$lastBlocker 을(를) 확인하지 못해 보내지 않았습니다."

        override fun advance(): Progress {
            val root = service.rootInActiveWindow ?: return Progress.Wait
            if (root.packageName?.toString() != smsPackage) return Progress.Wait
            val screen = Screen.read(root)
            if (sent) return verifySent(screen, value, bubblesBefore)

            val now = SystemClock.uptimeMillis()
            if (readySince == 0L) readySince = now
            // 앱이 sms_body 를 입력란에 채우는 데 시간이 걸린다.
            if (now - readySince < SETTLE_MS) return Progress.Wait

            // dump 확인: 본문은 id message_edit_text 의 EditText 에 sms_body 가 그대로 들어 있고,
            // 받는 사람은 id composer_title 의 text/content-desc(연락처에 없으면 번호)로 보인다.
            // 연락처에 있는 사람의 이름 표시는 dump 가 없어 추정이다(번호 뒤 8자리도 같이 본다).
            val editables = screen.inputs
            val body = editables.firstOrNull { MessageRules.sameText(value, it.text) && !it.showingHint }
            if (body == null) {
                lastBlocker = "입력란의 보낼 내용"
                // 받는 사람 칸이 따로 있을 수 있어서, 아래쪽 입력란이 하나뿐일 때만 직접 채운다.
                val only = editables.singleOrNull { it.top >= screen.height * INPUT_AREA }
                if (only != null && now - readySince > FILL_WAIT_MS) setText(only.node, value)
                return Progress.Wait
            }
            val shown = screen.nonEditableLabels()
            if (!MessageRules.mentionsRecipient(shown, contactName, number)) {
                lastBlocker = "받는 사람"
                return Progress.Wait
            }
            lastBlocker = "보내기 버튼"
            val button = screen.singleButton(SMS_SEND_LABELS) ?: return Progress.Wait
            bubblesBefore = screen.bubbleCount(value)
            if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Progress.Wait
            sent = true
            verifyDeadline = SystemClock.uptimeMillis() + VERIFY_MS
            Log.i(TAG, "문자 보내기 버튼을 눌렀다")
            return Progress.Wait
        }
    }

    // ---- 전송 확인(문자) ----

    /**
     * 입력란에서 내용이 사라졌고, 같은 글자의 말풍선이 보내기 전보다 늘었으면 보낸 것이다.
     * 같은 말을 전에도 보냈을 수 있어서 말풍선이 "있는지"가 아니라 "늘었는지"를 본다.
     */
    private fun verifySent(screen: Screen, value: String, bubblesBefore: Int): Progress {
        val stillTyped = screen.editables.any { MessageRules.sameText(value, it.text) && !it.showingHint }
        if (!stillTyped && screen.bubbleCount(value) > bubblesBefore) return Progress.Done
        return Progress.Wait
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    // ---- 화면 읽기 ----

    private class Item(
        val node: AccessibilityNodeInfo,
        val text: String?,
        val desc: String?,
        val editable: Boolean,
        val showingHint: Boolean,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    ) {
        fun labels(): List<String> = listOfNotNull(text, desc).map(String::trim).filter(String::isNotEmpty)

        /** 입력란이 비어 있다. 힌트 글자("메시지 입력")가 text 로 보이는 기기도 비어 있는 것으로 본다. */
        val isBlankInput: Boolean get() = showingHint || text.isNullOrBlank()
    }

    private class Screen(val items: List<Item>, val height: Int) {
        val editables: List<Item> get() = items.filter { it.editable }

        /** 진짜 입력란만. 보이지 않는 작은 EditText 는 뺀다([MessageRules.MIN_INPUT_WIDTH]). */
        val inputs: List<Item> get() = editables.filter { it.right - it.left >= MessageRules.MIN_INPUT_WIDTH }

        fun nonEditableLabels(): List<String> = items.filter { !it.editable }.flatMap { it.labels() }

        fun has(labels: List<String>): Boolean = items.any { item ->
            !item.editable && item.labels().any { label -> labels.any { MessageRules.sameText(it, label) } }
        }

        /** 글자가 정확히 같은 눌릴 수 있는 버튼이 딱 하나일 때 그 눌릴 곳. 둘 이상이면 모호해서 null. */
        fun singleButton(labels: List<String>): AccessibilityNodeInfo? {
            val found = items.filter { item ->
                !item.editable && item.labels().any { label -> labels.any { MessageRules.sameText(it, label) } }
            }.mapNotNull { NodeFinder.clickableAncestor(it.node) }.distinct()
            return found.singleOrNull()
        }

        /** 입력란이 아닌 곳에 보이는 [value] 말풍선 수([MessageRules.isBubbleOf]). */
        fun bubbleCount(value: String): Int = items.count { item ->
            !item.editable && MessageRules.isBubbleOf(value, item.text, item.desc)
        }

        companion object {
            fun read(root: AccessibilityNodeInfo): Screen {
                val rootBounds = Rect().also(root::getBoundsInScreen)
                val items = ArrayList<Item>()
                val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                var visited = 0
                while (queue.isNotEmpty()) {
                    if (++visited > MAX_NODES) break
                    val node = queue.removeFirst()
                    if (node.isVisibleToUser) {
                        val text = node.text?.toString()
                        val desc = node.contentDescription?.toString()
                        val editable = node.isEditable || node.className?.toString() == "android.widget.EditText"
                        if (editable || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                            val bounds = Rect().also(node::getBoundsInScreen)
                            val hint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
                            items += Item(
                                node, text, desc, editable, hint,
                                bounds.left, bounds.right, bounds.top, bounds.bottom,
                            )
                        }
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                }
                return Screen(items, rootBounds.height().coerceAtLeast(1))
            }
        }
    }

    private companion object {
        const val TAG = "AemxMessageSender"
        const val MAX_NODES = 2_000
        const val POLL_MS = 300L
        const val SETTLE_MS = 800L
        const val SMS_TIMEOUT_MS = 25_000L
        const val VERIFY_MS = 8_000L
        const val FILL_WAIT_MS = 2_000L

        /** 화면 높이 대비 아래쪽 입력란 영역의 경계. */
        const val INPUT_AREA = 0.4

        const val SENT_UNVERIFIED = "보내기를 눌렀지만 보낸 말풍선을 확인하지 못했습니다. 이미 보냈을 수 있어 다시 보내지 않습니다."

        /** dump 확인: 삼성 메시지 보내기 버튼은 ImageButton content-desc "전송"(id send_button). "보내기" 등은 다른 버전 대비 추정. */
        val SMS_SEND_LABELS = listOf("전송", "보내기", "send")
    }
}
