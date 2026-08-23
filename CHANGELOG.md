# Changelog

## 0.4.0-alpha3 - 2026-08-23

### Added

- TranslateGemma 4B용 프롬프트 비교 벤치마크
- 기존 RPG 번역 system prompt와 짧은 일본어→한국어 프롬프트를 동일 모델에서 비교
- CPU 4스레드 / 6스레드 모델 로드 및 추론 비교 버튼
- 결과 화면에 사용 스레드와 프롬프트 모드 표시

### Changed

- 로컬 벤치 출력 최대 길이 96 → 64 tokens
- 모델 경로뿐 아니라 스레드 수가 바뀌면 모델을 재로딩하도록 상주 모델 상태 관리 확장
- temperature 0 / topK 1 / seed 0 결정론적 생성 유지
- 버전 0.4.0-alpha3 / versionCode 10

### Validation target

- alpha2 POCO X8 Pro Max 실측: 순수 번역 8.13~8.99초, prompt eval 4.33~5.07초, generate 3.79~3.92초, 8.42~8.71 tok/s
- alpha3에서 짧은 프롬프트가 prompt eval을 줄이는지 확인
- 4스레드 대비 6스레드가 실제 총 지연을 줄이는지 확인

## 0.4.0-alpha2 - 2026-08-23

### Added

- GGUF 모델 상주형 벤치 구조
- `모델 메모리 로드` 버튼 및 최초 로딩 시간 측정
- 상주 모델 재사용 번역
- 순수 번역 시간 / prompt eval / generation / tok/s 표시

### Changed

- TranslateGemma 테스트 출력 최대 96 tokens
- context 1024 / threads 4 유지
- temperature 0.0, topK 1, seed 0으로 deterministic greedy 번역
- 동일 모델 경로는 재로딩하지 않도록 변경
- native 모델 접근을 `Mutex`로 직렬화
- 버전 0.4.0-alpha2 / versionCode 9

### Validation

- alpha1 POCO X8 Pro Max + TranslateGemma 4B Q4_K_M 로딩/번역 성공
- alpha1 총 시간 8.93초 / 9.49초
- alpha1 생성 속도 8.54 tok/s / 7.60 tok/s
- alpha2 POCO 실측: 순수 번역 8.13초 / 8.99초 / 8.77초
- alpha2 prompt eval 4326 / 5072 / 4840 ms
- alpha2 generate 3789 / 3913 / 3918 ms
- alpha2 생성 속도 8.71 / 8.43 / 8.42 tok/s
- 동일 입력 번역문 3회 일치

## 0.4.0-alpha1 - 2026-08-23

### Added

- Android용 사전 빌드 llama.cpp AAR (`llama-android`) 통합
- 로컬 GGUF 파일 선택 UI
- 선택 GGUF를 앱 전용 `models/local-model.gguf`로 복사
- `LocalModelActivity` 독립 벤치마크 화면
- `LocalModelTester` 일본어→한국어 단독 추론
- context 1024 / CPU threads 4 / maxTokens 128 초기 설정
- 총 처리시간 및 생성 속도(tok/s) 표시
- OOM 오류 안내 및 앱 내부 모델 삭제 기능
- `TranslationSettingsStore`에 engine/localModelPath 예약 필드 추가

### Design

- 안정적으로 동작 중인 API 실시간 경로와 로컬 벤치마크를 분리
- 4B Q4가 POCO X8 Pro Max에서 실사용 가능한지 측정한 뒤에만 PPSSPP 실시간 파이프라인에 연결
- 최종 실시간 구조에서는 모델을 문장마다 재로딩하지 않고 서비스 생명주기 동안 상주시킬 예정

### Validation

- llama.cpp/ggml Android arm64 네이티브 라이브러리 패키징 확인
- 최초 CI에서 `tokensPerSecond` Float/Double 타입 불일치 발견 후 수정
- GitHub Actions `assembleDebug` 성공
- TranslateGemma 4B Q4_K_M POCO 실기기 번역 성공

## 0.3.1 - 2026-08-23

### Fixed

