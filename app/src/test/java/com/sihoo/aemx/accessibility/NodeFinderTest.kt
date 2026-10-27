package com.sihoo.aemx.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NodeFinderTest {

    private fun node(
        label: String? = null,
        clickable: Boolean = false,
        description: String? = null,
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        text = label
        contentDescription = description
        isEnabled = true
        isVisibleToUser = true
        isClickable = clickable
    }

    /** 실기기의 영상 항목 모양. 누를 수 있고 content-desc 가 "동영상 재생"으로 끝난다. */
    private fun videoItem(title: String, shorts: Boolean = false) = node(
        clickable = true,
        description = if (shorts) "$title - Shorts 동영상 재생" else "$title - 3분 12초 - 동영상 재생",
    )

    private fun AccessibilityNodeInfo.with(vararg children: AccessibilityNodeInfo) = apply {
        children.forEach { shadowOf(this).addChild(it) }
    }

    /**
     * 실기기에서 "노트북 추천"을 검색했을 때의 광고 모양. 광고 표시는 항목이 아니라
     * 항목을 감싸는 컨테이너에 붙는다.
     */
    private fun sponsoredBlock(item: AccessibilityNodeInfo) = node(
        description = "스폰서 - 지금 놓치면 후회 | 지금까지 이런 혜택은 없었다 | Temu",
    ).with(item)

    @Test
    fun `첫 영상으로 광고를 고르지 않는다`() {
        val advert = sponsoredBlock(videoItem("노트북 특가 모음"))
        val video = videoItem("귀여운 고양이 모음")
        val root = node().with(advert, video)

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("귀여운 고양이 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `광고뿐이면 아무것도 고르지 않는다`() {
        val root = node().with(sponsoredBlock(videoItem("노트북 특가 모음")))

        assertNull(NodeFinder.findFirstPlayable(root))
    }

    @Test
    fun `제목에 광고가 들어간 일반 영상은 거르지 않는다`() {
        // 이슈 #13. 광고 판정이 항목 자신의 제목까지 훑어서, 멀쩡한 영상이 걸러졌다.
        val video = videoItem("추억의 TV 광고 모음")
        val root = node().with(video)

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("추억의 TV 광고 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `항목 안에 광고 배지가 있으면 건너뛴다`() {
        // 이슈 #13 제안: 항목 컨테이너 안의 짧은 배지 텍스트만 본다. 제목은 보지 않는다.
        val advert = videoItem("노트북 특가 모음").with(node("광고"))
        val video = videoItem("귀여운 고양이 모음")
        val root = node().with(advert, video)

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("귀여운 고양이 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `제목에 shorts가 들어가도 일반 영상으로 본다`() {
        // 같은 이슈. Shorts 판정도 제목이 아니라 꼬리말로 해야 한다.
        val root = node().with(
            videoItem("고양이 짤", shorts = true),
            videoItem("shorts 찍는 법"),
        )

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("shorts 찍는 법 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    private fun withId(id: String, label: String? = null) = node(label).apply {
        viewIdResourceName = "com.google.android.apps.youtube.music:id/$id"
    }

    /** 실기기 dump 의 노래 행: clickable 은 없고 id 가 title/subtitle 인 글자가 있다. */
    private fun songRow(title: String? = "시간의 바깥") = node().with(
        node().with(
            *listOfNotNull(
                title?.let { withId("title", it) },
                withId("subtitle", "아이유(IU) • 5:01 • 6260만회 재생"),
            ).toTypedArray(),
        ),
    )

    /**
     * 칩: 글자 Button -> chip_contents -> FrameLayout(content-desc). 실기기에서 선택 전은
     * "노래 검색결과 표시", 선택 뒤는 "노래 검색결과 표시가 선택됨" 이고 selected/checked 는 둘 다 false 다.
     */
    private fun songChip(selected: Boolean) = node(
        description = if (selected) "노래 검색결과 표시가 선택됨" else "노래 검색결과 표시",
    ).with(node().with(withId("chip_cloud_chip_text", "노래")))

    private fun musicRoot(chip: AccessibilityNodeInfo, vararg rows: AccessibilityNodeInfo) =
        node().with(chip, withId("results_list").with(*rows))

    @Test
    fun `유튜브 뮤직은 노래 칩이 선택됐을 때 글자가 있는 첫 행을 고른다`() {
        val header = node().with(node())
        val first = songRow()
        val root = musicRoot(songChip(selected = true), header, first, songRow("에필로그"))

        assertEquals(first, NodeFinder.findFirstSong(root))
    }

    @Test
    fun `유튜브 뮤직은 노래 칩이 선택되지 않았으면 null 이다`() {
        val root = musicRoot(songChip(selected = false), songRow())

        assertNull(NodeFinder.findFirstSong(root))
    }

    @Test
    fun `유튜브 뮤직은 제목이 비어 있는 행은 건너뛴다`() {
        val real = songRow()
        val root = musicRoot(songChip(selected = true), songRow(title = null), real)

        assertEquals(real, NodeFinder.findFirstSong(root))
    }

    @Test
    fun `유튜브 뮤직은 노래 칩이나 목록이 없으면 null 이다`() {
        assertNull(NodeFinder.findFirstSong(node().with(withId("results_list").with(songRow()))))
        assertNull(NodeFinder.findFirstSong(node().with(songChip(selected = true))))
    }

    @Test
    fun `칩 선택은 부모 content-desc 끝의 선택됨으로 판정한다`() {
        val selected = songChip(selected = true)
        val unselected = songChip(selected = false)

        assertEquals(true, NodeFinder.isSelectedChip(NodeFinder.findSongFilterChip(node().with(selected))!!))
        assertEquals(false, NodeFinder.isSelectedChip(NodeFinder.findSongFilterChip(node().with(unselected))!!))
    }

    @Test
    fun `노래 필터 칩은 글자가 정확히 노래인 칩 하나만 찾는다`() {
        val chip = withId("chip_cloud_chip_text", "노래")
        val other = withId("chip_cloud_chip_text", "앨범")
        val root = node().with(other, chip)

        assertEquals(chip, NodeFinder.findSongFilterChip(root))
        assertNull(NodeFinder.findSongFilterChip(node().with(other)))
    }

    @Test
    fun `N번째 영상은 광고를 빼고 센다`() {
        val advert = sponsoredBlock(videoItem("노트북 특가 모음"))
        val root = node().with(videoItem("첫 영상"), advert, videoItem("둘째 영상"), videoItem("셋째 영상"))

        assertEquals("둘째 영상 - 3분 12초 - 동영상 재생", NodeFinder.findNthPlayable(root, 2)?.contentDescription)
        assertEquals("셋째 영상 - 3분 12초 - 동영상 재생", NodeFinder.findNthPlayable(root, 3)?.contentDescription)
        assertNull(NodeFinder.findNthPlayable(root, 4))
    }

    @Test
    fun `N번째 영상은 Shorts 를 일반 영상이 있는 동안 세지 않는다`() {
        val root = node().with(videoItem("쇼츠 하나", shorts = true), videoItem("첫 영상"), videoItem("둘째 영상"))

        assertEquals("둘째 영상 - 3분 12초 - 동영상 재생", NodeFinder.findNthPlayable(root, 2)?.contentDescription)
    }

    @Test
    fun `N번째 노래는 칩이 선택된 목록의 글자 있는 행만 센다`() {
        val first = songRow("첫 곡")
        val blank = songRow(null)
        val second = songRow("둘째 곡")
        val root = musicRoot(songChip(selected = true), first, blank, second)

        assertEquals(second, NodeFinder.findNthSong(root, 2))
        assertNull(NodeFinder.findNthSong(root, 3))
        assertNull(NodeFinder.findNthSong(musicRoot(songChip(selected = false), first, second), 2))
    }

    @Test
    fun `채널 버튼이나 검색창은 영상으로 보지 않는다`() {
        // 예전 구현은 "라벨이 있는 첫 누를 수 있는 노드"를 골라서 검색창을 눌렀고,
        // 영상이 열리지 않은 채 성공으로 끝났다.
        val root = node().with(
            node("cats", clickable = true),
            node(clickable = true, description = "cats for life 채널로 이동"),
            videoItem("귀여운 고양이 모음"),
        )

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("귀여운 고양이 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `Shorts보다 일반 영상을 먼저 고른다`() {
        // Shorts 가 검색 결과 맨 위에 묶음으로 나오는데, 몇 초짜리 클립이라
        // "영상 틀어줘"의 답으로는 맞지 않는다.
        val root = node().with(
            videoItem("고양이 짤", shorts = true),
            videoItem("귀여운 고양이 모음"),
        )

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("귀여운 고양이 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `화면 요약은 보이는 글자와 누를 수 있는 것을 나눠서 모은다`() {
        // 재분석 때 LLM 에 넘기는 정보다. 누를 수 없는 안내 문구까지 "누를 수 있는 것"에
        // 섞이면 모델이 못 누르는 대상을 계획에 넣는다.
        val root = node().with(
            node("검색 결과", clickable = false),
            node("귀여운 고양이 모음", clickable = true),
        )

        val screen = NodeFinder.describeScreen(root)

        assertEquals(listOf("검색 결과", "귀여운 고양이 모음"), screen.texts)
        assertEquals(listOf("귀여운 고양이 모음"), screen.clickableLabels)
    }
}
