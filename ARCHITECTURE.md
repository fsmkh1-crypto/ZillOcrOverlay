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

## 2. 0.4.0-alpha3 로컬 벤치 구조

```text
GGUF file
  ↓
LocalModelActivity
  ↓ 4-thread 또는 6-thread 선택
LocalModelTester.load(path, threads)
  ↓
llama-android / llama.cpp CPU+NEON
  ↓ resident model in RAM
Japanese input
  ↓
PromptMode.BASELINE 또는 PromptMode.COMPACT
  ↓
LocalModelTester.translate()
  ↓
Korean text + pure inference time + prompt eval + generate + tok/s
```

### 공통 설정

- TranslateGemma 4B Q4_K_M 우선
- context 1024
- gpuLayers 0
- temperature 0.0
- topP 1.0 / topK 1 / seed 0
- max output 64 tokens

### alpha3 비교축

1. CPU threads 4 vs 6
2. 기존 RPG 번역 system prompt vs 짧은 일본어→한국어 prompt

스레드 수가 바뀌면 모델을 해당 설정으로 다시 로딩하고, 같은 경로·같은 스레드 설정이면 상주 모델을 그대로 재사용합니다.

짧은 프롬프트는 TranslateGemma 공식 Hugging Face 구조를 완전히 구현한 것이 아니라, 현재 `llama-android`의 문자열 기반 completion API에서 입력 프롬프트 오버헤드를 줄일 수 있는지 확인하기 위한 실험입니다.

## 3. 주요 구성

### `LocalModelActivity`

- GGUF 선택/복사
- 4스레드 / 6스레드 모델 메모리 로드
- 기존 프롬프트 / 짧은 프롬프트 번역 버튼
- 순수 번역시간, prompt eval, generation, tok/s, 현재 설정 표시
- 모델 삭제 시 native model release

### `LocalModelTester`

- 단일 `LlamaModel` 인스턴스를 메모리에 보관
- 모델 경로와 스레드 수를 함께 상주 상태 키로 사용
- `Mutex`로 model load / inference / release 직렬화
- `PromptMode.BASELINE` / `PromptMode.COMPACT`
- deterministic greedy 번역 설정

### `ScreenOcrService`

현재는 기존 OpenRouter 실시간 경로를 유지합니다. alpha3 결과가 허용 범위면 서비스 생명주기에서 모델을 한 번 로드하는 `LocalTranslationProvider`로 옮깁니다.

## 4. 최종 로컬 실시간 목표

```text
OCR 확정
  ↓
Memory cache
  ↓ miss
Room persistent cache
  ↓ miss
LocalTranslationProvider
  ↓ resident local model
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
- alpha2 상주 순수 번역: 8.13초 / 8.99초 / 8.77초
- alpha2 prompt eval: 4326 / 5072 / 4840 ms
- alpha2 generate: 3789 / 3913 / 3918 ms
- alpha2 tok/s: 8.71 / 8.43 / 8.42
- 동일 입력 번역문 3회 일치
- OOM 없이 반복 실행 성공

현재 병목은 모델 로딩이 아니라 prompt eval과 generation입니다.

## 6. 성능 원칙

- 전체 화면 OCR 금지
- ROI만 OCR
- 동일/유사 문장 재추론 금지
- Room cache hit 시 LLM 호출 0회
- 로컬 모델은 세션 동안 한 번만 로드
- context 1024부터 시작
- 출력 길이 최소화
- reasoning 없음
- CPU thread 수는 PPSSPP 동시 벤치 후 결정
- 후속으로 ROI 픽셀 변화 게이트 추가
