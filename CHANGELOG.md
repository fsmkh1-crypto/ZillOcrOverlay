# Changelog

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

### Known issues

- POCO X8 Pro Max / HyperOS 실기기 동작 검증 필요
- 프레임마다 OCR 시점에 전체 화면 임시 Bitmap을 생성하는 부분은 후속 최적화 필요
- 화면 회전 처리 미구현
- 자기 오버레이가 캡처에 포함되는지 실기기 확인 필요

### Validation

- XML parser validation passed.
- Kotlin parser-level smoke check found no syntax parser errors.
- Full Android build is validated by GitHub Actions.
