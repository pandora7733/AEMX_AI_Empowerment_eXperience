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

    @Test
    fun `첫 영상으로 광고를 고르지 않는다`() {
        // 유튜브 검색 결과는 맨 위가 광고인 경우가 많다. 실기기에서 "고양이 영상"을
        // 검색했을 때 실제로 스폰서 영상이 재생돼서 걸러내게 됐다.
        val advert = videoItem("고양이 낚시대 장난감").with(node("스폰서"))
        val video = videoItem("귀여운 고양이 모음")
        val root = node().with(advert, video)

        val found = NodeFinder.findFirstPlayable(root)

        assertEquals("귀여운 고양이 모음 - 3분 12초 - 동영상 재생", found?.contentDescription)
    }

    @Test
    fun `광고뿐이면 아무것도 고르지 않는다`() {
        val advert = videoItem("고양이 장난감").with(node("스폰서"))
        val root = node().with(advert)

        assertNull(NodeFinder.findFirstPlayable(root))
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
