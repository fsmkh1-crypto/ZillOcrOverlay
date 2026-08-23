# Changelog

## 0.2.3 - 2026-08-23

### Fixed

- HyperOS에서 MediaProjection 시작 시 PPSSPP가 앱 선택 목록에 나타나지 않아 캡처를 시작하지 못하던 흐름을 우회
- Android 14(API 34)+에서 `MediaProjectionConfig.createConfigForDefaultDisplay()`를 사용해 기본 디스플레이 전체 캡처만 요청하도록 변경
- PPSSPP를 시스템 앱 목록에서 직접 선택하는 절차 제거

### Changed

- 시작 흐름을 `번역 캡처 시작 → 전체 화면 캡처 동의 → PPSSPP 전환 → 영역 지정`으로 단순화
- 앱 화면 버전 표기 0.2.3 / versionCode 5

### Compatibility

- Android 13 이하에서는 기존 `createScreenCaptureIntent()` 방식 유지

### Validation

- GitHub Actions `assembleDebug` 검증 예정
- POCO X8 Pro Max + HyperOS에서 전체 화면 캡처 동의창 및 PPSSPP 오버레이 재검증 예정

## 0.2.2 - 2026-08-23

### Fixed

- 같은 대사에서 ML Kit OCR 결과가 프레임마다 미세하게 흔들리며 번역/자막이 반복 갱신되던 현상 완화
- 새 OCR 문장은 유사한 결과가 2회 연속 확인된 뒤에만 확정
- 확정된 문장과 약 86% 이상 유사한 OCR 결과는 같은 대사로 간주해 재번역 억제
- 자막 텍스트가 실제로 바뀌지 않으면 TextView 값을 다시 설정하지 않음
- 자막 위치가 실제로 바뀌지 않으면 `updateViewLayout()`을 호출하지 않음
- OpenRouter가 `content: null` 또는 빈 completion을 반환할 때 화면에 `null`이 표시되던 문제 수정

### Changed

- OpenRouter 빈 응답은 1회 자동 재시도
- OpenRouter 요청에 reasoning 비활성화 옵션 추가
- 빈 응답이 계속되면 실제 응답 모델명과 `finish_reason`을 오류 문구에 포함
- 버전 0.2.2 / versionCode 4

### Trade-off

- 새 대사 확정에 OCR 한 주기(기본 약 500ms)가 추가로 필요할 수 있으나, 자막 안정성을 우선함

### Validation

- GitHub Actions `assembleDebug` 성공
- POCO X8 Pro Max 실기기 깜빡임 재검증 예정

## 0.2.1 - 2026-08-23

### Changed

- 번역 API를 OpenAI 직접 호출에서 OpenRouter로 전환
- 기본 모델을 `openrouter/free`로 변경
- 앱 내 API 키 발급 링크를 OpenRouter 키 페이지로 변경
- OpenRouter 모델 slug를 앱에서 직접 지정 가능

### Added

- 동일 일본어 원문에 대한 실행 중 메모리 LRU 캐시(최대 256개)
- OpenRouter `HTTP-Referer` / `X-Title` 헤더

### Notes

- 무료 계정은 호출 제한이 있으므로 테스트용에 적합함
- `openrouter/free`는 무료 모델을 자동 선택하므로 번역 품질/말투가 호출마다 달라질 수 있음
- 영구 Room 캐시는 아직 미구현

### Validation

- GitHub Actions `assembleDebug` 성공
- POCO X8 Pro Max 실기기 OpenRouter 번역 동작 확인
- 동일 대사에서 자막 깜빡임 현상 확인 → 0.2.2에서 수정

## 0.2.0 - 2026-08-23

### Added

- OpenAI Responses API 기반 일본어 → 한국어 번역
- `TranslationProvider` 추상화
- 앱 내 API 키 및 모델 설정 UI
- 직전 일본어 대사 최대 2개를 짧은 문맥으로 전달
- 번역 요청 전용 단일 백그라운드 executor
- 번역 중 새 OCR 발생 시 오래된 요청을 누적하지 않고 최신 요청 1개만 대기
- `INTERNET` 권한

### Changed

- OCR 결과 대신 한국어 번역문을 기본 오버레이로 표시
- 대화창이 화면 아래쪽에 있을 때 번역 자막을 화면 상단으로 이동
- 결과 자막 폭을 화면 약 74%로 축소해 우측 플로팅 컨트롤과 겹침 완화

### Safety / capture isolation

- 결과 자막, 플로팅 컨트롤, 영역 선택 오버레이에 `FLAG_SECURE` 적용
- 자기 오버레이가 MediaProjection 입력에 다시 잡히는 현상을 억제하도록 변경

### Validation

- 0.2.0 GitHub Actions `assembleDebug` 성공
- debug APK artifact 업로드 성공

## 0.1.0 - 2026-08-23

### Added

- Kotlin Android 프로젝트 생성
- Android 16 `compileSdk/targetSdk 36`
- MediaProjection 화면 캡처 동의 플로우
- MediaProjection Foreground Service
- `SYSTEM_ALERT_WINDOW` 권한 처리
- PPSSPP 위 플로팅 영역 선택/중지 컨트롤
- 드래그 기반 OCR 영역 선택
- SharedPreferences 기반 정규화 ROI 저장
- ImageReader 기반 화면 캡처
- 500ms OCR throttle
- Google ML Kit 일본어 Text Recognition v2 번들 모델
- OCR 결과 오버레이 표시
- 동일 OCR 텍스트 UI 갱신 억제
- README / TODO / ARCHITECTURE / CHANGELOG 문서화
- GitHub Actions 기반 debug APK 자동 빌드 구성

### Validation

- GitHub Actions 실제 `assembleDebug` 성공
- `app-debug.apk` artifact 생성 및 업로드 성공
- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR 실기기 동작 확인
