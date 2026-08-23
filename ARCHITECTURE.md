# Architecture

## 1. 현재 0.2.3 파이프라인

```text
PPSSPP 화면
  ↓ Android 14+: default display MediaProjection
ImageReader
  ↓ 500ms throttle
Normalized ROI crop
  ↓
ML Kit Japanese OCR
  ↓ 2-hit confirmation + similarity suppression
Context builder (직전 최대 2개)
  ↓
TranslationProvider
  ↓
OpenRouter Chat Completions API
  ↓ memory LRU cache
Korean subtitle overlay
```

번역 중 새 OCR 문장이 들어오면 오래된 요청을 계속 큐에 쌓지 않고 **가장 최신 요청 1개만 pending**으로 유지합니다.

## 2. 현재 구성

### `MainActivity`

- 오버레이 권한 요청
- MediaProjection 사용자 동의 요청
- Android 14(API 34)+에서 `MediaProjectionConfig.createConfigForDefaultDisplay()` 사용
- Android 13 이하에서는 기존 `createScreenCaptureIntent()` 사용
- Foreground Service 시작/중지
- OpenRouter API 번역 ON/OFF
- OpenRouter API 키 입력
- 모델 slug 입력
- 번역 설정 저장

### `ScreenOcrService`

- MediaProjection 수명 관리
- Android 14+ 1회 세션 제약 준수
- ImageReader/VirtualDisplay 관리
- 캡처 HandlerThread에서 프레임 처리
- 500ms OCR throttle
- ROI crop
- ML Kit OCR 호출
- 새 OCR 2회 유사 확인 후 확정
- 확정 OCR과 약 86% 이상 유사한 결과 재처리 억제
- 최근 일본어 대사 문맥 관리
- API 번역 작업 큐 관리
- 번역 결과 자막 오버레이 갱신
- 동일 자막/동일 위치 불필요 갱신 억제
- 플로팅 `영역` / `중지` 컨트롤 관리
- 오버레이에 `FLAG_SECURE` 적용

### `TranslationProvider`

- 번역 엔진 교체를 위한 최소 인터페이스
- 4단계 로컬 LLM 구현도 같은 인터페이스에 맞춰 추가 가능

### `OpenAiTranslationProvider`

- 현재 이름은 과거 호환을 위해 유지하지만 실제 구현은 OpenRouter Chat Completions API
- 기본 모델 `openrouter/free`
- 동일 일본어 원문 최대 256개 실행 중 LRU 캐시
- 빈/null 응답 1회 자동 재시도
- reasoning 비활성화 요청
- 직전 대사 문맥과 현재 OCR 문장을 분리해 전달

### `TranslationSettingsStore`

- API 번역 ON/OFF
- API 키
- 모델명
- 현재는 앱 private SharedPreferences 사용
- 후속 Android Keystore 적용 예정

### `RegionSelectionView`

- PPSSPP 위 전체 화면 투명 오버레이
- 사용자의 드래그 영역을 `RectF(0..1)` 정규화 좌표로 반환

### `RegionStore`

- 선택 영역을 SharedPreferences에 저장
- 해상도 변화에 덜 민감하도록 픽셀이 아니라 정규화 좌표 사용

## 3. 스레딩

- Main thread: Android UI 및 WindowManager overlay 조작
- `zill-ocr-capture` HandlerThread: ImageReader 콜백, bitmap crop
- ML Kit: `process()` 비동기 작업
- Translation executor: API 네트워크 요청 전용 단일 스레드
- `AtomicBoolean`: OCR 중복 실행 방지

UI 스레드에서 이미지 변환/OCR/API 네트워크 요청을 실행하지 않습니다.

## 4. 성능 원칙

- OCR은 매 프레임 실행하지 않음
- 기본 500ms 간격
- 사용자가 지정한 ROI만 ML Kit에 전달
- OCR 중 다음 OCR 시작 금지
- 동일/유사 OCR이면 번역 요청 시작 금지
- 새 OCR은 2회 확인 후 확정
- 번역 요청이 밀리면 최신 pending 요청만 남김
- 직전 문맥은 최대 2개 문장
- 번역 출력은 최대 220 tokens
- 디스크에 캡처 이미지 저장하지 않음
- ML Kit 번들형 일본어 모델 사용

## 5. 오버레이 격리

- 결과 자막, 플로팅 컨트롤, 영역 선택 창에 `FLAG_SECURE` 적용
- 목적: 우리 앱 오버레이가 MediaProjection 캡처 결과에 다시 포함되어 OCR되는 현상 억제
- 대화창이 화면 하단에 있으면 자막을 화면 상단에 배치

## 6. Android 14+ MediaProjection 규칙

- 매 캡처 세션마다 새 사용자 동의
- 동일 projection token 재사용 금지
- `MediaProjection.Callback`을 `createVirtualDisplay()` 전에 등록
- `mediaProjection` foreground service type 사용
- 서비스가 foreground 상태가 된 뒤 `getMediaProjection()` 호출
- 0.2.3부터 `MediaProjectionConfig.createConfigForDefaultDisplay()`로 전체 기본 디스플레이 캡처만 요청하여 특정 앱 선택 목록에 의존하지 않음

## 7. 다음 구조

```text
translation/
  TranslationProvider.kt
  OpenAiTranslationProvider.kt

data/
  RegionStore.kt
  TranslationSettingsStore.kt

cache/                 # 3단계
  TranslationEntity.kt
  TranslationDao.kt
  AppDatabase.kt

glossary/              # 3단계
  GlossaryEntity.kt
  GlossaryDao.kt
```

0.2.3 실기기에서 HyperOS 전체 화면 캡처 시작과 자막 안정성을 확인한 뒤 3단계 영구 캐시·용어집으로 진행합니다.
