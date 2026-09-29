# Accessibility 실행 모듈

담당자: 공규태

## 상태와 목적

LLM Action Plan을 받아 Android 앱을 실행하고 접근성 노드를 클릭·입력·스크롤하는 실행 계층이다. 교보문고·알라딘의 로그인 기능은 제거했다. 네이버/구글 검색, 유튜브 검색·첫 영상 재생, 유튜브 Shorts·인스타그램 릴스의 다음 영상 이동을 추가했다. 단위 테스트와 APK 빌드는 개발 환경에서 확인하며, 앱별 UI 구조는 **Galaxy S25+ 실기기 검증 필요**다.

```text
사용자 음성 → STT → 텍스트 → LLM → 실행계획 JSON
→ [AccessibilityService, 이 모듈] → 실제 Android 화면 조작 → TTS 상태 안내
```

## 사용 기술

- Android `AccessibilityService`, `AccessibilityNodeInfo`, `GestureDescription`
- `ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_FORWARD/BACKWARD`
- `Intent.ACTION_VIEW` 기반 네이버·구글·유튜브 직접 검색
- `Handler` 기반 비동기 retry/timeout; 앱 메인 스레드에서 긴 `Thread.sleep()` 미사용
- `org.json` 기반 엄격한 JSON 검증
- 기존 `AndroidTtsController` 기반 실행 상태 음성 안내

## 지원 앱과 Action

| Action | 지원 앱 | 동작 |
| --- | --- | --- |
| `open_app` | 전체 | 확인된 패키지의 실행 Activity 시작 |
| `search` | 네이버, 구글, 유튜브 | `target` 검색어로 해당 앱의 검색 URI 열기 |
| `tap` | 전체 | text/contentDescription/hint/resource-id의 유일한 정확 일치 요소 클릭 |
| `input_text` | 전체 | 대상 입력 필드에 `value`를 `ACTION_SET_TEXT`로 입력 |
| `play_first` | 유튜브 | 검색 결과에서 첫 번째 재생 가능 후보 클릭 |
| `next_video` | 유튜브, 인스타그램 | 선택적 지연 후 scroll-forward 또는 위쪽 swipe 수행 |
| `scroll` | 전체 | `up/down/forward/backward` 방향 기본 스크롤 |
| `wait` | 전체 | 0~30초 비동기 대기 |

패키지 매핑은 다음과 같다.

| 앱 | package name |
| --- | --- |
| 교보문고 | `mok.android` |
| 알라딘 | `kr.co.aladin.third_shop` |
| 네이버 | `com.nhn.android.search` |
| 구글 | `com.google.android.googlequicksearchbox` |
| 유튜브 | `com.google.android.youtube` |
| 인스타그램 | `com.instagram.android` |

로그인, 자격증명 저장·입력, 결제는 지원하지 않는다. 아이디·비밀번호·API Key를 JSON, 코드, 로그, 문서에 넣지 않는다.

## 실행 안정성

각 UI 액션은 250ms 간격으로 최대 8초 재시도하며 접근성 이벤트가 오면 즉시 다시 확인한다. 앱 실행과 직접 검색은 최대 15초 동안 올바른 전면 패키지가 3초간 유지되는지 확인한다. 성공 후 화면 이벤트가 300ms 동안 안정되기를 최대 1.5초 기다린 뒤 다음 단계로 진행한다. 다른 앱의 root는 절대 조작하지 않으며, 실패하면 뒤 단계는 실행하지 않는다.

`next_video.value`는 사용자가 명시한 지연 시간이다. Accessibility API는 유튜브/인스타그램의 정확한 재생 종료 이벤트를 일반적으로 제공하지 않으므로 “영상이 실제로 끝난 순간” 자동 전환은 현재 보장하지 않는다. 앱별 재생 상태 노드 또는 MediaSession 연동이 확인되기 전까지는 명시적 시간 지연 또는 즉시 넘기기만 지원한다.

## 폴더 구조

```text
ACCESSIBILITY/
├── README.md
├── VALIDATION.md
└── device-tests/                  개발용 외부 기기 검사 앱
app/src/main/
├── AndroidManifest.xml
├── java/com/sihoo/aemx/accessibility/
│   ├── AemxAccessibilityService.kt
│   ├── ActionExecutor.kt
│   ├── ActionPlan.kt
│   ├── ActionPlanActivity.kt
│   ├── AppLauncher.kt
│   └── NodeFinder.kt
└── res/xml/accessibility_service_config.xml
```

## AccessibilityService 활성화

