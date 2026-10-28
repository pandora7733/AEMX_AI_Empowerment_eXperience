# AEMX — 새 세션용 현황 (2026-10-28 기준)

이 파일은 새 작업 세션이 바로 상황을 파악하도록 쓴 것이다. 작업이 바뀌면 이 파일의 "지금 하는 일"과
"결정된 것"을 먼저 고친다.

## 새 세션 시작 지침 ("컴퓨터에서 작업하던 거 마저 해줘"라고 하면 이대로 진행)
먼저 이 파일 전체를 읽고, 아래 순서로 바로 시작한다. 되묻지 말고 1번부터 한다.
(PC에 연결된 세션이면 직접 실행하고, 아니면 명령을 cmd 기준으로 안내한다.)

1. 작업 브랜치 확인: `git fetch origin` → `git checkout feature/cloud-routing` → `git pull`.
   로컬 변경이 있으면 건드리지 말고 먼저 알린다.
2. **아직 한 번도 빌드·테스트하지 않은 코드가 있다**(클라우드 연동 전부). 단위 테스트부터:
   `gradlew.bat :app:testDebugUnitTest` (한글 경로면 `subst X: "경로"` 후 X:에서 실행).
   컴파일 오류·실패가 나면 원인 분석 → 최소 수정 → 재실행. 기존 가드·안전 로직은 임의로 바꾸지 않는다.
3. 통과하면 디버그 APK: `gradlew.bat assembleDebug`, 폰에 `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
4. 폰의 "AEMX 개발" 화면에서 Groq 키를 입력(사용자가 이미 키를 발급받음. **키는 채팅·파일·커밋 어디에도
   적지 않는다.** 사용자가 폰에 직접 입력한다) → 상태 줄이 "클라우드: 사용 가능"인지 확인.
5. 실기기 시나리오로 클라우드 경로 확인(E2E_TEST_PLAN.md의 S1~S5 참고, logcat 태그 `CloudPlan`):
   유튜브 영상 재생(클라우드), 네이버 검색(로컬), 카톡 보내기(반드시 로컬, 클라우드 호출 없어야 함),
   키 없음/네트워크 끔 → 로컬로 폴백되는지. 결과(성공·시간)를 표로 정리해 CHECKLIST.md에 반영.
6. 그 다음: 로컬 vs 클라우드 시간·성공률 비교표, 시연 영상·로그 확보, 모델 비교(아래 "그 밖에 남은 일").

작업 규칙(사용자 요청): 한 번에 한 작업 단위만 수정, 바꾼 파일 경로와 이유를 먼저 설명, 직접 실행하지
않은 코드는 성공했다고 하지 않는다. 정보가 부족하면 추측하지 말고 로그를 요청한다.

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
- 키 보관: **개발 중에는 "AEMX 개발" 앱의 키 입력 칸에 직접 입력**(폰에서 바로 가능, 앱 전용 저장소).
  **최종 APK는 사용자가 키를 입력하지 않게 빌드할 때 내장**: PC의 `local.properties`(gitignore됨) 또는 환경변수
  `GROQ_API_KEY`를 `BuildConfig.GROQ_API_KEY`로 주입(`app/build.gradle.kts`). 입력한 키가 있으면 그것이 우선.
  소스·레포·커밋·채팅에는 절대 키를 적지 않는다. APK를 풀면 키가 보이므로 APK는 공개 배포하지 않는다.
  Windows cmd 설정: `echo GROQ_API_KEY=여기에키>> local.properties` (프로젝트 루트, `>>` 앞에 공백 없이)
- 구조: 로컬이 계획을 먼저 만들고 → `CloudRoutingPolicy`가 어려운 동작이 있다고 판단하면 클라우드가
  같은 JSON 스키마로 계획을 만든다 → 기존 파서·가드·실행기를 그대로 쓴다.
- 개인정보 동작은 코드로 클라우드에서 막는다. 클라우드 실패·시간 초과 시 로컬 계획으로 되돌린다.
- "클라우드 AI 사용"은 기본 켜짐(형 지시로 계획서대로 클라우드 사용). 단 키가 없거나 실패하면 로컬로만 동작하므로 기존 데모는 깨지지 않는다. 폴백은 유지한다(필수화하되 폴백 제거는 안 함).

진행 상황
- [x] `pipeline/llm/CloudRoutingPolicy.kt` + `CloudRoutingPolicyTest.kt` (올림, **아직 빌드·테스트 안 해 봄**)
- [x] `llm/CloudChatClient.kt`, `GroqChatClient.kt`(HTTP), `CloudPlanGenerator.kt`(+`PlanGuards`),
      `HybridPlanGenerator.kt`(로컬 먼저 → 어려운 동작이면 클라우드, 실패·10초 초과면 로컬로 폴백)
      + 테스트 `CloudPlanGeneratorTest`, `HybridPlanGeneratorTest` (올림, **빌드·테스트 안 해 봄**)
- [x] `llm/CloudSettings.kt`(키·켜짐 저장, 앱 전용 SharedPreferences) + `PipelineDebugActivity`에
      키 입력 칸·저장 버튼·"클라우드 AI 사용"(기본 켜짐)·상태 표시, 하이브리드 래퍼 연결(`planFor`)
      (올림, **빌드·실기기 확인 안 해 봄**). 키 입력 칸은 디버그 빌드 "AEMX 개발" 화면에서만 가능.
- [ ] 빌드·단위 테스트 통과 확인 (위 "새 세션 시작 지침" 2번)
- [ ] 실기기에서 클라우드 경로·폴백 확인, 속도·성공률 측정(로컬 vs 클라우드)
- [ ] 화면에 "클라우드로 처리/기기에서 처리" 표시(지금은 logcat `CloudPlan` 태그에만 남음)

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
- 키를 서버(프록시) 경유로 바꾸는 안은 보류. APK를 공개 배포해야 하면 그때 한다.
