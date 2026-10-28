# AEMX — 새 세션용 현황 (2026-10-28 기준)

이 파일은 새 작업 세션이 바로 상황을 파악하도록 쓴 것이다. 작업이 바뀌면 이 파일의 "지금 하는 일"과
"결정된 것"을 먼저 고친다.

## 한 줄 요약
음성으로 말하면 AI가 폰 앱을 대신 조작해 주는 안드로이드 접근성 앱. 제11회 전국 고등학교 동아리
SW경진대회 출품작. **마감 11월 6일**(포스터 포함).

흐름: 음성 → STT(sherpa-onnx) → LLM(Qwen3 1.7B, llama.cpp) → 실행계획 JSON →
AccessibilityService가 실제 화면 조작 → TTS 안내.

## 사람
- 사용자(재준): STT·LLM 담당. 대화는 한국어. 명령어는 **Windows cmd 기준**으로 안내.
- 형 = 박시후(팀장). 계획서 기준을 정한다. 공규태(실행모듈·TTS), 김정우(파이프라인).
- 이 환경(클라우드)은 안드로이드 빌드·폰 테스트를 못 한다. 직접 돌려 보지 않은 코드는 성공했다고
  말하지 않는다. 빌드·테스트는 사용자가 PC에서 한다(`gradlew.bat :app:testDebugUnitTest`,
  한글 경로면 `subst X: "경로"` 우회, 루트 README "자주 발생하는 문제" 참고).

## 폴더 구조
```
app/                      안드로이드 앱 하나(패키지 com.sihoo.aemx, arm64-v8a 전용)
  src/main/java/com/sihoo/aemx/
    accessibility/        실행모듈: 서비스, 노드 찾기, 앱 실행, 메시지 보내기(카톡·문자)
    assistant/            상태 캡슐(오버레이) UI와 상태 관리
    pipeline/
      PipelineOrchestrator.kt   명령 하나의 전체 흐름(계획→실행→검증→재분석)
      port/               경계 인터페이스(PlanGenerator, ActionExecutor, SpeechSource …)
      model/              ActionPlan, ActionType, ScreenSnapshot 등 데이터
      json/               LLM 응답 JSON 파서
      llm/                LlamaPlanGenerator(로컬 모델) + 안전 가드들 + CloudRoutingPolicy
      stt/                SherpaSpeechSource, 호출어, 화자 확인(내 목소리만)
      device/             실행모듈·화면관찰 어댑터
      ui/PipelineDebugActivity.kt  음성/텍스트 입력 진입점(디버그 빌드 전용 "AEMX 개발")
  src/test/               단위 테스트(accessibility/, assistant/, pipeline/)
LLM/                      프롬프트 원본 system_prompt.txt(빌드 때 assets로 복사), 모델 설정
STT/ TTS/ ACCESSIBILITY/  파트별 문서와 도구
llm-engine/ stt-engine/   네이티브 엔진 모듈
CHECKLIST.md              기능별 실기기 확인 상태(`[x]` 확인 / `[~]` 코드만 / `[ ]` 아직)
DEVICE_TEST_REPORT.md     실기기 측정값(속도 등)
E2E_TEST_PLAN.md          종단 시나리오 테스트 계획(브랜치 docs/e2e-test-plan)
```
`AEMX_upload/`는 옛 UI 껍데기라 지우기로 했다(브랜치 chore/remove-aemx-upload, 아직 main에 미병합).

## 동작 규칙(코드에 박혀 있는 것)
- action 종류: open_app, tap, input_text, search, play_first, next_video, scroll, wait,
  send_message. **세 곳이 같아야 한다**: `LLM/system_prompt.txt`, `pipeline/model/ActionPlan.kt`,
  `accessibility/ActionPlan.kt`.
- 가드: SendMessageGuard(메시지 내용은 사용자가 말한 것만), AppMentionGuard,
  MessageChannelGuard, PlayOrdinalGuard, ModelIntegrity(모델 SHA-256), PipelineRedaction(로그 마스킹).
- 개인정보(메시지·연락처·음성·화자 임베딩)는 기기 밖으로 보내지 않는다.

## 실기기 확인된 것(재준 확인, 기록·수치·영상은 아직 없음)
호출어 "하이 에이맥스", 내 목소리만 듣기, STT/TTS 연결, 말하면 바로 실행, 상태 캡슐,
유튜브 검색·재생, 네이버·구글·교보·알라딘 검색, 인스타 릴스 다음 영상,
카톡·문자 보내기, 실패 후 재분석, 유튜브뮤직·멜론·쿠팡 열기.
성능(DEVICE_TEST_REPORT): S25+ NPU, 생각 끄기 후 계획 생성 약 2.3초, 실행 약 8.7초(앱이 그려지는
시간 대부분), 텍스트 명령 총 약 11초.

