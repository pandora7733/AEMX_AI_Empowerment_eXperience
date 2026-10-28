# AEMX 새 세션 안내 (2026-10-28)

먼저 PC의 작업 메모 C:\AI 대회\AEMX_AI_Empowerment_eXperience\HANDOFF.md 를 읽는다. 사람, 작업 방식, 빌드 방법, 기기와 로그, 기능별 상태, 오버레이 UI 단계가 거기 있고, 맨 아래 "클라우드 연동" 섹션이 지금 하는 일이다. 그 파일이 없는 환경이면 아래 요약으로 시작한다.

지금 하는 일
- 형이 제출한 계획서대로 어려운 동작(유튜브 영상 재생, 특정 앱 안에서 누르고 입력하기)은 클라우드 AI(Groq 무료 키)로 계획을 만든다. 개인정보(메시지, 연락처, 전화)는 클라우드로 보내지 않는다. 실패하거나 느리면 로컬 계획으로 되돌린다. 마감은 11월 6일.
- 코드는 이 브랜치(feature/cloud-routing)에 있고 origin/main 4f48610 위에 쌓았다. 한 번도 빌드하거나 테스트하지 않았다.

시작 순서
- git fetch origin 후 feature/cloud-routing 으로 이동한다. 로컬 변경이 있으면 건드리지 말고 먼저 알린다.
- 단위 테스트와 assembleDebug 를 돌린다(HANDOFF.md 의 빌드 방법 그대로). 오류가 나면 원인을 보고 최소로 고친다. 기존 가드와 안전 로직은 바꾸지 않는다.
- 폰의 개발 화면 "Groq API 키" 칸에는 재준이 직접 키를 입력한다. 키는 채팅, 파일, 커밋 어디에도 적지 않는다.
- 폰 확인과 측정 항목은 HANDOFF.md 의 "남은 일"을 따른다.

코드 위치
- app/src/main/java/com/sihoo/aemx/pipeline/llm/ 에 CloudRoutingPolicy, CloudChatClient, GroqChatClient, CloudPlanGenerator, HybridPlanGenerator, CloudSettings. 연결은 pipeline/ui/PipelineDebugActivity.kt 의 planFor.
- 키는 개발 중에는 개발 화면 입력, 최종 APK는 local.properties 의 GROQ_API_KEY 를 빌드할 때 BuildConfig 에 넣는다(app/build.gradle.kts). APK는 공개 배포하지 않는다.
- PlanGuards 는 LlamaPlanGenerator 의 가드 순서를 복사한 것이라 가드를 고치면 두 곳을 같이 고친다.

작업 규칙(재준 요청)
- 한 번에 한 작업 단위만 고치고, 바꾼 파일과 이유를 먼저 말한다. 직접 실행하지 않은 코드는 성공했다고 하지 않는다. 정보가 부족하면 추측하지 말고 로그를 요청한다.
- 명령어는 Windows cmd 기준으로 안내한다. 말투는 짧게 결론부터. md 문서는 평범하게 쓰고 굵은 글씨, 구분선, 번호 섹션, 줄표를 쓰지 않는다.
