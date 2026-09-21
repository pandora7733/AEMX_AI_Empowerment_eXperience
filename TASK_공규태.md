# 다음 작업: AccessibilityService 실행 모듈 (담당: 공규태)

## 시작 전 필수: GitHub 커넥터 연결

작업 시작하기 전에 **Claude 앱에 GitHub 커넥터를 꼭 연결해두세요** — 레포 상태 확인, 브랜치 생성, PR 관리를 Claude가 바로바로 도와줄 수 있어서 훨씬 편합니다.

1. Claude 앱 설정 → Connectors(커넥터) 메뉴 이동
2. GitHub 항목에서 Connect → 본인 GitHub 계정으로 로그인/권한 승인
3. `pandora7733/AEMX_AI_Empowerment_eXperience` 저장소 접근 권한 부여

## 이 작업이 왜 필요한가

AEMX의 전체 파이프라인은 이렇게 생겼습니다:

```
사용자 음성 → [STT] → 텍스트 → [LLM] → 실행계획 JSON → [AccessibilityService, 이 작업] → 실제 화면 조작
```

STT(배재준이 예전에 만듦, `STT/README.md`)와 LLM(배재준이 지금 진행 중, `LLM/README.md`)은 "무엇을 할지" JSON으로 뽑아내는 것까지만 합니다. 그 JSON을 받아서 **실제로 폰 화면을 눌러서 실행하는 부분이 아직 하나도 없습니다.** 이걸 만드는 게 이번 작업입니다.

## 입력 형식 (LLM이 만들어주는 JSON)

`LLM/README.md`에 자세히 나와있지만 핵심만 요약하면:

```json
{"app": "교보문고", "steps": [
  {"action": "open_app", "target": "교보문고"},
  {"action": "tap", "target": "로그인"},
  {"action": "input_credential", "target": "id"},
  {"action": "input_credential", "target": "password"},
  {"action": "tap", "target": "로그인"}
]}
```

| action | 해야 할 일 |
|---|---|
| `open_app` | `target`에 적힌 이름의 앱을 실행 (패키지명 매핑 필요 — 교보문고/알라딘 실제 패키지명 확인) |
| `tap` | 화면에서 `target` 텍스트/설명을 가진 버튼·요소를 찾아서 클릭 |
| `input_credential` | `target`이 `"id"` 또는 `"password"`. 저장된 로그인 정보를 자동으로 입력 (아래 "정해야 할 것" 참고) |
| `input_text` | `target` 필드에 `value` 텍스트 입력 |
| `scroll` | 화면 스크롤 (아직 정의만 있고 테스트 케이스 없음 — 필요할 때 구현) |
| `wait` | 잠시 대기 |

## 할 일

1. **AccessibilityService 서비스 클래스 작성**
   - `AccessibilityServiceInfo` 설정 (어떤 이벤트를 구독할지, 어떤 패키지를 대상으로 할지 — 교보문고/알라딘 패키지명)
   - 사용자가 설정에서 수동으로 서비스를 켜야 함 (일반 권한처럼 코드로 자동 부여 불가) — 앱에 "접근성 설정으로 이동" 버튼 정도는 만들어두면 좋음

2. **JSON 액션플랜을 받아서 한 단계씩 실행하는 실행기(Executor) 작성**
   - `AccessibilityNodeInfo` 트리를 탐색해서 `target` 텍스트/설명과 일치하는 노드를 찾고 `ACTION_CLICK` 수행 (tap)
   - 텍스트 입력은 `ACTION_SET_TEXT` 사용 (input_text)
   - 교보문고/알라딘 실제 화면에서 버튼 이름이 어떤 텍스트로 잡히는지 확인 필요 → `adb shell uiautomator dump`로 화면 구조 덤프해서 실제 노드 텍스트 확인하는 걸 추천

3. **정해야 할 것: `input_credential`은 아이디/비밀번호를 어디서 가져올지**
   - AEMX의 "자동 로그인" 컨셉 자체가 사용자가 매번 아이디/비번을 말하지 않는 것이라, 어딘가에는 미리 저장돼 있어야 함
   - 옵션: (a) Android Keystore로 암호화해서 로컬 저장 후 최초 1회 사용자가 설정 화면에서 입력, (b) 시스템 자동완성(Autofill) 프레임워크 활용
   - 팀(특히 재준이)이랑 상의해서 방식 정하고 진행하는 걸 추천

4. **교보문고/알라딘 실기기로 테스트**
   - 로그인/검색 시나리오 최소 1개씩 실제로 끝까지 동작하는지 확인

## 참고하면 좋은 것

- `LLM/README.md` — JSON 스키마, 지원 action 전체 목록
- 루트 `README.md` — 본인이 만든 TTS 컨트롤러(`AndroidTtsController.kt`) 구조. 실행이 끝난 뒤 "로그인 완료했습니다" 같은 음성 피드백을 TTS로 주면 자연스럽게 이어붙일 수 있음 (필수는 아니고 여유 되면)
- 프로젝트 컨벤션: 모델·큰 바이너리는 git에 커밋하지 말고 setup 스크립트로 받게 할 것 (`STT/setup_assets.sh`, `LLM/setup_llamademo.sh` 참고)

## 진행 방식

- `feature/accessibility` 브랜치에서 작업
- 폴더는 `ACCESSIBILITY/` 정도로 새로 만들어서 STT/LLM처럼 독립적으로 검증 가능하게 구성 추천
- 완료되면 기존 파트들처럼 README.md 작성 (담당/상태/사용한 것/기능명세서/폴더구조/다음 할 일 형식 맞추면 좋음)
- 메인 브랜치 병합은 팀 전체 합칠 때 진행 (기존 방식과 동일)
