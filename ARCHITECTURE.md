# Architecture

## 1. 기존 실시간 파이프라인

```text
PPSSPP 화면
  ↓ MediaProjection
ImageReader
  ↓ 500ms throttle
ROI crop
  ↓
ML Kit Japanese OCR
  ↓ 중복/유사 문장 억제
Memory cache
  ↓ miss
Room persistent cache
  ↓ miss
OpenRouter API
  ↓
Korean overlay
```

## 2. 0.4.0-alpha4 로컬 모델 비교 구조

```text
4 model slots
  ├─ TranslateGemma 4B Q4_K_M
  ├─ TranslateGemma 4B Q3_K_M
  ├─ EXAONE 3.5 2.4B Q4_K_M
  └─ Qwen3 1.7B Q4_K_M
        ↓ user chooses one benchmark
release previous native model
        ↓
load selected GGUF (6 threads)
        ↓ resident model
same Japanese sentence × 3
        ↓ compact prompt / greedy
average inference / prompt eval / generate / tok/s
```

네 모델을 동시에 RAM에 적재하지 않습니다. 벤치마크를 시작할 때 이전 모델을 해제하고 선택된 모델 하나만 상주시킵니다.

### 파일 슬롯

- `translategemma-q4.gguf`
- `translategemma-q3.gguf`
- `exaone-2.4b-q4.gguf`
- `qwen3-1.7b-q4.gguf`

alpha1~3의 기존 `models/local-model.gguf`가 존재하고 전용 Q4 파일이 없으면 TranslateGemma Q4 슬롯에서 legacy 파일을 자동으로 사용합니다.

## 3. 공통 벤치 설정

- llama-android / llama.cpp CPU+NEON
- context 1024
- threads 6
- gpuLayers 0
- temperature 0
- topP 1 / topK 1 / seed 0
- PromptMode.COMPACT
- max output 64 tokens
- 동일 입력 3회 평균

## 4. 주요 구성

### `LocalModelActivity`
- 4개 모델 슬롯의 독립 파일 선택/교체
- 파일 존재 여부 및 크기 표시
- 선택 모델 3회 벤치 실행
- 로딩 시간, 평균 순수 번역, prompt eval, generate, tok/s 표시
- 각 회차 실제 번역문 표시

### `LocalModelTester`
- 단일 native `LlamaModel` 상주
- 경로와 스레드 수가 같으면 재사용
- `Mutex`로 load/inference/release 직렬화
- BASELINE/COMPACT 프롬프트 지원

### `ScreenOcrService`
현재 실시간 경로는 OpenRouter provider를 유지합니다. alpha4에서 속도·품질 우승 모델을 고른 뒤 `LocalTranslationProvider`로 연결합니다.

## 5. alpha3 실기기 기준점

POCO X8 Pro Max + TranslateGemma 4B Q4_K_M:

- 6스레드 + 짧은 프롬프트
- 순수 번역 약 5.73~5.90초
- prompt eval 약 2.76~2.92초
- generate 약 2.96~2.97초
- 약 11.10~11.15 tok/s

## 6. 최종 로컬 실시간 목표

```text
OCR 확정
  ↓
Memory cache
  ↓ miss
Room cache
  ↓ miss
LocalTranslationProvider
  ↓ resident winning model
Room cache write
  ↓
Korean overlay
```

API는 백업 provider로 유지합니다.
