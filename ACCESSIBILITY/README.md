# Accessibility 실행 모듈

담당자: 공규태

## 상태

기본 실행 경로와 수동 JSON 실행 화면 구현. JSON 파싱 및 Robolectric 회귀 테스트를 포함한다. 교보문고·알라딘의 실제 UI 동작은 **실기기 검증 필요**. 빌드 결과는 `VALIDATION.md` 참고.

## 목적과 파이프라인 위치

```text
사용자 음성 → STT → 텍스트 → LLM → 실행계획 JSON
→ [AccessibilityService, 이 모듈] → 실제 Android 화면 조작
```

LLM이 생성한 Action Plan을 순서대로 해석해 앱 실행, 노드 탐색, 클릭, 텍스트 입력, 스크롤과 비동기 대기를 수행한다. STT·LLM·TTS 자체는 재구현하지 않는다.

## 사용 기술

- Android `AccessibilityService`, `AccessibilityNodeInfo`
- `ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_FORWARD/BACKWARD`
- `Handler` 기반 비동기 retry/timeout (긴 `Thread.sleep()` 미사용)
- Android `org.json` 기반 엄격한 Action Plan 파싱
- 접근성 이벤트 수집 범위와 실제 root 패키지를 교보문고·알라딘으로 제한
- 기존 `AndroidTtsController`를 통한 UI 명령 전달 완료 음성 피드백

## 지원 Action

| Action | 상태 | 동작 |
| --- | --- | --- |
| `open_app` | 구현 | 앱 이름을 패키지명으로 변환해 실행 |
| `tap` | 구현 | text/contentDescription/hint/resource-id의 유일한 정확 일치, 클릭 가능한 부모까지 검사 |
| `input_text` | 구현 | 대상 입력 필드에 `ACTION_SET_TEXT` 수행 |
| `input_credential` | 연결점 구현 | `CredentialProvider`가 값을 제공할 때 입력. 현재 저장 구현 없음 |
| `scroll` | 로컬 확장 | 첫 scrollable 노드를 up/down/forward/backward 방향으로 스크롤 |
| `wait` | 구현 | 기본 1초, 최대 30초의 비동기 대기 |

각 UI Action은 250ms 간격, 최대 8초 동안 재시도하며 접근성 이벤트가 발생하면 다음 시도를 앞당긴다. 실패 시 뒤 단계는 실행하지 않는다.

성공한 UI 명령을 반복하지 않고, 이벤트가 300ms 동안 잠잠해지기를 최대 1.5초 기다린 뒤 다음 단계를 진행한다. 앱 실행도 실제 root의 패키지가 준비될 때까지 기다린다. 이 안정화 대기는 서버 응답이나 로그인 성공을 보장하지 않는다. 사용자가 다른 앱으로 이동하면 해당 화면은 조작하지 않고 timeout 처리한다. 실행 중 새 계획은 거부하며, 중단 시 예약 콜백을 제거한다.

## 폴더 구조

```text
ACCESSIBILITY/README.md
app/src/main/
├── AndroidManifest.xml
├── java/com/sihoo/aemx/
│   ├── MainActivity.kt
│   └── accessibility/
│       ├── AemxAccessibilityService.kt
│       ├── ActionExecutor.kt
│       ├── ActionPlanActivity.kt
│       ├── ActionPlan.kt
│       ├── AppLauncher.kt
│       ├── CredentialProvider.kt
│       └── NodeFinder.kt
└── res/xml/accessibility_service_config.xml
```

## AccessibilityService 활성화

1. AEMX 앱을 실행한다.
2. **접근성 서비스 설정 열기**를 누른다.
3. 설치된 앱/AEMX 화면 실행 서비스를 찾아 사용자가 직접 활성화한다.
4. 앱으로 돌아와 활성화 상태 문구를 확인한다.
5. **Action Plan 실행 테스트**에서 JSON을 입력하고 **JSON 실행**을 누른다. 대상 앱으로 이동한 뒤 AEMX로 돌아오면 단계/완료/실패 상태를 확인할 수 있다.
6. 중단하려면 AEMX로 돌아와 **실행 중단**을 누른다. 회전 시 입력 JSON은 저장/복원하지 않는다.

Android 보안 정책상 앱이 접근성 서비스를 코드로 강제 활성화하지 않는다.

## LLM 연결 방법과 JSON 예시

LLM이 JSON 생성을 끝낸 뒤 같은 `app` 프로세스의 **메인 스레드**에서 다음 진입점을 호출한다. 별도 `LLM/LlamaDemo` 앱에서는 직접 호출할 수 없으며 메인 app 통합은 팀의 후속 작업이다.

```kotlin
val result = AemxAccessibilityService.submitActionPlan(actionPlanJson)
result.onFailure { error -> /* UI에 안전한 오류 표시 */ }
```

반환 `Result<Unit>`는 접수 여부다. 비동기 실행 완료는 `AemxAccessibilityService.status`로 구분한다. 완료는 UI 명령 전달 완료이며 로그인/구매의 비즈니스 성공 판정이 아니다.

```json
{
  "app": "교보문고",
  "steps": [
    {"action": "open_app", "target": "교보문고"},
    {"action": "tap", "target": "검색창"},
    {"action": "input_text", "target": "검색창", "value": "데미안"},
    {"action": "tap", "target": "검색"}
  ]
}
```

