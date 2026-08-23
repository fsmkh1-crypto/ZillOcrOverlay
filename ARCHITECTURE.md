# Architecture

## 1. 현재 0.3.0 파이프라인

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

## 2. 주요 구성

### `MainActivity`

- 오버레이/MediaProjection 권한 흐름
- Android 14+ 기본 디스플레이 전체 캡처 요청
- OpenRouter API 키/모델 설정
- 용어집 관리 UI
- 최근 번역 기록/캐시 조회
- 전체 번역 캐시 삭제
- Room 작업은 전용 DB executor에서 실행

### `ScreenOcrService`

- MediaProjection / ImageReader / VirtualDisplay 수명 관리
- 500ms OCR throttle
- 지정 ROI crop + ML Kit 일본어 OCR
- OCR 2회 안정화 및 유사 결과 억제
- 최근 일본어 문맥 관리
- 번역 작업 큐 관리
- 자막/플로팅 오버레이 관리

### `OpenAiTranslationProvider`

이름은 과거 호환을 위해 유지하지만 실제 API는 OpenRouter입니다.

- 메모리 LRU 캐시 최대 256개
- Room 영구 캐시 조회/저장
- 캐시 hit 시 네트워크 호출 없이 반환
- 현재 문장/직전 문맥에 실제 포함된 용어집 항목만 프롬프트에 삽입
- `openrouter/free` 기본 모델
- reasoning 비활성화
- 빈/null 응답 1회 자동 재시도

### Room DB

```text
db/
  AppDatabase.kt
  TranslationEntity.kt
  TranslationDao.kt
  GlossaryEntity.kt
  GlossaryDao.kt
```

`TranslationEntity`
- `sourceText` primary key
- `translatedText`
- `model`
- `createdAt`
- `lastUsedAt`
- `useCount`

`GlossaryEntity`
- `sourceTerm` primary key
- `targetTerm`
- `updatedAt`

용어를 변경하면 `instr(sourceText, term) > 0` 조건으로 해당 용어가 포함된 번역 캐시만 삭제합니다.

### `TranslationSettingsStore`

- API 번역 ON/OFF
- OpenRouter API 키
- 모델 slug
- private SharedPreferences
- `AppContextHolder` 초기화

## 3. 스레딩

- Main thread: Android UI / WindowManager
- `zill-ocr-capture` HandlerThread: ImageReader 및 Bitmap 처리
- ML Kit: 비동기 OCR
- Translation executor: Room cache 조회 + OpenRouter 네트워크 + Room 저장
- MainActivity DB executor: 용어집/기록 관리

UI 스레드에서 OCR, 네트워크, Room query를 실행하지 않습니다.

## 4. 캐시 정책

1. 메모리 LRU hit → 즉시 반환
2. Room hit → `lastUsedAt`, `useCount` 갱신 후 반환
3. miss → OpenRouter 번역
4. 성공 결과를 Room + 메모리 캐시에 저장

모델을 변경해도 원문이 같으면 기존 영구 캐시를 우선 사용합니다. 모델별 재번역이 필요하면 사용자가 캐시를 삭제합니다.

## 5. 용어집 정책

- 최초 DB가 비어 있으면 기본 3개 용어 시드
- 사용자가 추가/수정/삭제 가능
- 현재 대사 또는 직전 2개 문맥에 포함된 용어만 최대 80개 프롬프트에 전달
- 용어 변경 시 관련 원문 캐시만 선택 무효화

## 6. 성능 원칙

- 전체 화면 OCR 금지, 선택 ROI만 ML Kit 입력
- OCR은 기본 500ms 간격
- OCR 중복 실행 금지
- 동일/유사 OCR 재번역 억제
- 캐시 hit 시 API 호출 0회
- 직전 문맥 최대 2개
- 출력 최대 220 tokens
- 이미지 파일 저장 금지

## 7. Android 14+ MediaProjection

- 매 캡처 세션마다 새 사용자 동의
- projection token 재사용 금지
- Callback 등록 후 VirtualDisplay 생성
- `mediaProjection` foreground service type
- `MediaProjectionConfig.createConfigForDefaultDisplay()`로 특정 앱 목록에 의존하지 않는 전체 디스플레이 캡처 요청

## 8. 다음 단계

```text
translation/
  TranslationProvider.kt
  OpenAiTranslationProvider.kt
  LocalLlmTranslationProvider.kt   # 4단계

local/
  ModelManager.kt
  LocalInferenceRuntime.kt
```

0.3.0 실기기에서 영구 캐시 적중률을 측정한 뒤, 특정 OpenRouter 모델 품질 비교와 로컬 3~4B Q4 모델 벤치마크로 진행합니다.
