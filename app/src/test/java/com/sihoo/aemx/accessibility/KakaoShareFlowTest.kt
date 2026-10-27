package com.sihoo.aemx.accessibility

import com.sihoo.aemx.accessibility.KakaoShareRules.CheckVerdict
import com.sihoo.aemx.accessibility.KakaoShareRules.RowInfo
import com.sihoo.aemx.accessibility.KakaoShareRules.RowPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 문자열 모양은 실기기 dump 에서 가져왔고, 이름과 말은 지어낸 것이다. */
class KakaoShareFlowTest {
    private val match = KakaoShareRules.NameMatch(MessageRules::sameText)

    /** 검색 결과 행(상태 말 없음). */
    private fun searchRow(name: String, message: String = "안녕", group: Boolean = false, count: String? = null) = RowInfo(
        desc = listOfNotNull(name, count, message).joinToString(", "),
        name = name,
        hasMembersCount = group,
        checked = false,
    )

    /** 전체 목록 행(상태 말 있음). */
    private fun listRow(
        name: String,
        selected: Boolean,
        message: String = "안녕",
        extra: String? = null,
        group: Boolean = false,
    ) = RowInfo(
        desc = listOfNotNull(if (selected) "선택함" else "선택안함", name, extra, message).joinToString(", "),
        name = name,
        hasMembersCount = group,
        checked = selected,
    )

    @Test
    fun `행 설명에서 상태 말을 빼고 쉼표로 나눈다`() {
        assertEquals(listOf("홍길동", "안녕"), KakaoShareRules.pieces("선택안함, 홍길동, 안녕"))
        assertEquals(listOf("홍길동", "안녕"), KakaoShareRules.pieces("홍길동, 안녕"))
        assertEquals(true, KakaoShareRules.selectedByDesc("선택함, 홍길동, 안녕"))
        assertEquals(false, KakaoShareRules.selectedByDesc("선택안함, 홍길동, 안녕"))
        assertNull(KakaoShareRules.selectedByDesc("홍길동, 안녕"))
    }

    @Test
    fun `이름이 정확히 맞는 일대일 행 하나를 고른다`() {
        val rows = listOf(searchRow("김영희"), searchRow("홍길동"))

        assertEquals(RowPick.Found(1), KakaoShareRules.pickRow(rows, "홍길동", match))
        assertEquals(RowPick.Found(1), KakaoShareRules.pickRow(rows, "홍 길동", match))
    }

