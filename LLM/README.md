# LLM (의도분석) 파트

담당: 박시후

## 상태

STT가 뽑아낸 자연어 명령을 "실행 가능한 작업계획(JSON)"으로 변환하는 온디바이스 LLM. PC(llama.cpp CLI)에서 프롬프트 설계 및 검증 완료. 로그인/검색/구매 3가지 의도에 대해 정확한 JSON 출력 확인.

Android 앱(`LlamaDemo/`)도 세팅함 — llama.cpp 공식 Android 예제를 가져와서 우리 프롬프트/설정에 맞게 수정. 아직 실기기에서 직접 실행해서 확인은 안 함 (다음 단계). STT 결과 연결·AccessibilityService 실행 연결도 안 함.

### 모델을 APK에 안 넣고 다운로드 방식으로 한 이유

모델이 1.2GB라 APK에 그대로 넣으면 설치 자체가 부담스러움 — 이 프로젝트가 타겟으로 하는 고령층/장애인 사용자는 저장공간이 넉넉하지 않은 폰을 쓸 수도 있어서, 앱은 가볍게 설치하고 이 기능을 처음 쓸 때만 모델을 받는 방식으로 결정함. (시연 때는 미리 받아두면 되니 네트워크 문제 없음.)

## 파이프라인에서의 위치

```
사용자 음성 → [STT] → 텍스트 → [LLM, 이 파트] → 실행계획 JSON → [AccessibilityService] → 실제 화면 조작
```

## 사용한 것

| 항목 | 내용 |
|---|---|
| 모델 | Qwen3 1.7B (Q4_K_M 양자화, GGUF) |
| 실행 엔진 | llama.cpp |
| 방식 | 시스템 프롬프트(역할 지시 + few-shot 예시) + 사용자 명령 → JSON만 출력 |
| 샘플링 | **temperature=0 (결정적 출력)** — 구조화된 출력이 필요한 작업이라 랜덤성 제거가 중요했음 |
| Qwen3 thinking 모드 | 켬 (기본값) — 꺼두면 복잡한 문장(예: 상품명이 포함된 구매 요청)에서 정확도가 떨어짐 |

## 기능 명세서

### 입력 / 출력

| 구분 | 내용 |
|---|---|
| 입력 | STT 결과 텍스트 (예: "교보문고에서 로그인하고 싶어") |
| 출력 | `{"app": "앱 이름", "steps": [{"action": "...", "target": "...", "value": "..."}]}` 형식의 JSON 문자열 |

### 지원 의도(intent) 3종

| 의도 | 예시 명령 | steps |
|---|---|---|
| 로그인 | "OO에서 로그인하고 싶어" | `open_app → tap(로그인) → input_credential(id) → input_credential(password) → tap(로그인)` |
| 검색 | "OO에서 XX 검색해줘 / 찾아줘" | `open_app → tap(검색창) → input_text(검색창, XX) → tap(검색)` |
| 구매/주문 | "XX 사고싶어 / 사려고 / 주문해줘" | `open_app → tap(검색창) → input_text(검색창, XX) → tap(검색) → tap(검색결과 첫번째 항목) → tap(구매하기)` |

### action 종류

| action | 의미 |
|---|---|
| `open_app` | 앱 실행 |
| `tap` | 화면의 버튼/요소를 누름 (`target`에 버튼 이름) |
| `input_credential` | 저장된 아이디/비밀번호를 자동 입력 (`target`에 `"id"` 또는 `"password"`). 사용자가 직접 아이디·비밀번호를 말하지 않아도 항상 이 액션 사용 — AEMX의 "자동 로그인" 컨셉 자체가 자격증명을 사용자가 입력하지 않는 것이므로 |
| `input_text` | 검색어 등 텍스트 입력 (`target`에 필드 이름, `value`에 입력할 내용) |
| `scroll` | 화면 스크롤 (정의만 해둠, 아직 테스트 케이스 없음) |
| `wait` | 잠시 대기 (정의만 해둠, 아직 테스트 케이스 없음) |

### 지원 앱

공식 지원: **교보문고, 알라딘**

새 앱 추가 시 시스템 프롬프트의 "지원 앱" 목록에 이름만 추가하면 대부분 바로 일반화됨(검증: "쿠팡"으로 예시 없이 테스트 → 검색·구매 완벽 동작). 전용 예시가 필요한 경우는 그 앱의 화면 흐름이 확연히 다를 때뿐.

### 알려진 한계

- **1.7B는 소형 모델**이라 복잡한 문장(예: 유명 도서 제목처럼 의미가 있는 고유명사가 포함된 구매 요청)에서 과도하게 의미를 해석하려다 실패하는 경우가 있었음 → 프롬프트에 "검색어 의미를 해석하지 말고 그대로 사용" 지시 추가로 개선
- **temperature > 0(기본 샘플링)에서는 같은 입력도 가끔 다른(틀린) 결과가 나옴** → temperature=0 고정으로 해결. 실서비스에서도 이 설정 유지 필요
- 구매 플로우는 "구매하기 버튼 클릭"까지만 정의됨 — 결제/배송 확인 등 후속 단계는 데모 시나리오 범위가 아직 팀에서 확정되지 않아 미포함

## 폴더 구조