1. AEMX를 실행하고 **접근성 서비스 설정 열기**를 누른다.
2. 설치된 앱에서 AEMX 화면 실행 서비스를 선택한다.
3. 사용자가 직접 서비스를 활성화한다. 앱은 권한을 강제로 켜지 않는다.
4. AEMX로 돌아와 활성화 상태를 확인한다.
5. **Action Plan 실행 테스트**에서 JSON을 실행한다.

LLM 통합 시 메인 앱 프로세스의 메인 스레드에서 다음 진입점을 호출한다.

```kotlin
AemxAccessibilityService.submitActionPlan(actionPlanJson)
```

## JSON 예시

네이버 날씨:

```json
{"app":"네이버","steps":[{"action":"open_app","target":"네이버"},{"action":"search","target":"서울 날씨"}]}
```

구글 뉴스:

```json
{"app":"구글","steps":[{"action":"open_app","target":"구글"},{"action":"search","target":"오늘 주요 뉴스"}]}
```

유튜브 검색 후 첫 영상 재생:

```json
{"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"search","target":"오늘 날씨"},{"action":"play_first","target":"첫 번째 동영상"}]}
```

쇼츠 10초 뒤 다음 영상:

```json
{"app":"유튜브","steps":[{"action":"open_app","target":"유튜브"},{"action":"tap","target":"Shorts"},{"action":"next_video","target":"다음 동영상","value":"10s"}]}
```

인스타 릴스 즉시 다음 영상:

```json
{"app":"인스타그램","steps":[{"action":"open_app","target":"인스타그램"},{"action":"tap","target":"릴스"},{"action":"next_video","target":"다음 릴스"}]}
```

`search` 검색어는 `target`에 둔다. `input_text`만 `value`가 필수다. `wait`와 `next_video.value`는 정수 초 또는 `ms`/`s` 단위이며 최대 30초다. 한 계획은 최대 50단계, JSON은 100,000자, target/value는 각 2,000자로 제한한다.

## 테스트

```bash
./gradlew test
./gradlew assembleDebug
./gradlew lint
```

개발용 기기 검사 시나리오는 `naver_weather`, `google_news`, `youtube_search`, `youtube_next`, `instagram_next`, `kyobo_search`, `aladin_open`을 제공한다. 실행법과 현 검증 결과는 [VALIDATION.md](VALIDATION.md)를 참고한다.

## UI 노드 확인 (`uiautomator dump`)

검사할 화면을 실기기에 연 뒤 실행한다.

```bash
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
```

`window.xml`에서 `text`, `content-desc`, `resource-id`, `class`, `clickable`, `scrollable`을 확인한다. 계정 정보나 개인 콘텐츠가 포함될 수 있으므로 덤프는 Git에 커밋하거나 공유하지 않는다. Windows에서는 `grep` 대신 `findstr`를 사용할 수 있다.

## 알려진 제한사항과 실기기 검증 항목

- Galaxy S25+에서 여섯 앱의 설치 패키지와 실행 URI 재확인
- 네이버 날씨, 구글 뉴스 검색 결과 화면 도달 확인
- 유튜브 검색 결과의 첫 재생 후보 resource-id/설명 확인
- YouTube UI 언어에 따른 `Shorts`, 인스타 UI 언어에 따른 `릴스` target 확인
- 쇼츠/릴스 화면에서 scroll-forward 또는 swipe-up 동작 확인
- 영상 종료 시점은 현재 감지하지 않음; 정확한 자동 전환에는 앱별 노드/MediaSession 추가 조사 필요
- 광고, 로그인 유도, 개인정보 동의, 팝업, 네트워크 지연, 앱 업데이트로 UI가 달라질 수 있음
- Compose/WebView/custom view가 노드를 충분히 노출하지 않으면 앱별 selector 보강 필요
- `play_first`는 휴리스틱이므로 실제 YouTube 버전에서 고유 resource-id 기반 규칙으로 보강 필요
- TTS는 UI 명령 전달 상태를 안내할 뿐 검색·재생의 비즈니스 결과 성공을 보증하지 않음

## 다음 작업

- [ ] Galaxy S25+에서 위 기기 검사 시나리오 실행 및 UI dump 저장(민감정보 제거)
- [ ] YouTube 첫 영상 selector를 실제 resource-id 기준으로 고정
- [ ] Shorts/릴스 재생 종료 감지 가능 여부 조사 및 MediaSession 대안 검증
- [ ] 광고·팝업·UI 언어별 실패 처리 추가
- [ ] LLM Android 출력과 `submitActionPlan` 직접 연결
- [ ] STT → LLM → Accessibility → TTS 전체 실기기 통합 테스트
