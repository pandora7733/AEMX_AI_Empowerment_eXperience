# Accessibility 검증 기록

## 자동 검사

프로젝트 루트에서 실행한다.

```bash
./gradlew test
./gradlew assembleDebug
./gradlew lint
```

단위 검사 범위:

- Action Plan JSON 형식·순서·크기·지원 앱·지원 action 검증
- 네이버/구글/유튜브 `search` 계약과 URL 인코딩
- `tap`, `input_text`, `scroll`, `wait` 순서와 timeout
- 유튜브 `play_first` 후보 클릭
- `next_video` 선택적 비동기 지연과 scroll-forward
- 잘못된 앱 root 조작 방지, 중단, 중복 계획 거부

## 실기기 검사

서비스는 사용자가 설정에서 직접 켜야 한다. 개발용 검사 APK를 설치한 뒤 다음처럼 시나리오를 지정한다.

```bash
./gradlew :accessibility-device-tests:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r ACCESSIBILITY/device-tests/build/outputs/apk/debug/accessibility-device-tests-debug.apk
adb shell am instrument -w -e scenario naver_weather com.sihoo.aemx.devicechecks/.DeviceChecks
```

`scenario`는 다음 중 하나다.

- `naver_weather`
- `google_news`
- `youtube_search`
- `youtube_next`
- `instagram_next`
- `kyobo_search`
- `aladin_open`
- 생략 시 기본 회귀 검사

외부 앱 결과는 자동 PASS로 단정하지 않는다. `DISPATCHED`는 명령을 보냈다는 뜻이며 검색 결과, 영상 재생, 다음 영상 전환은 사용자가 화면과 AEMX 상태를 직접 확인해야 한다.

## 현재 실기기 미검증

네이버·구글·유튜브·인스타그램 확장은 현재 저장소 코드와 단위 검사 기준 구현 상태다. Galaxy S25+에서 패키지/URI, 노드 이름, 첫 영상 선택, swipe 방향, 광고·팝업 영향을 검증해야 한다. 특히 정확한 영상 종료 이벤트는 접근성 트리만으로 확인되지 않았으므로 구현 완료로 간주하지 않는다.
