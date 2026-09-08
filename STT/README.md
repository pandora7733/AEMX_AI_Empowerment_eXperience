# STT (Speech-to-Text) 파트

담당: 배재준

## 상태

sherpa-onnx의 실시간 스트리밍 한국어 음성 인식(`sherpa-onnx-streaming-zipformer-korean-2024-06-16`)이 독립 실행형 Android 예제 프로젝트(`SherpaOnnx/`)에서 정상 동작 확인 완료. 실기기 테스트 결과 한국어 짧은 문장 인식 잘 됨.

아직 메인 `app` 모듈(`com.sihoo.aemx`)에는 통합 안 함 — 다른 파트(TTS/UI/LLM)와 합칠 때 팀에서 같이 진행 예정.

## 사용한 것

| 항목 | 내용 |
|---|---|
| 엔진 | sherpa-onnx (온디바이스, 스트리밍) |
| 모델 | `sherpa-onnx-streaming-zipformer-korean-2024-06-16` (Zipformer, int8 경량화 mobile 버전) |
| 방식 | Android AudioRecord로 마이크 입력 → OnlineRecognizer로 실시간 디코딩 |

## 기능 명세서

### 개요

마이크로 들어오는 한국어 음성을 실시간(스트리밍)으로 텍스트로 변환한다. 네트워크 없이 기기 안에서만 동작한다(온디바이스).

### 입력 / 출력

| 구분 | 내용 |
|---|---|
| 입력 | 16kHz, mono, 16-bit PCM 오디오 샘플 (`AudioRecord`에서 100ms(1600 sample) 단위 청크로 공급) |
| 출력 | `OnlineRecognizerResult` — `text: String`(인식된 문장), `tokens: Array<String>`, `timestamps: FloatArray`, `ysProbs: FloatArray` |
| 권한 | `android.permission.RECORD_AUDIO` (런타임 권한 요청 필요) |

### 핵심 API (`OnlineRecognizer.kt`, `OnlineStream.kt`)

다른 파트에서 STT 결과를 가져다 쓰려면 아래 흐름을 그대로 따르면 된다 (`MainActivity.processSamples()`가 실제 사용 예시).

```kotlin
// 1. 앱 시작 시 한 번만 생성 (모델 로딩 비용이 있으므로 재사용)
val recognizer = OnlineRecognizer(
    assetManager = context.assets,
    config = OnlineRecognizerConfig(
        featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = getModelConfig(type = 14)!!,   // 14 = 한국어 zipformer
        endpointConfig = getEndpointConfig(),
        enableEndpoint = true,
    )
)

// 2. 발화(하나의 세션)마다 스트림 생성
val stream = recognizer.createStream()

// 3. 오디오 청크가 들어올 때마다 공급하고 디코딩
stream.acceptWaveform(samples: FloatArray, sampleRate = 16000)
while (recognizer.isReady(stream)) {
    recognizer.decode(stream)
}

// 4. 현재까지 인식된 텍스트 확인
val text = recognizer.getResult(stream).text

// 5. 침묵 등으로 문장이 끝났는지 확인 → 끝났으면 스트림 리셋
if (recognizer.isEndpoint(stream)) {
    recognizer.reset(stream)   // 다음 문장을 위해 상태 초기화
}

// 6. 완전히 끝나면 스트림 해제
stream.release()
```

### 모델 / 설정

| 항목 | 값 |
|---|---|
| 모델 | `sherpa-onnx-streaming-zipformer-korean-2024-06-16` (Zipformer transducer, int8 양자화) |
| 모델 타입 코드 | `getModelConfig(type = 14)` (`OnlineRecognizer.kt` 참고) |
| 필요 에셋 파일 | `encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt` |

### Endpoint(발화 끝) 감지 규칙

`getEndpointConfig()` 기준, 아래 셋 중 하나라도 만족하면 "문장이 끝났다"고 판단한다.

| 규칙 | 조건 |
|---|---|
| rule1 | 무음 2.4초 이상 지속 (발화 내용 유무 무관) |
| rule2 | 발화가 있었고, 그 뒤 무음 1.4초 이상 지속 |
| rule3 | 발화 길이가 20초를 초과 |