```
LLM/
├── README.md              (이 파일)
├── system_prompt.txt       (LLM에 넣는 시스템 프롬프트 — 실제 사용하는 최종본)
├── setup_llm.sh             (PC 테스트용: 모델 + llama.cpp CLI 다운로드 스크립트)
├── test_prompt.sh           (PC 테스트용: 프롬프트 테스트 스크립트)
├── setup_llamademo.sh       (Android 빌드용: llama.cpp 소스 다운로드 스크립트)
└── LlamaDemo/                (Android 참고용 프로젝트 — llama.cpp 공식 예제를 우리 용도로 수정)
    ├── app/                  MainActivity: 명령 입력 → 실행계획 JSON 출력 화면
    └── lib/                  JNI 브릿지 (ai_chat.cpp) + Kotlin InferenceEngine API
```

모델(1.2GB), llama.cpp 실행파일/소스는 용량 문제로 git에 커밋하지 않음 — 스크립트로 받을 것.

## 테스트 방법 (PC, 프롬프트만 빠르게 확인)

```bash
cd LLM
bash setup_llm.sh        # 최초 1회: 모델 + llama-cli 다운로드
bash test_prompt.sh      # 기본 테스트 케이스 4개 실행
bash test_prompt.sh "알라딘에서 데미안 검색해줘"   # 원하는 문장으로 테스트
```

## Android 빌드 방법 (LlamaDemo)

```bash
cd LLM
bash setup_llamademo.sh   # 최초 1회: llama.cpp 소스 clone (NDK 빌드에 필요)
```

그다음 Android Studio에서 `LLM/LlamaDemo` 폴더를 열고 Gradle sync (NDK 27, CMake 3.31.6 필요 — 없으면 sync 중 자동 설치 시도됨). 앱을 실행하면:

1. 최초 실행 시 모델을 자동 다운로드 (진행률 화면에 표시)
2. 다운로드 끝나면 모델 로드 + 시스템 프롬프트 자동 설정
3. 하단 입력창에 명령 입력 (예: "교보문고에서 로그인하고 싶어") → 전송 → 생성되는 실행계획 확인

주의: 모델 파일명(`Qwen_Qwen3-1.7B-Q4_K_M.gguf`)과 URL은 `MainActivity.kt`의 companion object에 상수로 박혀있음. `system_prompt.txt`를 수정하면 `MainActivity.kt`의 `SYSTEM_PROMPT` 상수도 같이 갱신해야 함 (지금은 두 곳에 중복 — 나중에 asset 파일로 통합하는 게 좋을 듯).

### 빌드 중 겪은 문제 (삽질 기록)

- **NDK 라이선스 미동의 에러**: `sdkmanager --licenses` 없이 헤드리스로 빌드하면 라이선스 동의 파일이 없어서 실패함. Android Studio에서 열면 GUI가 알아서 동의 처리해줌 — 터미널로 빌드할 거면 `$SDK/licenses/android-sdk-license`, `android-sdk-preview-license`에 표준 해시를 직접 써넣어야 함
- **CMake 3.31.6이 자동 설치 안 됨**: NDK는 Gradle sync 중 자동으로 받아지는데, CMake는 별도로 SDK Manager에서 받아야 하는 경우가 있었음. Android Studio의 `Tools > SDK Manager > SDK Tools`에서 CMake 체크하면 해결됨
- **NDK 29(rc1)에서 CMake가 원인불명 크래시**: `LANGUAGES C CXX`를 함께 선언한 프로젝트를 NDK 안드로이드 툴체인으로 configure할 때, C 컴파일러 식별 직후 cmake.exe 자체가 크래시함 (exit code로 보면 스택 버퍼 오버런). CMake 버전(3.22.1, 3.31.6 모두 재현), 언어 선언 순서, `enable_language` 분리 등 여러 방법으로도 재현됨 — 근본 원인 못 찾음. **NDK를 27.2.12479018(안정 버전)로 낮추니 일단 이 조합 자체는 우회됨** (`lib/build.gradle.kts`의 `ndkVersion`에 이미 반영해둠). 만약 나중에 또 이 크래시가 나면 NDK 버전을 의심할 것
- **결론적으로 이 프로젝트는 Android Studio GUI로 여는 게 제일 편함** — 터미널(headless gradlew)로 빌드하면 라이선스/SDK 컴포넌트 자동 설치가 매끄럽지 않은 경우가 있었음

## 다음 할 일

- [ ] LlamaDemo 실기기 실행 테스트 (아직 안 해봄 — PC 테스트 결과가 폰에서도 똑같이 나오는지 확인 필요)
  - 특히 native 코드가 PC의 llama-cli와 달리 jinja 챗 템플릿을 안 쓰기 때문에(`use_jinja=false`), Qwen3의 thinking 모드 동작이 PC 테스트와 다를 수 있음 — 실행해보고 출력이 이상하면 이 부분부터 의심할 것
- [ ] STT 출력 → LLM 입력 실시간 연결
- [ ] LLM 출력(JSON) → AccessibilityService 실행 모듈 연결
- [ ] 화면 변화/오류 시 재분석 로직 (원래 기획에 있던 기능, 아직 미구현)
- [ ] 구매 플로우 범위(결제까지 할지) 팀 확정 후 프롬프트 보강
- [ ] 연속 명령 사용 시 대화 히스토리가 계속 누적됨 (엔진이 매 요청을 이전 대화의 연장으로 처리) — 명령마다 독립적으로 처리하려면 리셋 로직 추가 필요
