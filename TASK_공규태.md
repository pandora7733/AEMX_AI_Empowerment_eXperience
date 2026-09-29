# AccessibilityService 실행 모듈 (담당: 공규태)

## 현재 범위

```text
사용자 음성 → STT → 텍스트 → LLM → 실행계획 JSON
→ AccessibilityService → 실제 Android 화면 조작
```

지원 대상은 교보문고·알라딘의 기본 검색 조작, 네이버·구글 검색, 유튜브 검색/첫 영상 재생, 유튜브 Shorts·인스타그램 릴스의 다음 영상 이동이다.

로그인, 자격증명 저장·자동입력, 구매·결제는 현재 범위가 아니며 구현하지 않는다. 아이디·비밀번호·API Key를 JSON, 코드, Git, 문서, 로그에 저장하지 않는다.

## Action Plan

현재 action은 `open_app`, `tap`, `input_text`, `search`, `play_first`, `next_video`, `scroll`, `wait`다. 상세 스키마와 예시는 `LLM/README.md`, 실행 방식과 테스트는 `ACCESSIBILITY/README.md`를 기준으로 한다.

## 실기기 확인

Galaxy S25+에서 각 앱의 package name, 검색 URI, 접근성 노드의 text/contentDescription/resource-id/clickable/scrollable, YouTube 첫 영상 후보, Shorts/릴스 swipe를 확인한다. 정확한 영상 종료 시점은 접근성 API만으로 보장하지 않으며 별도 MediaSession 또는 앱별 상태 노드 조사가 필요하다.
