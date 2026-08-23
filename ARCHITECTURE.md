# Architecture

## 1. 목표 파이프라인

```text
PPSSPP 화면
  ↓ MediaProjection
ImageReader
  ↓ 500ms throttle
Normalized ROI crop
  ↓
ML Kit Japanese OCR
  ↓ text normalize / unchanged suppression
Overlay subtitle
```

후속 단계에서는 다음으로 확장합니다.

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
  ├─ API provider
  └─ Local LLM provider
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
- 실제 캡처/OCR 로직은 보유하지 않음

### `ScreenOcrService`

- MediaProjection 수명 관리
- Android 14+ 1회 세션 제약 준수
- ImageReader/VirtualDisplay 관리
- 캡처 HandlerThread에서 프레임 처리
- 500ms throttle
- ROI crop
- ML Kit OCR 호출
- OCR 결과 변경 시 자막 오버레이 갱신
- 플로팅 `영역` / `중지` 컨트롤 관리

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
- `AtomicBoolean`: OCR 중복 실행 방지

UI 스레드에서 이미지 변환/OCR을 실행하지 않습니다.

## 4. 성능 원칙

- OCR은 매 프레임 실행하지 않음
- 기본 500ms 간격
- 사용자가 지정한 ROI만 ML Kit에 전달
- OCR 중 다음 OCR 시작 금지
- 동일 OCR 문자열이면 UI 갱신 생략
- 디스크에 캡처 이미지 저장하지 않음
- ML Kit 번들형 일본어 모델을 사용해 첫 실행 다운로드 의존성 제거

## 5. Android 14+ MediaProjection 규칙

- 매 캡처 세션마다 새 사용자 동의
- 동일 projection token 재사용 금지
- `MediaProjection.Callback`을 `createVirtualDisplay()` 전에 등록
- `mediaProjection` foreground service type 사용
- 서비스가 foreground 상태가 된 뒤 `getMediaProjection()` 호출

## 6. 후속 구조 권장

2단계부터 패키지를 다음처럼 분리합니다.

```text
translation/
  TranslationProvider.kt
  ApiTranslationProvider.kt

cache/
  TranslationEntity.kt
  TranslationDao.kt
  AppDatabase.kt

glossary/
  GlossaryEntity.kt
  GlossaryDao.kt

pipeline/
  OcrPipeline.kt
  TextNormalizer.kt
  DuplicateDetector.kt

settings/
  SettingsRepository.kt
```

현재 1단계에서는 과도한 추상화를 피하고, 실제 캡처/OCR 성공을 먼저 검증합니다.