## 지금 하는 일: 클라우드 API 연동 (브랜치 `feature/cloud-routing`)
계획서에 "개인정보·간단한 동작·웹검색 외 어려운 동작은 클라우드 AI를 API로 불러 처리"라고 명시돼 있어
형 지시로 구현한다.

결정된 것
- 서비스: **Groq 무료 키**(카드 없이 가입, OpenAI 호환 HTTP). 막히면 Gemini 무료로 교체 가능하게
  서비스 하나에 묶지 않는다. 무료 한도는 바뀔 수 있으니 키를 만든 뒤 콘솔에서 확인한다.
- 키는 **디버그 화면의 설정에 직접 입력**. 레포·APK에 넣지 않는다.
- 구조: 로컬이 계획을 먼저 만들고 → `CloudRoutingPolicy`가 어려운 동작이 있다고 판단하면 클라우드가
  같은 JSON 스키마로 계획을 만든다 → 기존 파서·가드·실행기를 그대로 쓴다.
- 개인정보 동작은 코드로 클라우드에서 막는다. 클라우드 실패·시간 초과 시 로컬 계획으로 되돌린다.
- 키가 없으면 지금과 똑같이 로컬만 동작한다(기본값 꺼짐 — 기존 데모를 깨지 않는다).

진행 상황
- [x] `pipeline/llm/CloudRoutingPolicy.kt` + `CloudRoutingPolicyTest.kt` (올림, **아직 빌드·테스트 안 해 봄**)
- [x] `llm/CloudChatClient.kt`, `GroqChatClient.kt`(HTTP), `CloudPlanGenerator.kt`(+`PlanGuards`),
      `HybridPlanGenerator.kt`(로컬 먼저 → 어려운 동작이면 클라우드, 실패·10초 초과면 로컬로 폴백)
      + 테스트 `CloudPlanGeneratorTest`, `HybridPlanGeneratorTest` (올림, **빌드·테스트 안 해 봄**)
- [ ] `PipelineDebugActivity`에 키 입력 칸·"클라우드 AI 사용" 체크 연결, `HybridPlanGenerator`로 감싸기
      (`llmPlanner` 를 쓰는 곳: 322, 357, 405, 416줄 부근. 키 저장은 앱 전용 SharedPreferences)
- [ ] 실기기에서 속도·성공률 측정(로컬 vs 클라우드)

## 그 밖에 남은 일
- 모델 비교: 형이 "왜 Qwen3 1.7B냐"고 물었다. 비교 기록이 레포에 없다. 같은 명령 10개로
  EXAONE 3.5 2.4B 등을 돌려 정확도·속도 표를 만들 계획(우선순위는 클라우드 뒤).
  모델을 바꾸면 `LlamaPlanGenerator.kt`의 MODEL_URL·SHA-256과 프롬프트도 같이 바꿔야 한다.
- 음성 입력 진입점이 디버그 빌드에만 있다(릴리스 빌드용 진입점 미정).
- `LLM/README.md`, `ACCESSIBILITY/README.md`에 send_message·유튜브뮤직 미반영.
- CHECKLIST.md의 "main은 9/22 이후 멈춰 있고"는 사실과 다르다(main에 10/27 커밋, integration 브랜치 존재).
  `integration`과 `main` 관계 미확인.
- 속도 측정, 시연 영상, 로그가 아직 없다(포스터·발표에 필요).

## 브랜치(원격)
- `main`: 기준. 직접 푸시하지 않고 기능 브랜치 + PR로 작업한다.
- `feature/cloud-routing`: 클라우드 연동(이 파일이 있는 곳).
- `docs/sync-checklist`, `docs/e2e-test-plan`, `chore/remove-aemx-upload`: 각각 독립. PR 미생성.

## 주의
- `PlanGuards`(llm/CloudPlanGenerator.kt)는 `LlamaPlanGenerator.generate`의 가드 순서를 복사한 것이다.
  가드를 고치면 둘 다 고친다. 로컬 테스트가 통과하면 로컬도 `PlanGuards`를 쓰게 합치는 게 좋다.
- Groq 기본 모델 `llama-3.3-70b-versatile`은 바뀔 수 있다. 안 되면 콘솔 모델 목록에서 골라
  `GroqChatClient.DEFAULT_MODEL`을 바꾼다.
