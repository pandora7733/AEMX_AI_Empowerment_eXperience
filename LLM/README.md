# LLM (의도분석) 파트

담당: 배재준

## 상태

STT가 뽑아낸 자연어 명령을 "실행 가능한 작업계획(JSON)"으로 변환하는 온디바이스 LLM. 기존 서점 검색에 더해 네이버·구글 검색, 유튜브 검색/첫 영상 재생, 유튜브 Shorts·인스타그램 릴스의 다음 영상 이동 스키마를 반영했다. 로그인·자격증명·결제 액션은 제거했다. 새 프롬프트의 온디바이스 출력은 실기기 재검증이 필요하다.

Android 앱(`LlamaDemo/`)도 세팅함 — llama.cpp 공식 Android 예제를 가져와서 우리 프롬프트/설정에 맞게 수정. 실기기(갤럭시 S25+)에서 직접 실행해 확인을 마쳤고, 메인 앱에는 `llm-engine` 모듈로 연결되어 STT와 실행모듈과 이어져 있다.

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
| 입력 | STT 결과 텍스트 (예: "네이버에서 서울 날씨 찾아줘") |
| 출력 | `{"app": "앱 이름", "steps": [{"action": "...", "target": "...", "value": "..."}]}` 형식의 JSON 문자열 |

### 지원 의도

| 의도 | 예시 명령 | steps |
|---|---|---|
| 서점 검색 | "교보문고에서 토지 검색해줘" | `open_app → tap → input_text → tap` |
| 웹/앱 검색 | "네이버에서 서울 날씨 찾아줘" | `open_app → search(서울 날씨)` |
| 동영상 검색·재생 | "유튜브에서 고양이 영상 틀어줘" | `open_app → search → play_first` |
| 다음 짧은 영상 | "인스타 릴스 다음 영상" | `open_app → tap(릴스) → next_video` |

### action 종류

| action | 의미 |
|---|---|
| `open_app` | 앱 실행 |
| `tap` | 화면의 버튼/요소를 누름 (`target`에 버튼 이름) |
| `input_text` | 검색어 등 텍스트 입력 (`target`에 필드 이름, `value`에 입력할 내용) |
| `search` | 네이버·구글·유튜브에서 `target` 검색어 직접 검색 |
| `play_first` | 유튜브 첫 재생 가능 검색 결과 클릭 |
| `next_video` | 유튜브 Shorts·인스타 릴스에서 다음 영상으로 이동. 명시적 지연은 `value` |
| `scroll` | `up/down/forward/backward` 방향 화면 스크롤 |
| `wait` | 최대 30초 비동기 대기 |

### 지원 앱

공식 지원: **교보문고, 알라딘, 네이버, 구글, 유튜브, 인스타그램**

새 앱은 프롬프트뿐 아니라 Accessibility 실행기의 패키지 매핑, Manifest queries, 접근성 서비스 packageNames, 앱별 검색 계약을 함께 추가해야 한다. 확인되지 않은 package name은 추측하지 않는다.

### 알려진 한계

- **1.7B는 소형 모델**이라 복잡한 문장(예: 유명 도서 제목처럼 의미가 있는 고유명사가 포함된 구매 요청)에서 과도하게 의미를 해석하려다 실패하는 경우가 있었음 → 프롬프트에 "검색어 의미를 해석하지 말고 그대로 사용" 지시 추가로 개선
- **temperature > 0(기본 샘플링)에서는 같은 입력도 가끔 다른(틀린) 결과가 나옴** → temperature=0 고정으로 해결. 실서비스에서도 이 설정 유지 필요
- 로그인·자격증명·결제 명령은 현재 범위에서 의도적으로 생성하지 않음
- Accessibility API만으로 유튜브/인스타그램 영상의 정확한 종료 시점은 보장할 수 없음. `next_video.value`는 사용자가 명시한 시간만 사용하며 최대 30초
- 앱 UI 언어·광고·팝업·업데이트에 따라 `tap`, `play_first` target은 실기기에서 조정이 필요함

## 폴더 구조

