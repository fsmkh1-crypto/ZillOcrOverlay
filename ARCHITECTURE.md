# Architecture

## 1. 기존 실시간 파이프라인

```text
PPSSPP 화면
  ↓ MediaProjection
ImageReader
  ↓ 500ms throttle
Normalized ROI crop
  ↓
ML Kit Japanese OCR
  ↓ 2-hit confirmation + similarity suppression
Context builder (직전 최대 2개)
  ↓
Memory LRU cache
  ↓ miss
Room persistent cache
  ↓ miss
Relevant glossary terms
  ↓
OpenRouter Chat Completions API
  ↓
Room cache write
  ↓
Korean subtitle overlay
```

번역 중 새 OCR 문장이 들어오면 오래된 요청을 계속 쌓지 않고 가장 최신 요청 1개만 pending으로 유지합니다.

## 2. 0.4.0-alpha1 로컬 모델 검증 구조

실시간 서비스에 바로 로컬 모델을 붙이지 않고 독립된 벤치마크 경로를 추가했습니다.

```text
Android Storage Access Framework
  ↓ 사용자 GGUF 선택
LocalModelActivity
  ↓ 1MB buffer copy
app external files/models/local-model.gguf
  ↓
LocalModelTester
  ↓
llama-android prebuilt AAR
  ↓ CPU/NEON llama.cpp native libs
GGUF model load
  ↓ context=1024 / threads=4
Japanese → Korean generation
  ↓ maxTokens=128
translation + elapsed time + tok/s
```

이 알파의 목적은 POCO X8 Pro Max에서 4B Q4 모델이 실제로 실행되는지 먼저 검증하는 것입니다. 최종 실시간 구조에서는 매 문장마다 모델을 로드/해제하지 않고 서비스 생명주기 동안 한 번 로드해 상주시킬 예정입니다.

## 3. 주요 구성

### `MainActivity`

- 기존 API 기반 실시간 OCR/번역 UI
- 오버레이/MediaProjection 권한 흐름
- OpenRouter API 키/모델 설정
- 용어집 및 번역 캐시 관리

### `LocalModelActivity`

- 0.4.0-alpha1 전용 로컬 모델 벤치마크 UI
- `.gguf` 문서 선택
- content Uri를 앱 모델 폴더로 복사
- 선택 모델 경로 영구 저장
- 일본어 테스트 문장 입력
- 로컬 번역 실행
- 총 지연시간 및 tok/s 표시
- 앱 내부 모델 삭제

### `LocalModelTester`

- `llama-android` AAR Kotlin API 사용
- 초기 설정: context 1024, threads 4, maxTokens 128
- 일본 판타지 RPG용 일본어→한국어 번역 system prompt
- 현재 알파에서는 테스트마다 모델 load → complete → release

### `ScreenOcrService`

- MediaProjection / ImageReader / VirtualDisplay 수명 관리
- 500ms OCR throttle
- 지정 ROI crop + ML Kit 일본어 OCR
- OCR 2회 안정화 및 유사 결과 억제
- 현재 0.4.0-alpha1에서도 실시간 경로는 기존 OpenRouter provider를 사용

### `OpenAiTranslationProvider`

이름은 과거 호환을 위해 유지하지만 실제 API는 OpenRouter입니다.

- 메모리 LRU 캐시 최대 256개
- Room 영구 캐시 조회/저장
- 용어집 프롬프트 적용
- `openrouter/free` 기본 모델
- 빈/null 응답 처리

### `TranslationSettingsStore`

- 기존 API 설정
- `engine`: `api` / `local` 예약 필드
- `localModelPath`: 앱 내부 GGUF 파일 경로
- 실시간 서비스의 engine 전환은 다음 알파에서 연결 예정

### Room DB

```text
db/
  AppDatabase.kt
  TranslationEntity.kt
  TranslationDao.kt
  GlossaryEntity.kt
  GlossaryDao.kt
```

기존 번역 캐시와 용어집 구조는 그대로 유지합니다. 로컬 모델이 실시간에 연결되더라도 Room 캐시를 모델보다 먼저 확인하여 반복 대사의 추론 비용을 없애는 구조를 유지합니다.

## 4. 스레딩

- Main thread: Android UI / WindowManager
- `zill-ocr-capture` HandlerThread: ImageReader 및 Bitmap 처리
- ML Kit: 비동기 OCR
- Translation executor: Room + API 번역
- `LocalModelActivity`: `lifecycleScope` + `Dispatchers.IO`에서 모델 복사/로컬 추론

모델 로딩 및 추론을 UI 스레드에서 실행하지 않습니다.

## 5. 최종 로컬 실시간 목표 구조

```text
OCR 확정
  ↓
Memory cache
  ↓ miss
Room persistent cache
  ↓ miss
LocalTranslationProvider (model already resident)
  ↓
Room cache write
  ↓
Korean overlay
```

최종 버전에서 API는 선택 가능한 백업 provider로 유지합니다.

## 6. 성능 원칙

- 전체 화면 OCR 금지
- 선택 ROI만 ML Kit 입력
- 동일/유사 OCR은 추론하지 않음
- Room hit 시 LLM 추론 0회
- 로컬 모델은 실시간 모드에서 한 번만 로드
- context는 우선 1024로 제한
- 번역 출력은 짧게 제한
- CPU thread 수는 PPSSPP 프레임과 함께 벤치마크 후 결정
- 다음 최적화로 ROI 저해상도 픽셀 변화 게이트를 추가해 정지 화면에서는 OCR 자체를 생략

## 7. 0.4.0-alpha1 판단 기준

TranslateGemma 4B Q4_K_M 실기기 테스트에서 다음을 확인합니다.

- 모델 로딩 성공 여부
- 일본어→한국어 번역 품질
- 총 첫 실행 지연
- 생성 tok/s
- 앱 강제종료/OOM 여부
- 발열 체감

이 결과가 허용 범위면 다음 알파에서 `LocalTranslationProvider`를 구현하고 PPSSPP 실시간 경로와 연결합니다. 너무 느리거나 메모리 부담이 크면 Q3/Q2 또는 2~3B 모델로 하향 비교합니다.
