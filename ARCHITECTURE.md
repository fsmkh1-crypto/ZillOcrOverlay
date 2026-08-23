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

## 2. 0.4.0-alpha2 로컬 벤치 구조

```text
GGUF file
  ↓
LocalModelActivity
  ↓ 최초 1회
LocalModelTester.load()
  ↓
llama-android / llama.cpp CPU+NEON
  ↓ model resident in RAM
Japanese input
  ↓
LocalModelTester.translate()
  ↓
completion only (model 재로딩 없음)
  ↓
Korean text + pure inference time + tok/s
```

### 설정

- TranslateGemma 4B Q4_K_M 우선
- context 1024
- CPU threads 4
- gpuLayers 0
- temperature 0.0 (greedy)
- topP 1.0 / topK 1 / seed 0
- max output 96 tokens

alpha1에서는 매 요청마다 `load → complete → release`였기 때문에 약 9초의 총 시간에 모델 로딩이 섞여 있었습니다. alpha2에서는 모델을 메모리에 유지해 최초 로딩 시간과 순수 추론 시간을 분리합니다.

## 3. 주요 구성

### `LocalModelActivity`

- GGUF 선택/복사
- `모델 메모리 로드` 버튼
- 최초 로딩 시간 표시
- `상주 모델로 테스트 번역` 반복 실행
- 순수 번역시간, prompt eval, generation, tok/s 표시
- 모델 삭제 시 native model release

### `LocalModelTester`

- 단일 `LlamaModel` 인스턴스를 메모리에 보관
- 동일 모델 경로면 재로딩하지 않음
- `Mutex`로 model load / inference / release 직렬화
- deterministic 번역 설정
- 다음 실시간 Provider 설계의 선행 검증용

### `ScreenOcrService`

현재는 기존 OpenRouter 실시간 경로를 유지합니다. alpha2 벤치 결과가 허용 범위면 서비스 생명주기에서 모델을 한 번 로드하는 `LocalTranslationProvider`로 옮깁니다.

## 4. 최종 로컬 실시간 목표

```text
OCR 확정
  ↓
Memory cache
  ↓ miss
Room persistent cache
  ↓ miss
LocalTranslationProvider
  ↓ resident TranslateGemma
Room cache write
  ↓
Korean overlay
```

API는 선택 가능한 백업 Provider로 유지합니다.

## 5. 실기기 현황

POCO X8 Pro Max + TranslateGemma 4B Q4_K_M:

- GGUF 로딩 성공
- 일본어→한국어 번역 성공
- alpha1 총 시간: 8.93초 / 9.49초
- 생성 속도: 8.54 tok/s / 7.60 tok/s
- OOM 없이 반복 실행 성공

alpha2에서 순수 추론 시간을 별도로 측정한 뒤 4B Q4 유지 여부를 결정합니다.

## 6. 성능 원칙

- 전체 화면 OCR 금지
- ROI만 OCR
- 동일/유사 문장 재추론 금지
- Room cache hit 시 LLM 호출 0회
- 로컬 모델은 세션 동안 한 번만 로드
- context 1024부터 시작
- 출력 96 tokens 제한
- reasoning 없음
- CPU thread 수는 PPSSPP 동시 벤치 후 조정
- 후속으로 ROI 픽셀 변화 게이트 추가
