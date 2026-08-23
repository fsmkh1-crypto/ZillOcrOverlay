# Architecture

## 1. 현재 0.2.0 파이프라인

```text
PPSSPP 화면
  ↓ MediaProjection
ImageReader
  ↓ 500ms throttle
Normalized ROI crop
  ↓
ML Kit Japanese OCR
  ↓ normalize / unchanged suppression
Context builder (직전 최대 2개)
  ↓
TranslationProvider
  ↓
OpenAiTranslationProvider
  ↓ Responses API
Korean subtitle overlay
```

번역 중 새 OCR 문장이 들어오면 오래된 요청을 계속 큐에 쌓지 않고 **가장 최신 요청 1개만 pending**으로 유지합니다.

후속 3단계 목표는 다음과 같습니다.

```text
OCR
  ↓
Duplicate detector
  ↓
Translation cache ── hit ─→ Overlay
  ↓ miss
Glossary/context builder
  ↓
TranslationProvider
  ├─ OpenAI API provider
  └─ Local LLM provider (4단계)
  ↓
Cache write
  ↓
Overlay
```

## 2. 현재 구성

### `MainActivity`

- 오버레이 권한 요청
- MediaProjection 사용자 동의 요청
- Foreground Service 시작/중지
- API 번역 ON/OFF
- OpenAI API 키 입력
- API 모델명 입력
- 번역 설정 저장
- OpenAI API 키 발급 페이지 바로가기

### `ScreenOcrService`

- MediaProjection 수명 관리
- Android 14+ 1회 세션 제약 준수
- ImageReader/VirtualDisplay 관리
- 캡처 HandlerThread에서 프레임 처리
- 500ms OCR throttle
- ROI crop
- ML Kit OCR 호출
- OCR 결과 변경 감지
- 최근 일본어 대사 문맥 관리
- API 번역 작업 큐 관리
- 번역 결과 자막 오버레이 갱신
- 플로팅 `영역` / `중지` 컨트롤 관리
- 오버레이에 `FLAG_SECURE` 적용

### `TranslationProvider`

- 번역 엔진 교체를 위한 최소 인터페이스
- 4단계 로컬 LLM 구현도 같은 인터페이스에 맞춰 추가 가능

### `OpenAiTranslationProvider`

- OpenAI Responses API 직접 HTTPS 호출
- `store=false`
- 번역문만 반환하도록 짧은 RPG 번역 프롬프트 사용
- 직전 대사 문맥과 현재 OCR 문장을 분리해 전달
- HTTP/API 오류를 호출자에게 전달

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
- 동일 OCR 문자열이면 번역 요청 자체를 시작하지 않음
- 번역 요청이 밀리면 최신 pending 요청만 남김
- 직전 문맥은 최대 2개 문장
- 번역 출력은 최대 220 output tokens
- 디스크에 캡처 이미지 저장하지 않음
- ML Kit 번들형 일본어 모델 사용

## 5. 오버레이 격리

- 결과 자막, 플로팅 컨트롤, 영역 선택 창에 `FLAG_SECURE` 적용
- 목적: 우리 앱 오버레이가 MediaProjection 캡처 결과에 다시 포함되어 OCR되는 현상 억제
- 대화창이 화면 하단에 있으면 자막을 화면 상단에 배치
- 실제 HyperOS 동작은 0.2.0 실기기 검증 필요

## 6. Android 14+ MediaProjection 규칙

- 매 캡처 세션마다 새 사용자 동의
- 동일 projection token 재사용 금지
- `MediaProjection.Callback`을 `createVirtualDisplay()` 전에 등록
- `mediaProjection` foreground service type 사용
- 서비스가 foreground 상태가 된 뒤 `getMediaProjection()` 호출

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

pipeline/              # 필요 시 분리
  TextNormalizer.kt
  DuplicateDetector.kt
```

0.2.0에서는 번역 경로가 실제 POCO + PPSSPP 환경에서 안정적으로 동작하는지 먼저 검증하고, 캐시·용어집·로컬 LLM은 그 이후에 추가합니다.
