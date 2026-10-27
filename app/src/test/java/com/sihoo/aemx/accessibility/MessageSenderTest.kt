package com.sihoo.aemx.accessibility

import com.sihoo.aemx.accessibility.MessageRules.ContactPick
import com.sihoo.aemx.accessibility.MessageRules.ContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderTest {
    private fun row(id: Long, name: String, number: String, type: Int = MessageRules.TYPE_MOBILE) =
        ContactRow(id, name, number, type)

    @Test
    fun `parses send_message for kakaotalk and sms`() {
        val kakao = ActionPlanParser.parse(
            """{"app":"카카오톡","steps":[{"action":"send_message","target":"엄마","value":"갈게"}]}"""
        )
        assertEquals(ActionType.SEND_MESSAGE, kakao.steps.single().action)
        assertEquals("엄마", kakao.steps.single().target)
        assertEquals("갈게", kakao.steps.single().value)

        val sms = ActionPlanParser.parse(
            """{"app":"메시지","steps":[{"action":"send_message","target":"김철수","value":"잘 지내?"}]}"""
        )
        assertEquals("잘 지내?", sms.steps.single().value)
    }

    @Test
    fun `rejects send_message without recipient content or on other apps`() {
        listOf(
            """{"app":"카카오톡","steps":[{"action":"send_message","value":"안녕"}]}""",
            """{"app":"카카오톡","steps":[{"action":"send_message","target":"엄마"}]}""",
            """{"app":"카카오톡","steps":[{"action":"send_message","target":"엄마","value":"  "}]}""",
            """{"app":"네이버","steps":[{"action":"send_message","target":"엄마","value":"안녕"}]}""",
        ).forEach { json ->
            assertThrows(ActionPlanParseException::class.java) { ActionPlanParser.parse(json) }
        }
    }

    @Test
    fun `channel follows the fixed app table`() {
        assertEquals(MessageChannel.KAKAO, MessageRules.channelFor("카톡"))
        assertEquals(MessageChannel.SMS, MessageRules.channelFor("문자"))
        assertEquals(MessageChannel.SMS, MessageRules.channelFor("메시지"))
        assertNull(MessageRules.channelFor("네이버"))
        assertNull(MessageRules.channelFor("모르는앱"))
    }

    @Test
    fun `contact must match the name exactly`() {
        val rows = listOf(row(1, "김철수", "010-1234-5678"), row(2, "김철수민", "010-9999-0000"))
        assertEquals(
            ContactPick.Found("김철수", "01012345678"),
            MessageRules.pickContact(rows, "김철수"),
        )
        // 부분 일치로 보내지 않는다.
        assertEquals(ContactPick.Missing, MessageRules.pickContact(rows, "철수"))
        assertEquals(ContactPick.Missing, MessageRules.pickContact(rows, "이영희"))
    }

    @Test
    fun `spacing and case in the spoken name do not matter`() {
        val rows = listOf(row(1, "김철수", "010-1234-5678"))
        assertTrue(MessageRules.pickContact(rows, "김 철수") is ContactPick.Found)
    }

    @Test
    fun `two people with the same name are ambiguous`() {
        val rows = listOf(row(1, "김철수", "010-1111-2222"), row(2, "김철수", "010-3333-4444"))
        assertTrue(MessageRules.pickContact(rows, "김철수") is ContactPick.Ambiguous)
    }

    @Test
    fun `duplicate contacts with the same number count as one person`() {
        val rows = listOf(row(1, "김철수", "010-1111-2222"), row(2, "김철수", "+82 10-1111-2222"))
        assertEquals(
            ContactPick.Found("김철수", "01011112222"),
            MessageRules.pickContact(rows, "김철수"),
        )
    }

    @Test
    fun `one contact with several numbers needs exactly one mobile`() {
        val oneMobile = listOf(
            row(1, "엄마", "02-123-4567", type = 1),
            row(1, "엄마", "010-1111-2222", type = MessageRules.TYPE_MOBILE),
        )
        assertEquals(ContactPick.Found("엄마", "01011112222"), MessageRules.pickContact(oneMobile, "엄마"))

        val twoMobiles = listOf(
            row(1, "엄마", "010-1111-2222"),
            row(1, "엄마", "010-3333-4444"),
        )
        assertTrue(MessageRules.pickContact(twoMobiles, "엄마") is ContactPick.Ambiguous)

        val noMobile = listOf(
            row(1, "엄마", "02-123-4567", type = 1),
            row(1, "엄마", "02-765-4321", type = 3),
        )
        assertTrue(MessageRules.pickContact(noMobile, "엄마") is ContactPick.Ambiguous)
    }

    @Test
    fun `contact without a number is not sendable`() {
        assertTrue(MessageRules.pickContact(listOf(row(1, "엄마", "")), "엄마") is ContactPick.Ambiguous)
    }

    @Test
    fun `number normalization turns plus 82 into the local form`() {
        assertEquals("01012345678", MessageRules.normalizeNumber("+82 10-1234-5678"))
        assertEquals("01012345678", MessageRules.normalizeNumber("010 1234 5678"))
        assertEquals("021234567", MessageRules.normalizeNumber("02-123-4567"))
    }

    @Test
    fun `recipient is recognized by name or number tail on screen`() {
        assertTrue(MessageRules.mentionsRecipient(listOf("받는 사람", "김철수"), "김철수", "01012345678"))
        assertTrue(MessageRules.mentionsRecipient(listOf("010-1234-5678"), "김철수", "01012345678"))
        assertTrue(MessageRules.mentionsRecipient(listOf("+82 10 1234 5678"), "김철수", "01012345678"))
        assertFalse(MessageRules.mentionsRecipient(listOf("010-9999-0000", "이영희"), "김철수", "01012345678"))
        assertFalse(MessageRules.mentionsRecipient(emptyList(), "김철수", "01012345678"))
    }

    @Test
    fun `sameText ignores spacing but never matches empty or null`() {
        assertTrue(MessageRules.sameText("잘 지내?", "잘지내?"))
        assertFalse(MessageRules.sameText("", ""))
        assertFalse(MessageRules.sameText("갈게", null))
        assertFalse(MessageRules.sameText("갈게", "갈게요"))
    }

    // 아래는 실제 화면 dump 에서 뽑은 값이다.

    @Test
    fun `dump - 검색 화면의 작은 숨은 입력란은 입력란으로 치지 않는다`() {
        // 진짜 입력란 [90,179][1350,284] 와 input_focus [0,127][38,299]
        val real = 1350 - 90
        val hidden = 38 - 0
        assertTrue(real >= MessageRules.MIN_INPUT_WIDTH)
        assertFalse(hidden >= MessageRules.MIN_INPUT_WIDTH)
    }

    @Test
    fun `dump - 문자 작성 화면은 받는 사람을 번호로 보여 준다`() {
        // composer_title: text 와 content-desc 가 모두 "010-0000-0000"
        assertTrue(MessageRules.mentionsRecipient(listOf("010-0000-0000", "153/160"), "누구", "01000000000"))
        assertFalse(MessageRules.mentionsRecipient(listOf("010-0000-0000"), "누구", "01011112222"))
    }

    @Test
    fun `dump - 문자 본문 sms_body 는 입력란 글자와 정확히 같아야 한다`() {
        assertTrue(MessageRules.sameText("test123", "test123"))
        assertFalse(MessageRules.sameText("test123", "test1234"))
    }

    @Test
    fun `보낸 말풍선은 글자가 같거나 내용 뒤에 쉼표로 덧붙은 설명이다`() {
        assertTrue(MessageRules.isBubbleOf("곧 갈게", "곧 갈게", null))
        assertTrue(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게"))
        assertTrue(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게, 오후 3:12"))
        // 다른 말, 앞부분만 같은 말, 내용이 뒤에 있는 설명은 아니다.
        assertFalse(MessageRules.isBubbleOf("곧 갈게", "곧 갈게요", null))
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게요, 오후 3:12"))
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "오후 3:12, 곧 갈게"))
        // 링크 미리보기 같은 기존 글자와 섞이지 않는다.
        assertFalse(MessageRules.isBubbleOf("곧 갈게", "www.example.com", "공유"))
    }

    @Test
    fun `숫자 기호 이모지만 덧붙은 이름은 같은 사람이다`() {
        listOf("21105배재준", "배재준 010", "배재준😊", "배재준 (2)", "배재준-1").forEach {
            assertTrue(it, MessageRules.looselyNamed(it, "배재준"))
        }
    }

    @Test
    fun `문자가 덧붙은 이름은 다른 사람이다`() {
        listOf("배재준엄마", "배재준형", "김배재준", "배재준ABC", "배재준배재준").forEach {
            assertFalse(it, MessageRules.looselyNamed(it, "배재준"))
        }
    }

    @Test
    fun `한 글자 이름은 느슨하게 맞추지 않는다`() {
        assertFalse(MessageRules.looselyNamed("1엄", "엄"))
        val rows = listOf(row(1, "21105엄", "010-1111-2222"))
        assertEquals(ContactPick.Missing, MessageRules.pickContact(rows, "엄"))
    }

    @Test
    fun `연락처 이름에 학번이 붙어 있어도 하나뿐이면 찾는다`() {
        val rows = listOf(
            row(1, "21105배재준", "010-1234-5678"),
            row(2, "배재준엄마", "010-9999-0000"),
            row(3, "김철수", "010-1111-2222"),
        )
        assertEquals(
            ContactPick.Found("21105배재준", "01012345678"),
            MessageRules.pickContact(rows, "배재준"),
        )
    }

    @Test
    fun `정확히 같은 이름이 있으면 느슨한 후보는 보지 않는다`() {
        val rows = listOf(
            row(1, "배재준", "010-1234-5678"),
            row(2, "21105배재준", "010-9999-0000"),
        )
        assertEquals(
            ContactPick.Found("배재준", "01012345678"),
            MessageRules.pickContact(rows, "배재준"),
        )
    }

    @Test
    fun `느슨하게 맞은 후보가 둘이면 사용자에게 읽어 줄 문구로 실패한다`() {
        val rows = listOf(
            row(1, "21105배재준", "010-1234-5678"),
            row(2, "배재준 010", "010-9999-0000"),
        )
        val pick = MessageRules.pickContact(rows, "배재준")
        assertEquals(
            ContactPick.Ambiguous("배재준 이라는 연락처가 2명이라 보내지 않았어요. 더 정확한 이름으로 말해 주세요."),
            pick,
        )
    }

    @Test
    fun `이름이 같은 두 사람도 같은 문구로 실패한다`() {
        val rows = listOf(row(1, "김철수", "010-1111-2222"), row(2, "김철수", "010-3333-4444"))
        assertEquals(
            ContactPick.Ambiguous(MessageRules.tooManyMessage("김철수", 2)),
            MessageRules.pickContact(rows, "김철수"),
        )
    }

    @Test
    fun `느슨하게 맞은 두 연락처의 번호가 같으면 한 사람이다`() {
        val rows = listOf(
            row(1, "21105배재준", "010-1234-5678"),
            row(2, "배재준😊", "+82 10-1234-5678"),
        )
        assertTrue(MessageRules.pickContact(rows, "배재준") is ContactPick.Found)
    }

    @Test
    fun `dump - 삼성 메시지 말풍선은 내용 공백 시각 상태 모양의 content-desc 로 보인다`() {
        assertTrue(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게 오후 5:55 수신인이 읽지 않음 "))
        assertTrue(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게 오전 9:05 읽음"))
        assertTrue(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게 17:55"))
        assertTrue(MessageRules.isBubbleOf("ㅗㅗ", null, "ㅗㅗ 오후 5:56 수신인이 읽지 않음 "))
        // 글자 노드를 볼 수 있는 기기에서는 text 가 같다.
        assertTrue(MessageRules.isBubbleOf("곧 갈게", "곧 갈게", null))
    }

    @Test
    fun `내용 뒤에 시각이 아닌 글이 붙은 설명은 말풍선이 아니다`() {
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게요 오후 5:55 수신인이 읽지 않음"))
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "곧 갈게 내일 보자 오후 5:55"))
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "오후 5:55 곧 갈게"))
        assertFalse(MessageRules.isBubbleOf("곧 갈게", null, "메시지 입력"))
    }

    @Test
    fun `한 자리씩 읽은 숫자를 아라비아 숫자로 바꾼다`() {
        assertEquals("21105배재준", MessageRules.digitizeName("이일일공오배재준"))
        assertEquals("21105 배재준", MessageRules.digitizeName("이일일공오 배재준"))
        assertEquals("100배재준", MessageRules.digitizeName("일영영배재준"))
        assertEquals("0910", MessageRules.digitizeName("공구일공"))
    }

    @Test
    fun `자릿수로 읽은 숫자를 아라비아 숫자로 바꾼다`() {
        assertEquals("21105 배재준", MessageRules.digitizeName("이만천백오 배재준"))
        assertEquals("21배재준", MessageRules.digitizeName("이십일배재준"))
        assertEquals("천 배재준", MessageRules.digitizeName("천 배재준"))
        assertEquals("1000 배재준", MessageRules.digitizeName("일천 배재준"))
        assertEquals("10000 배재준", MessageRules.digitizeName("일만 배재준"))
        assertEquals("105 배재준", MessageRules.digitizeName("백오 배재준"))
    }

    @Test
    fun `숫자 글자가 하나뿐이면 바꾸지 않는다`() {
        assertEquals("이민수", MessageRules.digitizeName("이민수"))
        assertEquals("오 이민수", MessageRules.digitizeName("오 이민수"))
        assertEquals("배재준", MessageRules.digitizeName("배재준"))
        assertEquals("김구", MessageRules.digitizeName("김구"))
    }

    @Test
    fun `이름과 숫자가 섞여도 숫자 덩어리만 바꾼다`() {
        assertEquals("이민수21105", MessageRules.digitizeName("이민수이일일공오"))
        assertEquals("오호 이민수", MessageRules.digitizeName("오호 이민수"))
        assertEquals("21105 배재준 12", MessageRules.digitizeName("이일일공오 배재준 일이"))
    }

    @Test
    fun `읽을 수 없는 자릿수 덩어리는 그대로 둔다`() {
        assertEquals("일이만", MessageRules.digitizeName("일이만"))
        assertEquals("백십천", MessageRules.digitizeName("백십천"))
        assertEquals("만만", MessageRules.digitizeName("만만"))
    }

    @Test
    fun `연락처는 말한 이름이 안 맞을 때만 숫자를 바꿔 찾는다`() {
        val rows = listOf(
            row(1, "21105배재준", "010-1234-5678"),
            row(2, "배재준엄마", "010-9999-0000"),
        )
        assertEquals(
            ContactPick.Found("21105배재준", "01012345678"),
            MessageRules.pickContact(rows, "이일일공오배재준"),
        )
        assertEquals(
            ContactPick.Found("21105배재준", "01012345678"),
            MessageRules.pickContact(rows, "이만천백오 배재준"),
        )
        // 말한 이름 그대로 맞는 후보가 있으면 숫자를 바꾸지 않는다.
        val spokenStored = listOf(
            row(1, "이일일공오배재준", "010-1111-2222"),
            row(2, "21105배재준", "010-3333-4444"),
        )
        assertEquals(
            ContactPick.Found("이일일공오배재준", "01011112222"),
            MessageRules.pickContact(spokenStored, "이일일공오배재준"),
        )
    }

    @Test
    fun `성이 이인 사람은 숫자로 바뀌지 않아 그대로 찾는다`() {
        val rows = listOf(row(1, "이민수", "010-1111-2222"), row(2, "21105배재준", "010-3333-4444"))
        assertEquals(ContactPick.Found("이민수", "01011112222"), MessageRules.pickContact(rows, "이민수"))
    }

    @Test
    fun `숫자를 바꿔 찾았는데 후보가 둘이면 모호하다`() {
        val rows = listOf(
            row(1, "21105배재준", "010-1234-5678"),
            row(2, "21105 배재준", "010-9999-0000"),
        )
        assertEquals(
            ContactPick.Ambiguous(MessageRules.tooManyMessage("이일일공오배재준", 2)),
            MessageRules.pickContact(rows, "이일일공오배재준"),
        )
    }

    @Test
    fun `ㅔ 를 ㅐ 로 ㅖ 를 ㅒ 로 통일한다`() {
        assertEquals("배재준", MessageRules.foldVowels("배제준"))
        assertEquals("배재준", MessageRules.foldVowels("배재준"))
        assertEquals("얘", MessageRules.foldVowels("예"))
        assertEquals("얘기", MessageRules.foldVowels("예기"))
        // 받침이 있는 음절도 받침을 지키고 중성만 바꾼다.
        assertEquals("갠", MessageRules.foldVowels("겐"))
    }

    @Test
    fun `통일은 다른 모음과 자음을 넓히지 않는다`() {
        // ㅂ/ㅍ, ㅓ/ㅗ, ㅏ/ㅓ, ㅣ/ㅔ 는 그대로 둔다.
        assertEquals("패재준", MessageRules.foldVowels("패제준"))
        assertEquals("배저준", MessageRules.foldVowels("배저준"))
        assertEquals("배조준", MessageRules.foldVowels("배조준"))
        assertEquals("바자준", MessageRules.foldVowels("바자준"))
        assertEquals("비지준", MessageRules.foldVowels("비지준"))
        // 한글이 아닌 글자와 숫자는 그대로다.
        assertEquals("Abc 123 ㅔ", MessageRules.foldVowels("Abc 123 ㅔ"))
    }

    @Test
    fun `변형 이름은 ㅐ ㅔ 를 서로 바꾸고 원래 이름은 뺀다`() {
        // "배" 도 ㅐ 라서 바꿀 수 있는 곳이 둘이다: 오른쪽 하나, 왼쪽 하나, 둘 다.
        assertEquals(listOf("배재준", "베제준", "베재준"), MessageRules.vowelVariants("배제준"))
        assertEquals(listOf("배제준", "베재준", "베제준"), MessageRules.vowelVariants("배재준"))
        assertEquals(listOf("얘"), MessageRules.vowelVariants("예"))
        assertEquals(listOf("김철수").filter { false }, MessageRules.vowelVariants("김철수"))
        assertEquals(emptyList<String>(), MessageRules.vowelVariants("김철수"))
    }

    @Test
    fun `변형 이름은 한 군데씩, 모두 바꾼 것, 나머지 조합 순으로 최대 네 개다`() {
        // 두 군데: 하나씩 둘, 모두 바꾼 것 하나.
        assertEquals(listOf("제재", "재제", "재재"), MessageRules.vowelVariants("제제"))
        val three = MessageRules.vowelVariants("제제제")
        assertEquals(4, three.size)
        assertEquals(listOf("제제재", "제재제", "재제제", "재재재"), three)
        assertFalse(three.contains("제제제"))
    }

    @Test
    fun `연락처는 정확 일치와 느슨 일치가 통일보다 먼저다`() {
        val rows = listOf(row(1, "배제준", "01011112222"), row(2, "배재준", "01033334444"))
        // 말한 이름과 정확히 같은 연락처가 있으면 통일 비교를 보지 않는다.
        assertEquals(ContactPick.Found("배제준", "01011112222"), MessageRules.pickContact(rows, "배제준"))
        assertEquals(ContactPick.Found("배재준", "01033334444"), MessageRules.pickContact(rows, "배재준"))
        // 느슨 일치(숫자가 붙은 이름)도 통일보다 먼저다.
        val loose = listOf(row(1, "21105배제준", "01011112222"), row(2, "배재준", "01033334444"))
        assertEquals(ContactPick.Found("21105배제준", "01011112222"), MessageRules.pickContact(loose, "배제준"))
    }

    @Test
    fun `정확 느슨 숫자 정리가 모두 실패했을 때만 통일 비교로 찾는다`() {
        val rows = listOf(row(1, "배재준", "01033334444"), row(2, "김철수", "01055556666"))
        assertEquals(ContactPick.Found("배재준", "01033334444"), MessageRules.pickContact(rows, "배제준"))
        // 숫자를 바꾼 이름과 통일을 함께 쓴다.
        val numbered = listOf(row(3, "21105배재준", "01077778888"))
        assertEquals(ContactPick.Found("21105배재준", "01077778888"), MessageRules.pickContact(numbered, "이일일공오배제준"))
    }

    @Test
    fun `통일로 찾은 후보가 둘이면 보내지 않는다`() {
        val rows = listOf(row(1, "배재준", "01011112222"), row(2, "배제준", "01033334444"))
        // 말한 이름이 둘 중 하나와 정확히 같으면 그 사람이다. 정확 일치가 없는 이름은 통일로 둘 다 걸려 모호하다.
        val both = listOf(row(1, "배재준", "01011112222"), row(2, "배재 준", "01033334444"))
        assertTrue(MessageRules.pickContact(both, "배제준") is ContactPick.Ambiguous)
        assertEquals(ContactPick.Found("배제준", "01033334444"), MessageRules.pickContact(rows, "배제준"))
    }

    @Test
    fun `통일은 다른 자음이나 모음의 연락처를 찾지 않는다`() {
        val rows = listOf(row(1, "패재준", "01011112222"), row(2, "배저준", "01033334444"), row(3, "배조준", "01055556666"))
        assertEquals(ContactPick.Missing, MessageRules.pickContact(rows, "배제준"))
    }
}