```
LLM/
├── README.md              (이 파일)
├── system_prompt.txt       (LLM에 넣는 시스템 프롬프트 — 실제 사용하는 최종본)
├── setup_llm.sh             (PC 테스트용: 모델 + llama.cpp CLI 다운로드 스크립트)
├── test_prompt.sh           (PC 테스트용: 프롬프트 테스트 스크립트)
├── setup_llamademo.sh       (Android 빌드용: llama.cpp 헤더 + 미리 빌드된 .so 다운로드)
├── build_native.sh          (Android 빌드용: libai-chat.so를 clang++로 직접 컴파일 — ai_chat.cpp 수정 시에만 필요)
└── LlamaDemo/                (Android 참고용 프로젝트 — llama.cpp 공식 예제를 우리 용도로 수정)
    ├── app/                  MainActivity: 명령 입력 → 실행계획 JSON 출력 화면
    └── lib/                  JNI 브릿지 (ai_chat.cpp) + Kotlin InferenceEngine API
        └── src/main/jniLibs/arm64-v8a/libai-chat.so, libc++_shared.so   (직접 컴파일한 결과물과 C++ 런타임, git에 커밋해둠)
```

모델(1.2GB), llama.cpp 소스/큰 `.so`들은 용량 문제로 git에 커밋하지 않음 — 스크립트로 받을 것. `libai-chat.so`(수십 KB)와 `libc++_shared.so`(약 9.2MB)는 예외적으로 커밋되어 있어서, **`setup_llamademo.sh`만 실행하면 CMake 없이 바로 빌드/실행 가능함.** `ai_chat.cpp`를 고쳤다면 `build_native.sh`로 `libai-chat.so`를 다시 만들어 커밋해야 한다. 안 그러면 고친 내용이 앱에 반영되지 않는다.

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
bash setup_llamademo.sh   # 최초 1회: llama.cpp 헤더 + 미리 빌드된 .so 다운로드
```

그다음 Android Studio에서 `LLM/LlamaDemo` 폴더를 열고 Gradle sync (NDK 27 필요 — 없으면 sync 중 자동 설치 시도됨. **CMake는 이제 필요 없음** — 아래 "빌드 중 겪은 문제" 참고). 앱을 실행하면:

1. 최초 실행 시 모델을 자동 다운로드 (진행률 화면에 표시)
2. 다운로드 끝나면 모델 로드 + 시스템 프롬프트 자동 설정
3. 하단 입력창에 명령 입력 (예: "네이버에서 서울 날씨 찾아줘") → 전송 → 생성되는 실행계획 확인

⚠️ **에뮬레이터가 아니라 실기기(arm64)로 테스트할 것** — 아래 "빌드 중 겪은 문제" 참고, 미리 빌드된 라이브러리가 arm64-v8a만 있음.

주의: 모델 파일명(`Qwen_Qwen3-1.7B-Q4_K_M.gguf`)과 URL은 `MainActivity.kt`의 companion object에 상수로 박혀있음. `system_prompt.txt`를 수정하면 `MainActivity.kt`의 `SYSTEM_PROMPT` 상수도 같이 갱신해야 함 (지금은 두 곳에 중복 — 나중에 asset 파일로 통합하는 게 좋을 듯).

### 빌드 중 겪은 문제 (삽질 기록)

- **NDK 라이선스 미동의 에러**: `sdkmanager --licenses` 없이 헤드리스로 빌드하면 라이선스 동의 파일이 없어서 실패함. Android Studio에서 열면 GUI가 알아서 동의 처리해줌 — 터미널로 빌드할 거면 `$SDK/licenses/android-sdk-license`, `android-sdk-preview-license`에 표준 해시를 직접 써넣어야 함
- **CMake가 컴파일러 식별 중 원인불명 크래시 (핵심 이슈, 결국 CMake 자체를 안 쓰는 걸로 우회함)**: CMake로 NDK 안드로이드 툴체인을 configure하면, 컴파일러 식별 직후 cmake.exe 자체가 크래시함 (스택 버퍼 오버런, exit code로 보면 `STATUS_STACK_BUFFER_OVERRUN`). 재현 조건을 찾으려고 여러 변수를 다 바꿔봄 — CMake 버전(3.22.1/3.31.6), NDK 버전(27/29), 언어 선언 개수(C만/CXX만/둘 다), `project()`의 `VERSION` 유무, `add_library`/`add_executable`, Git Bash vs Android Studio 직접 빌드 — **모든 조합에서 크래시가 재현되거나 안 되거나 했고, 심지어 방금 성공했던 것과 완전히 동일한 설정이 바로 다음 시도에서 실패하기도 함**. 즉 CMake 스크립트 내용의 문제가 아니라 **이 PC 환경에서 비결정적으로 발생하는 문제**로 결론 내림 (백신/보안 소프트웨어의 간헐적 프로세스 개입으로 추정 — VC++ 재배포 패키지는 최신이라 그건 아니었고, 학교 관리 PC라 Windows 보안 예외 추가 UI도 막혀있어서 더 확인은 못 함)
  - **최종 해결**: CMake를 아예 포기함. `ai_chat.cpp` 하나뿐이라 **NDK의 `aarch64-linux-android33-clang++`를 직접 호출**해서 `libai-chat.so`를 만듦 (`LLM/build_native.sh`) — 이 방식은 매번 성공적으로 재현됐음 (문제는 CMake.exe 자체에 있었던 것으로 보임, NDK의 clang++ 자체는 멀쩡함). llama.cpp 본체는 STT 때(sherpa-onnx)처럼 **GitHub 릴리스의 미리 빌드된 `.so`를 그대로 링크** — 소스로 빌드하지 않음
  - 결과물 `libai-chat.so`는 수십 KB라 git에 커밋해둠 — **팀원들은 이 삽질을 다시 겪을 필요 없이 `setup_llamademo.sh`만 실행하면 바로 빌드됨** (`ai_chat.cpp`를 수정했을 때만 `build_native.sh`로 재컴파일 필요)
  - 대신 미리 빌드된 라이브러리가 **arm64-v8a(실기기)만 있어서 x86_64 에뮬레이터는 지원 안 함** — 이 프로젝트가 어차피 폰 중심이라 문제 없음
  - 헤더(`.llama.cpp-src`)와 `.so`(GitHub 릴리스)는 정확히 같은 빌드 태그(`b11100`)로 맞춰뒀음(`setup_llamademo.sh`의 `LLAMA_CPP_TAG`). PC 시험용 llama-cli(`setup_llm.sh`)는 별개로 `b11067`이다 — 버전이 어긋나면 ABI 불일치로 이상하게 동작하거나 크래시할 수 있으니 업데이트할 때 주의
- **결론적으로 이 프로젝트는 Android Studio GUI로 여는 게 제일 편함** — 터미널(headless gradlew)로 빌드하면 라이선스/SDK 컴포넌트 자동 설치가 매끄럽지 않은 경우가 있었음

## 다음 할 일

- [x] LlamaDemo 실기기 실행 테스트 (완료)
  - 특히 native 코드가 PC의 llama-cli와 달리 jinja 챗 템플릿을 안 쓰기 때문에(`use_jinja=false`), Qwen3의 thinking 모드 동작이 PC 테스트와 다를 수 있음 — 실행해보고 출력이 이상하면 이 부분부터 의심할 것
- [ ] STT 출력 → LLM 입력 실시간 연결
- [ ] LLM 출력(JSON) → AccessibilityService 실행 모듈 연결
- [ ] 화면 변화/오류 시 재분석 로직 (원래 기획에 있던 기능, 아직 미구현)
- [ ] 네이버·구글·유튜브·인스타그램 새 프롬프트를 Galaxy S25+에서 검증
- [ ] 영상 종료 이벤트 또는 MediaSession 연동 가능성 검증
- [ ] 연속 명령 사용 시 대화 히스토리가 계속 누적됨 (엔진이 매 요청을 이전 대화의 연장으로 처리) — 명령마다 독립적으로 처리하려면 리셋 로직 추가 필요