`input_text`에는 `value`가 필수이고 `input_credential.target`은 `id` 또는 `password`만 허용한다. 알 수 없는 Action과 잘못된 JSON은 실행 전에 거부한다.
비정상적으로 큰 입력을 막기 위해 한 계획은 최대 50단계, 각 target/value는 최대 2,000자로 제한한다.
전체 JSON은 100,000자 이하이며 필드는 문자열이어야 한다. `open_app.target`은 `app`과 같은 앱이어야 한다. credential의 value는 거부한다.

LLM README는 wait/scroll의 상세 스키마를 정의하지 않았다. 아래는 **실행기 로컬 확장**이며 팀 합의 전 LLM 공통 스키마로 간주하지 않는다.

```json
{"app":"알라딘","steps":[{"action":"open_app","target":"알라딘"},{"action":"wait","value":"500ms"},{"action":"scroll","target":"down"}]}
```

wait는 value, 없으면 target을 사용한다. 생략/빈 문자열은 1초, 정수는 초, `500ms`/`2s`는 명시 단위다. 범위는 0~30초이며 음수·소수·범위 초과는 실행 전에 거부한다. 상태 대기는 후속 UI 액션의 준비 확인으로 처리한다. scroll은 기본 방향만 지원하고 거리/컨테이너 지정은 TODO다.

## 테스트 방법

```bash
./gradlew test
./gradlew assembleDebug
./gradlew lint
```

설치 후 등록 여부 확인:

```bash
adb shell dumpsys accessibility | grep -i aemx
```

## UI 노드 확인 (`uiautomator dump`)

교보문고 또는 알라딘에서 검사할 화면을 연 뒤 실행한다.

```bash
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml
```

`window.xml`에서 `text`, `content-desc`, `resource-id`, `class`, `clickable`, `scrollable`을 확인하고 LLM target 및 노드 탐색 규칙과 맞춘다. 비밀번호 등 민감 정보가 포함될 수 있으므로 덤프 파일을 Git에 커밋하거나 공유하지 않는다.

패키지명도 실기기에서 확인한다.

```bash
adb shell pm list packages | grep -Ei 'kyobo|aladin'
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
```

2026-09-25 Google Play 등록 페이지로 확인한 매핑은 다음과 같다. 설치된 앱의 실제 ID는 실기기에서도 재확인한다.

- [교보문고](https://play.google.com/store/apps/details?id=mok.android): `mok.android`
- [알라딘](https://play.google.com/store/apps/details?id=kr.co.aladin.third_shop): `kr.co.aladin.third_shop`

앱 추가 시 `AppLauncher` 매핑, Manifest의 queries, 접근성 설정 XML의 packageNames를 함께 갱신한다. 패키지명이 확인되지 않으면 추측하지 말고 TODO로 남긴다. Windows에서는 `grep` 대신 `findstr /i "kyobo aladin"` 등을 사용할 수 있다.

## Credential 보안

저장 방식이 확정되지 않아 `CredentialProvider` 인터페이스와 안전하게 실패하는 미설정 구현만 제공한다. 아이디·비밀번호·API Key는 코드, Git, README, 로그에 저장하지 않는다. 이후 Android Keystore 기반 암호화 저장 또는 Autofill 연동을 팀에서 결정해야 한다. Executor와 서비스 로그에는 credential 값과 입력 텍스트를 출력하지 않는다.

기존 `AEMX_upload`는 독립 UI/더미 데이터 프로젝트이며 재사용 가능한 보안 credential 저장소로 간주하지 않았다. 팀 provider는 연결 후 유휴 상태에서 메인 스레드로 `configureCredentialProvider(provider)`를 호출해 연결한다. 서비스 재연결 시 다시 연결해야 한다. provider는 빠르게 반환해야 하며 I/O를 메인 스레드에서 수행하지 않는다. `id`/`password`는 현재 정확한 노드 라벨/ID와 일치해야 하므로 실기기에서 별칭 매핑 확정이 필요하다.

## 알려진 제한사항 / 실기기 검증 항목

- 교보문고·알라딘의 설치 기기 패키지명 및 실제 실행 여부 재검증 필요
- 로그인/검색 화면의 노드 text, contentDescription, hint, resource-id 검증 필요
- WebView/Compose/custom view가 접근성 노드를 충분히 노출하지 않는 화면 검증 필요
- 검색 결과 첫 항목처럼 동일 문구가 여러 개인 경우 선택 규칙 보강 필요
- 복수 일치/부분 일치는 오조작 방지를 위해 거부하며 고유 resource-id를 사용한다. 최대 2,000개 노드까지만 탐색한다.
- 화면 회전, 팝업, 네트워크 지연, 앱 업데이트 후 재검증 필요
- 서비스가 꺼졌거나 프로세스가 분리된 경우 현재의 직접 호출 진입점은 사용할 수 없음
- `scroll`은 LLM 스키마에 상세 거리·컨테이너가 없어 기본 방향만 지원
- TTS 성공 안내는 엔진 준비 상태에 따라 재생되지 않을 수 있으며 실행 성공 판정과 분리됨
- Action API의 true 반환은 요청 수락이며 실제 로그인·검색 완료 검증은 별도로 필요하다.

## 다음 작업

- [ ] 교보문고·알라딘 실기기에서 패키지명과 UI 덤프 확정
- [ ] 앱별 target 별칭 또는 resource-id 우선 탐색 규칙 추가
- [ ] 팀 결정에 맞는 안전한 `CredentialProvider` 구현
- [ ] LLM Android 실행부에서 `submitActionPlan` 연결
- [ ] 로그인·검색 시나리오 계측 테스트와 실패 UI 추가
- [ ] 팀 공통 기기에서 전체 STT → LLM → Accessibility → TTS 통합 검증
