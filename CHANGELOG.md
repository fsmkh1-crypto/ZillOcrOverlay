# Changelog

## 0.2.0 - 2026-08-23

### Added

- OpenAI Responses API 기반 일본어 → 한국어 번역
- `TranslationProvider` 추상화
- 기본 API 모델 `gpt-5.6-luna`
- 앱 내 API 키 및 모델 설정 UI
- 직전 일본어 대사 최대 2개를 번역 문맥으로 전달
- 번역 요청 전용 단일 백그라운드 executor
- 번역 중 새 OCR 발생 시 오래된 요청을 누적하지 않고 최신 요청 1개만 대기
- `INTERNET` 권한
- 앱에서 OpenAI API 키 발급 페이지 바로가기

### Changed

- 버전 0.2.0 / versionCode 2
- OCR 결과 대신 한국어 번역문을 기본 오버레이로 표시
- 대화창이 화면 아래쪽에 있을 때 번역 자막을 화면 상단으로 이동
- 결과 자막 폭을 화면 약 74%로 축소해 우측 플로팅 컨트롤과 겹침 완화
- 캡처 알림 문구를 OCR/번역 기준으로 변경

### Safety / capture isolation

- 결과 자막, 플로팅 컨트롤, 영역 선택 오버레이에 `FLAG_SECURE` 적용
- 자기 오버레이가 MediaProjection 입력에 다시 잡히는 현상을 억제하도록 변경
- API 응답은 `store=false`로 요청

### Known issues

- API 키는 현재 앱 private SharedPreferences에 저장하며 Android Keystore 암호화는 아직 미적용
- HyperOS에서 `FLAG_SECURE`가 MediaProjection 재캡처를 완전히 차단하는지 실기기 검증 필요
- 네트워크 오류 자동 재시도 미구현
- 번역 캐시 및 사용자 편집 용어집 미구현
- 프레임 OCR 시 전체 화면 임시 Bitmap 할당은 기존과 동일

### Validation

- 0.2.0 GitHub Actions `assembleDebug` 성공
- debug APK artifact 업로드 성공
- POCO X8 Pro Max 실기기 번역 검증 예정

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

### Fixed

- Kotlin 2.3 legacy JVM target 설정 수정
- compileSdk 36과 맞는 AndroidX 버전으로 고정
- nullable MediaProjection 컴파일 오류 수정

### Validation

- GitHub Actions 실제 `assembleDebug` 성공
- `app-debug.apk` artifact 생성 및 업로드 성공
- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR 실기기 동작 확인