- `openrouter/free`가 reasoning 필수 모델로 라우팅될 때 `reasoning=false` 때문에 요청이 거절되던 문제 수정
- OpenRouter 요청에서 reasoning 강제 비활성화 옵션 제거

### Validation

- GitHub Actions `assembleDebug` 성공

## 0.3.0 - 2026-08-23

### Added

- Room 2.8.4 + KSP 기반 영구 번역 캐시
- 원문, 번역문, 사용 모델, 최초 생성일, 마지막 사용일, 사용 횟수 저장
- 앱 종료/재실행 후에도 동일 일본어 원문 번역 재사용
- 사용자 편집 용어집 CRUD UI
- `ロストール → 로스토르`, `ソウル → 소울`, `インフィニティア → 인피니티아` 최초 기본 시드
- 현재 대사/직전 문맥에 실제 포함된 용어만 프롬프트에 주입
- 최근 번역 기록 최대 30개 조회
- 전체 번역 캐시 삭제 기능

### Changed

- 번역 경로를 `메모리 캐시 → Room 영구 캐시 → OpenRouter` 순서로 변경
- 용어 추가/수정/삭제 시 해당 일본어 용어가 포함된 기존 번역 캐시만 선택 무효화
- 앱 설정 화면을 ScrollView로 변경
- 버전 0.3.0 / versionCode 6

### Validation

- Room/KSP 포함 GitHub Actions `assembleDebug` 성공
- debug APK artifact 업로드 성공
- POCO X8 Pro Max 실기기 API 번역 동작 확인

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

- GitHub Actions `assembleDebug` 성공
- POCO X8 Pro Max + HyperOS 실기기 확인 진행

## 0.2.2 - 2026-08-23

### Fixed

- 같은 대사에서 ML Kit OCR 결과가 프레임마다 미세하게 흔들리며 번역/자막이 반복 갱신되던 현상 완화
- 새 OCR 문장은 유사한 결과가 2회 연속 확인된 뒤에만 확정
- 확정된 문장과 약 86% 이상 유사한 OCR 결과는 같은 대사로 간주해 재번역 억제
- 자막 텍스트/위치가 실제로 바뀌지 않으면 불필요한 View 갱신 억제
- OpenRouter `content: null`/빈 completion 처리

### Changed

- OpenRouter 빈 응답 1회 자동 재시도
- 버전 0.2.2 / versionCode 4

### Validation

- GitHub Actions `assembleDebug` 성공

## 0.2.1 - 2026-08-23

### Changed

- 번역 API를 OpenAI 직접 호출에서 OpenRouter로 전환
- 기본 모델을 `openrouter/free`로 변경
- OpenRouter 모델 slug를 앱에서 직접 지정 가능

### Added

- 실행 중 메모리 LRU 캐시 최대 256개
- OpenRouter `HTTP-Referer` / `X-Title` 헤더

### Validation

- GitHub Actions `assembleDebug` 성공
- POCO X8 Pro Max 실기기 OpenRouter 번역 동작 확인

## 0.2.0 - 2026-08-23

### Added

- API 번역 파이프라인
- `TranslationProvider` 추상화
- 앱 내 API 키/모델 설정 UI
- 직전 일본어 대사 최대 2개 문맥 전달
- 번역 요청 전용 백그라운드 executor
- `INTERNET` 권한

### Changed

- 한국어 번역문을 기본 오버레이로 표시
- 번역 자막을 OCR ROI 바깥에 배치
- 오버레이에 `FLAG_SECURE` 적용

### Validation

- GitHub Actions `assembleDebug` 성공

## 0.1.0 - 2026-08-23

### Added

- Kotlin Android 프로젝트 생성
- Android 16 `compileSdk/targetSdk 36`
- MediaProjection 화면 캡처
- Foreground Service
- 오버레이 권한
- 드래그 OCR 영역 선택
- 500ms OCR throttle
- Google ML Kit 일본어 OCR
- GitHub Actions debug APK 자동 빌드

### Validation

- GitHub Actions 실제 `assembleDebug` 성공
- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR 실기기 동작 확인
