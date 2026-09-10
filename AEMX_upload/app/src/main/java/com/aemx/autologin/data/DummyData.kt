package com.aemx.autologin.data

import com.aemx.autologin.data.model.Account

/**
 * 화면 미리보기용 더미 데이터 저장소.
 * 실제 DB/네트워크 없이 UI가 어떻게 보이는지 확인하기 위한 샘플 목록이다.
 */
object DummyData {

    /**
     * 계정 목록 샘플 12건. 분류는 "쇼핑" / "SNS" / "금융" / "학교" / "기타" 만 사용한다.
     * 건수(12건)와 자동 로그인 사용 건수(8건)는 홈/계정 화면에 표기된 문자열 리소스
     * (home_stat_accounts_value, home_stat_auto_value, accounts_count_format)와 일치시켰다.
     */
    val accounts: List<Account>
        get() = listOf(
            Account(
                id = 1L,
                siteName = "네이버",
                siteUrl = "https://www.naver.com",
                loginId = "hong_gildong",
                category = "기타",
                autoLoginEnabled = true,
                lastUsed = "3분 전"
            ),
            Account(
                id = 2L,
                siteName = "카카오",
                siteUrl = "https://www.kakao.com",
                loginId = "hong.gildong@example.com",
                category = "SNS",
                autoLoginEnabled = true,
                lastUsed = "1시간 전"
            ),
            Account(
                id = 3L,
                siteName = "쿠팡",
                siteUrl = "https://www.coupang.com",
                loginId = "coupang_demo",
                category = "쇼핑",
                autoLoginEnabled = false,
                lastUsed = "3일 전"
            ),
            Account(
                id = 4L,
                siteName = "유튜브",
                siteUrl = "https://www.youtube.com",
                loginId = "hong.gildong@example.com",
                category = "SNS",
                autoLoginEnabled = true,
                lastUsed = "어제"
            ),
            Account(
                id = 5L,
                siteName = "인스타그램",
                siteUrl = "https://www.instagram.com",
                loginId = "gildong_daily",
                category = "SNS",
                autoLoginEnabled = true,
                lastUsed = "12분 전"
            ),
            Account(
                id = 6L,
                siteName = "넷플릭스",
                siteUrl = "https://www.netflix.com",
                loginId = "family.demo@example.com",
                category = "기타",
                autoLoginEnabled = false,
                lastUsed = "1주 전"
            ),
            Account(
                id = 7L,
                siteName = "학교 포털",
                siteUrl = "https://portal.univ.ac.kr",
                loginId = "20250000",
                category = "학교",
                autoLoginEnabled = true,
                lastUsed = "2시간 전"
            ),
            Account(
                id = 8L,
                siteName = "국민은행",
                siteUrl = "https://www.kbstar.com",
                loginId = "gildong_kb",
                category = "금융",
                autoLoginEnabled = false,
                lastUsed = "5일 전"
            ),
            Account(
                id = 9L,
                siteName = "배달의민족",
                siteUrl = "https://www.baemin.com",
                loginId = "01000000000",
                category = "쇼핑",
                autoLoginEnabled = true,
                lastUsed = "어제"
            ),
            Account(
                id = 10L,
                siteName = "깃허브",
                siteUrl = "https://github.com",
                loginId = "aemx-demo",
                category = "기타",
                autoLoginEnabled = true,
                lastUsed = "30분 전"
            ),
            Account(
                id = 11L,
                siteName = "토스",
                siteUrl = "https://www.toss.im",
                loginId = "01000000000",
                category = "금융",
                autoLoginEnabled = true,
                lastUsed = "6시간 전"
            ),
            Account(
                id = 12L,
                siteName = "당근마켓",
                siteUrl = "https://www.daangn.com",
                loginId = "gildong_market",
                category = "쇼핑",
                autoLoginEnabled = false,
                lastUsed = "2주 전"
            )
        )
}
