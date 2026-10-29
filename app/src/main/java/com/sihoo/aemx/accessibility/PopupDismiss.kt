package com.sihoo.aemx.accessibility

/**
 * 앱 시작 광고·안내 팝업을 닫을 버튼을 고른다. 화면에서 누를 수 있는 후보의 목록을 받아 번호 하나를 돌려준다.
 *
 * 닫기 후보는 라벨이 정확히 맞을 때만 고른다(부분 일치는 쓰지 않는다. "나중에 결제" 같은 문장을 잘못 누르지 않도록).
 * 결제·구매·동의·확인·설치·업데이트·로그인·허용 같은 말이 들어간 버튼은 라벨이 닫기와 맞아도 절대 고르지 않는다.
 */
object PopupDismiss {

    /** 후보 하나. [actionable] 은 눌 수 있고(클릭 노드가 있고) 보이며 활성화된 것이다. */
    data class Candidate(
        val label: String?,
        val description: String?,
        val actionable: Boolean,
    )

    /** 닫기로 인정하는 라벨. 앞에 있을수록 먼저 고른다. 비교는 공백·대소문자를 무시한다. */
    private val DISMISS_LABELS = listOf(
        "닫기", "닫기 버튼", "오늘 하루 보지 않기", "오늘 그만 보기", "다시 보지 않기",
        "나중에", "건너뛰기", "close", "x", "×", "skip", "dismiss",
    )

    /** 이 말이 들어간 후보는 닫기처럼 보여도 누르지 않는다. */
    private val BLOCKED_WORDS = listOf(
        "결제", "구매", "주문", "예약", "동의", "확인", "설치", "업데이트", "로그인", "로그아웃", "가입",
        "허용", "승인", "수락", "삭제", "전송", "보내기", "탈퇴", "공유",
    )

    /** 누를 후보의 번호. 고를 것이 없으면 null. */
    fun choose(candidates: List<Candidate>): Int? {
        for (wanted in DISMISS_LABELS) {
            val index = candidates.indexOfFirst { candidate ->
                candidate.actionable && isBlockedFree(candidate) &&
                    (matches(candidate.label, wanted) || matches(candidate.description, wanted))
            }
            if (index >= 0) return index
        }
        return null
    }

    private fun matches(text: String?, wanted: String): Boolean =
        text != null && key(text) == key(wanted)

    private fun isBlockedFree(candidate: Candidate): Boolean {
        val texts = listOfNotNull(candidate.label, candidate.description)
        return texts.none { text -> BLOCKED_WORDS.any { key(text).contains(key(it)) } }
    }

    private fun key(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()
}