필요 시 `EndpointRule(mustContainNonSilence, minTrailingSilence, minUtteranceLength)` 값을 조정해서 문장 끊김 민감도를 바꿀 수 있다.

### 성능 / 제약

- 완전 온디바이스 처리 — 네트워크 불필요, 인터넷 끊겨도 동작
- 모델 로딩(최초 `OnlineRecognizer` 생성) 시 약간의 지연 있음 → 앱 시작 시 미리 초기화해두는 걸 권장
- int8 경량화 모델이라 정확도와 속도의 균형을 맞춘 버전 (실기기 테스트에서 짧은 문장 인식 정상 확인)
- 네이티브 라이브러리(.so)는 `arm64-v8a`(실기기), `x86_64`(에뮬레이터)만 받아둠 — 32비트 기기 지원 필요하면 `armeabi-v7a`, `x86`도 `setup_assets.sh`에서 추가로 받아야 함

## 폴더 구조

```
STT/
├── README.md          (이 파일)
├── setup_assets.sh     (대용량 모델/네이티브 라이브러리 다운로드 스크립트)
└── SherpaOnnx/          (독립 실행 가능한 Android 예제 프로젝트)
    └── app/src/main/java/com/k2fsa/sherpa/onnx/
        ├── MainActivity.kt        (녹음 버튼 UI + 실시간 인식 로직)
        └── OnlineRecognizer.kt 등  (sherpa-onnx Kotlin API, 모델 설정)
```

## 실행 방법

1. **대용량 에셋(모델 + .so) 다운로드** — git에는 안 올렸음 (encoder 파일이 106MB로 GitHub 100MB 제한 초과, .so 파일도 무거워서 스크립트로 분리)

   ```bash
   cd STT
   bash setup_assets.sh
   ```

   완료되면 아래 경로에 파일이 채워짐:
   - `SherpaOnnx/app/src/main/assets/sherpa-onnx-streaming-zipformer-korean-2024-06-16/*.onnx`
   - `SherpaOnnx/app/src/main/jniLibs/{x86_64,arm64-v8a}/*.so`

2. Android Studio에서 `STT/SherpaOnnx` 폴더를 프로젝트로 열기
3. Gradle sync 대기 후 에뮬레이터 또는 실기기로 Run

## 삽질 기록 (다음 사람을 위해)

- **Gradle JDK 버전 충돌**: Android Studio 내장 JBR이 JDK 25인데 이 프로젝트의 Gradle 8.2는 JDK 17까지만 지원함 → `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 Gradle JDK를 17로 다운로드해서 지정해야 함
- **한글 경로 에러**: 저장소 경로에 한글이 섞여 있으면 (`android.overridePathCheck` 관련) AGP가 빌드를 거부함 → `gradle.properties`에 `android.overridePathCheck=true` 추가로 우회 (네이티브 코드를 직접 컴파일하는 게 아니라 미리 빌드된 `.so`를 쓰기 때문에 실제로는 문제 없음)
- **git symlink 깨짐**: sherpa-onnx 저장소의 `kotlin-api/*.kt` 파일들은 원래 심볼릭 링크인데, Windows에서 git clone하면 심볼릭 링크가 아니라 "경로 문자열이 담긴 텍스트 파일"로 받아짐 → 실제 내용으로 직접 교체함 (이미 이 저장소의 파일들은 정상 내용으로 커밋되어 있어서 다시 겪을 일은 없음)
- **`libsherpa-onnx-jni.so` not found 크래시**: 네이티브 라이브러리는 원래 NDK로 소스 빌드하거나(`build-android-*.sh`, 시간 오래 걸림) GitHub 릴리스의 미리 빌드된 아카이브(`sherpa-onnx-v1.13.7-android.tar.bz2`)를 받아서 `jniLibs/`에 넣어야 함 — `setup_assets.sh`가 이 과정을 자동화함

## 다음 할 일

- [ ] TTS 없이도 확인 가능한 recognizer 정확도 추가 테스트 (다양한 문장, 소음 환경)
- [ ] 메인 `app` 모듈에 통합 (다른 파트 완성 후 팀 논의)
- [ ] endpoint(문장 끊김) 감지 파라미터 튜닝 필요한지 확인
