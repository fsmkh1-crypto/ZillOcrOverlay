# Changelog

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

- GitHub Actions `assembleDebug` 검증 예정
- POCO X8 Pro Max 실기기 OpenRouter 번역 검증 예정

## 0.2.0 - 2026-08-23

### Added

- OpenAI Responses API 기반 일본어 → 한국어 번역
- `TranslationProvider` 추상화
- 앱 내 API 키 및 모델 설정 UI
- 직전 일본어 대사 최대 2개를 번역 문맥으로 전달
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