    @Test
    fun `부분 일치와 마지막 말에 든 이름은 맞는 것으로 보지 않는다`() {
        val rows = listOf(searchRow("홍길동이네", message = "안녕"), searchRow("김영희", message = "홍길동 만났어"))

        assertEquals(RowPick.None, KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    @Test
    fun `나와의 채팅은 둘째 조각이 이름이다`() {
        val me = RowInfo(desc = "나, 홍길동, 파일: a.zip", name = "홍길동", hasMembersCount = false, checked = false)

        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(listOf(me), "홍길동", match))
        assertFalse(KakaoShareRules.isGroup(me))
    }

    @Test
    fun `인원 수가 붙은 행은 단체방이다`() {
        // 인원 수 글자(id members_count)가 있는 행, 이름 다음 조각이 숫자뿐인 행, "N명" 이 든 오픈채팅 행.
        assertTrue(KakaoShareRules.isGroup(searchRow("모임방", group = true)))
        assertTrue(KakaoShareRules.isGroup(listRow("모임방", selected = false, extra = "6")))
        assertTrue(KakaoShareRules.isGroup(listRow("오픈방", selected = false, extra = "오픈채팅 29명")))
        assertFalse(KakaoShareRules.isGroup(listRow("홍길동", selected = false)))
    }

    @Test
    fun `이름이 맞는 행이 단체방뿐이면 고르지 않는다`() {
        val rows = listOf(searchRow("홍길동", group = true, count = "6"))

        assertEquals(RowPick.GroupOnly, KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    @Test
    fun `단체방과 일대일이 같이 있으면 일대일을 고른다`() {
        val rows = listOf(searchRow("홍길동", group = true, count = "6"), searchRow("홍길동"))

        assertEquals(RowPick.Found(1), KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    @Test
    fun `일대일 후보가 둘이면 모호하다`() {
        val rows = listOf(searchRow("홍길동"), searchRow("홍길동"))

        assertEquals(RowPick.Ambiguous(2), KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    @Test
    fun `설명과 이름 글자가 서로 다르면 맞는 행으로 보지 않는다`() {
        val odd = RowInfo(desc = "홍길동, 안녕", name = "김영희", hasMembersCount = false, checked = false)

        assertEquals(RowPick.None, KakaoShareRules.pickRow(listOf(odd), "홍길동", match))
    }

    @Test
    fun `보내기 버튼 글자에서 대상 수를 읽는다`() {
        assertEquals(1, KakaoShareRules.submitCount("1 보내기"))
        assertEquals(2, KakaoShareRules.submitCount("2 보내기"))
        assertNull(KakaoShareRules.submitCount("보내기"))
        assertNull(KakaoShareRules.submitCount(null))
    }

    private fun verify(
        rows: List<RowInfo>,
        chips: List<String> = listOf("홍길동"),
        submit: String? = "1 보내기",
    ) = KakaoShareRules.verifyChecked(rows, chips, submit, "홍길동", match)

    @Test
    fun `하나만 체크되고 이름이 맞으면 보낼 수 있다`() {
        val rows = listOf(listRow("김영희", false), listRow("홍길동", true), listRow("이철수", false))

        assertEquals(CheckVerdict.Ready, verify(rows))
    }

    @Test
    fun `체크된 행이 화면에 안 보여도 칩과 버튼이 맞으면 보낼 수 있다`() {
        // 체크하면 검색어가 지워지고 전체 목록이 다시 떠서, 체크된 행이 화면 밖에 있을 수 있다.
        assertEquals(CheckVerdict.Ready, verify(listOf(listRow("김영희", false))))
        assertEquals(CheckVerdict.Ready, verify(emptyList()))
    }

    @Test
    fun `보이는 체크된 행은 모두 같은 이름이어야 한다`() {
        assertEquals(CheckVerdict.Ready, verify(listOf(listRow("홍길동", true), listRow("홍길동", true))))
        assertTrue(verify(listOf(listRow("홍길동", true), listRow("김영희", true))) is CheckVerdict.Reject)
    }

    private fun move(
        stage: KakaoShareRules.SearchStage?,
        rows: Int = 0,
        since: Long = 0,
        supported: Boolean = true,
    ) = KakaoShareRules.searchMove(stage, rows, since, supported)

    private fun advance(to: KakaoShareRules.SearchStage) = KakaoShareRules.SearchMove.Advance(to)

    @Test
    fun `검색 방법은 열기 붙여넣기 포커스 엔터 순서로 한 번씩 쓴다`() {
        val s = KakaoShareRules.SearchStage.values()
        assertEquals(advance(s[0]), move(null))
        assertEquals(advance(KakaoShareRules.SearchStage.PASTE), move(KakaoShareRules.SearchStage.OPEN, since = 1_600))
        assertEquals(advance(KakaoShareRules.SearchStage.FOCUS), move(KakaoShareRules.SearchStage.PASTE, since = 1_600))
        assertEquals(advance(KakaoShareRules.SearchStage.ENTER), move(KakaoShareRules.SearchStage.FOCUS, since = 1_600))
        assertEquals(KakaoShareRules.SearchMove.GiveUp, move(KakaoShareRules.SearchStage.ENTER, since = 4_500))
    }

    @Test
    fun `방법을 쓴 뒤 지켜보는 동안은 다음으로 넘어가지 않는다`() {
        assertEquals(KakaoShareRules.SearchMove.Wait, move(KakaoShareRules.SearchStage.OPEN, since = 800))
        assertEquals(KakaoShareRules.SearchMove.Wait, move(KakaoShareRules.SearchStage.ENTER, since = 3_000))
    }

    @Test
    fun `결과 행이 보이면 더 시도하지 않는다`() {
        KakaoShareRules.SearchStage.values().forEach { stage ->
            assertEquals(KakaoShareRules.SearchMove.Wait, move(stage, rows = 3, since = 9_000))
        }
    }

    @Test
    fun `엔터를 쓸 수 없는 버전에서는 포커스 다음에 바로 포기한다`() {
        assertEquals(
            KakaoShareRules.SearchMove.GiveUp,
            move(KakaoShareRules.SearchStage.FOCUS, since = 1_600, supported = false),
        )
    }

    private fun scanRow(name: String, message: String = "안녕") = listRow(name, selected = false, message = message)

    @Test
    fun `목록 훑기는 같은 사람이 여러 화면에 보여도 한 행으로 센다`() {
        val scan = KakaoShareRules.ListScan()
        scan.add(listOf(scanRow("홍길동", "가"), scanRow("김영희", "나")))
        scan.add(listOf(scanRow("김영희", "나"), scanRow("이철수", "다")))

        assertEquals(3, scan.rows().size)
        assertEquals(KakaoShareRules.ListScan.State.MORE, scan.state)
        assertEquals(RowPick.Found(0), scan.pick("홍길동", match, null))
    }

    @Test
    fun `직전 화면과 행이 같으면 끝으로 본다`() {
        val scan = KakaoShareRules.ListScan()
        scan.add(listOf(scanRow("홍길동")))
        scan.add(listOf(scanRow("김영희")))
        scan.add(listOf(scanRow("김영희")))

        assertEquals(KakaoShareRules.ListScan.State.END, scan.state)
    }

    @Test
    fun `스크롤이 거절되면 끝으로 본다`() {
        val scan = KakaoShareRules.ListScan()
        scan.add(listOf(scanRow("홍길동")))
        scan.markEnd()

        assertEquals(KakaoShareRules.ListScan.State.END, scan.state)
    }

    @Test
    fun `상한 안에 끝에 못 닿으면 너무 긴 목록이다`() {
        val scan = KakaoShareRules.ListScan(maxScreens = 3)
        repeat(3) { scan.add(listOf(scanRow("사람$it"))) }

        assertEquals(KakaoShareRules.ListScan.State.TOO_LONG, scan.state)
    }

    @Test
    fun `목록 훑기에서 다른 사람이 둘이면 모호하고 단체방뿐이면 고르지 않는다`() {
        val two = KakaoShareRules.ListScan()
        two.add(listOf(scanRow("홍길동", "가")))
        two.add(listOf(scanRow("홍길동", "나")))
        assertEquals(RowPick.Ambiguous(2), two.pick("홍길동", match, null))

        val group = KakaoShareRules.ListScan()
        group.add(listOf(listRow("홍길동", false, extra = "6", group = true)))
        assertEquals(RowPick.GroupOnly, group.pick("홍길동", match, null))
    }

    @Test
    fun `공유 화면이 3초 안에 안 뜨면 한 번만 다시 열고 그래도 안 뜨면 실패한다`() {
        assertEquals(KakaoShareRules.OpenMove.Wait, KakaoShareRules.openMove(2_000, retried = false))
        assertEquals(KakaoShareRules.OpenMove.Retry, KakaoShareRules.openMove(3_100, retried = false))
        assertEquals(KakaoShareRules.OpenMove.Wait, KakaoShareRules.openMove(-500, retried = true))
        assertEquals(KakaoShareRules.OpenMove.Wait, KakaoShareRules.openMove(2_900, retried = true))
        assertEquals(KakaoShareRules.OpenMove.Fail, KakaoShareRules.openMove(3_100, retried = true))
    }

    private fun mayRetry(phase: KakaoShareFlow.Phase, used: Int = 0, remaining: Long = 60_000) =
        KakaoShareRules.mayRetry(phase, used, remaining)

    @Test
    fun `보내기 전 단계에서는 한 번 다시 할 수 있다`() {
        KakaoShareFlow.Phase.values().filter { it != KakaoShareFlow.Phase.SENT }.forEach { phase ->
            assertTrue("$phase", mayRetry(phase))
        }
    }

    @Test
    fun `보내기를 누른 뒤에는 절대 다시 하지 않는다`() {
        assertFalse(mayRetry(KakaoShareFlow.Phase.SENT))
        assertFalse(mayRetry(KakaoShareFlow.Phase.SENT, remaining = 1_000_000))
    }

    @Test
    fun `다시 하는 것은 한 번뿐이고 시간이 모자라면 하지 않는다`() {
        assertFalse(mayRetry(KakaoShareFlow.Phase.CHECK, used = 1))
        assertFalse(mayRetry(KakaoShareFlow.Phase.CHECK, remaining = 5_000))
        assertTrue(mayRetry(KakaoShareFlow.Phase.CHECK, remaining = 20_000))
    }

    @Test
    fun `공유 시트는 1_5초 넘게 안 보여야 사라진 것으로 본다`() {
        assertFalse(KakaoShareRules.sheetGone(0))
        assertFalse(KakaoShareRules.sheetGone(1_400))
        assertTrue(KakaoShareRules.sheetGone(1_500))
    }

    @Test
    fun `체크된 행의 이름이 다르면 보내지 않는다`() {
        assertTrue(verify(listOf(listRow("김영희", true))) is CheckVerdict.Reject)
    }

    @Test
    fun `대상 칩이나 보내기 개수가 맞지 않으면 보내지 않는다`() {
        val rows = listOf(listRow("홍길동", true))

        assertTrue(verify(rows, chips = listOf("홍길동", "김영희"), submit = "2 보내기") is CheckVerdict.Reject)
        assertTrue(verify(rows, chips = listOf("김영희")) is CheckVerdict.Reject)
        assertTrue(verify(rows, chips = emptyList()) is CheckVerdict.Reject)
        assertTrue(verify(rows, submit = "2 보내기") is CheckVerdict.Reject)
        assertTrue(verify(rows, submit = null) is CheckVerdict.Reject)
    }

    @Test
    fun `체크 표시가 서로 맞지 않으면 보내지 않는다`() {
        val inconsistent = RowInfo(desc = "선택함, 홍길동, 안녕", name = "홍길동", hasMembersCount = false, checked = false)

        assertTrue(verify(listOf(inconsistent)) is CheckVerdict.Reject)
    }

    @Test
    fun `체크된 행이 단체방이면 보내지 않는다`() {
        val rows = listOf(listRow("홍길동", true, extra = "6", group = true))

        assertTrue(verify(rows) is CheckVerdict.Reject)
    }

    @Test
    fun `넓힌 이름 판정을 넘기면 그것으로 비교한다`() {
        val wide = KakaoShareRules.NameMatch { wanted, shown -> shown.endsWith(wanted) }
        val rows = listOf(searchRow("21105홍길동"))

        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(rows, "홍길동", wide))
        assertEquals(RowPick.None, KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    private val loose = KakaoShareRules.NameMatch { wanted, shown -> MessageRules.looselyNamed(shown, wanted) }

    @Test
    fun `정확히 같은 이름이 없을 때만 느슨한 이름으로 찾는다`() {
        val rows = listOf(searchRow("21105홍길동"), searchRow("홍길동엄마"), searchRow("김철수"))
        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(rows, "홍길동", match, loose))
        // 느슨한 판정을 안 주면 찾지 않는다.
        assertEquals(RowPick.None, KakaoShareRules.pickRow(rows, "홍길동", match))
    }

    @Test
    fun `정확히 같은 행이 있으면 느슨한 후보는 보지 않는다`() {
        val rows = listOf(searchRow("21105홍길동"), searchRow("홍길동"))
        assertEquals(RowPick.Found(1), KakaoShareRules.pickRow(rows, "홍길동", match, loose))
        // 정확한 행이 단체방뿐이어도 느슨한 사람으로 넘어가지 않는다.
        val groupOnly = listOf(searchRow("21105홍길동"), searchRow("홍길동", group = true))
        assertEquals(RowPick.GroupOnly, KakaoShareRules.pickRow(groupOnly, "홍길동", match, loose))
    }

    @Test
    fun `느슨하게 맞은 행이 둘이면 모호하다`() {
        val rows = listOf(searchRow("21105홍길동"), searchRow("홍길동 010"))
        assertEquals(RowPick.Ambiguous(2), KakaoShareRules.pickRow(rows, "홍길동", match, loose))
    }

    @Test
    fun `느슨하게 찾은 행은 실제 이름으로 보내기 전 확인을 통과한다`() {
        val rows = listOf(listRow("21105홍길동", selected = true))
        val shown = KakaoShareRules.shownName(rows.single())
        assertEquals("21105홍길동", shown)
        // 말한 이름("홍길동")으로는 막히고, 누른 행의 이름으로는 통과한다.
        assertTrue(
            KakaoShareRules.verifyChecked(rows, listOf("21105홍길동"), "1 보내기", "홍길동", match) is CheckVerdict.Reject,
        )
        assertEquals(
            CheckVerdict.Ready,
            KakaoShareRules.verifyChecked(rows, listOf("21105홍길동"), "1 보내기", shown!!, match),
        )
    }

    @Test
    fun `행의 실제 이름은 id name 을 먼저 보고 없으면 content-desc 의 이름 조각이다`() {
        assertEquals("홍길동", KakaoShareRules.shownName(searchRow("홍길동")))
        assertEquals("홍길동", KakaoShareRules.shownName(RowInfo("홍길동, 안녕", null, false, false)))
        assertEquals("홍길동", KakaoShareRules.shownName(RowInfo("나, 홍길동, 안녕", null, false, false)))
        assertNull(KakaoShareRules.shownName(RowInfo(null, null, false, false)))
    }

    @Test
    fun `말한 이름으로 하나도 안 맞을 때만 숫자를 바꾼 이름으로 찾는다`() {
        val rows = listOf(searchRow("21105홍길동"), searchRow("김철수"))
        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(rows, "이일일공오홍길동", match, loose, "21105홍길동"))
        assertEquals(RowPick.None, KakaoShareRules.pickRow(rows, "이일일공오홍길동", match, loose))
        // 말한 이름 그대로 맞는 행이 있으면 바꾼 이름은 보지 않는다.
        val spoken = listOf(searchRow("이일일공오홍길동"), searchRow("21105홍길동"))
        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(spoken, "이일일공오홍길동", match, loose, "21105홍길동"))
    }

    @Test
    fun `숫자를 바꿔 찾은 행이 둘이면 모호하다`() {
        val rows = listOf(searchRow("21105홍길동"), searchRow("21105 홍길동"))
        assertEquals(RowPick.Ambiguous(2), KakaoShareRules.pickRow(rows, "이일일공오홍길동", match, loose, "21105홍길동"))
    }

    @Test
    fun `행 이름은 정확 일치가 없을 때만 ㅐ ㅔ 를 통일해 비교한다`() {
        val rows = listOf(searchRow("배재준"), searchRow("김철수"))
        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(rows, "배제준", match, loose))
        // 정확히 같은 행이 있으면 그것만 본다.
        val both = listOf(searchRow("배재준"), searchRow("배제준"))
        assertEquals(RowPick.Found(1), KakaoShareRules.pickRow(both, "배제준", match, loose))
    }

    @Test
    fun `통일로 찾은 행이 둘이면 모호하고 다른 자음이나 모음은 찾지 않는다`() {
        val two = listOf(searchRow("배재준"), searchRow("배재 준", message = "안녕하세요"))
        assertEquals(RowPick.Ambiguous(2), KakaoShareRules.pickRow(two, "배제준", match, loose))
        assertEquals(RowPick.None, KakaoShareRules.pickRow(listOf(searchRow("패재준"), searchRow("배저준")), "배제준", match, loose))
    }

    @Test
    fun `통일로 찾은 행이 단체방뿐이면 단체방만 있다고 한다`() {
        val rows = listOf(searchRow("배재준", group = true, count = "5"))
        assertEquals(RowPick.GroupOnly, KakaoShareRules.pickRow(rows, "배제준", match, loose))
    }

    @Test
    fun `숫자를 바꾼 이름과 통일을 함께 써서 찾는다`() {
        val rows = listOf(searchRow("21105배재준"))
        assertEquals(RowPick.Found(0), KakaoShareRules.pickRow(rows, "이일일공오배제준", match, loose, "21105배제준"))
    }

    @Test
    fun `전체 목록 훑기에서도 통일로 찾는다`() {
        val scan = KakaoShareRules.ListScan()
        scan.add(listOf(listRow("김철수", selected = false), listRow("배재준", selected = false)))
        scan.markEnd()
        assertEquals(RowPick.Found(1), scan.pick("배제준", match, loose))
    }
}
