# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 개발 버전은 **0.4.0-alpha4 · 4종 로컬 모델 비교 벤치마크**입니다.

## 현재 구현 상태

- MediaProjection + PPSSPP 지정 ROI 캡처
- Google ML Kit 일본어 OCR, 500ms 주기
- OpenRouter API 번역
- Room 영구 번역 캐시 + 사용자 용어집
- Android용 llama.cpp CPU/NEON 런타임
- GGUF 로컬 모델 로딩/상주/벤치마크
- deterministic greedy 생성: temperature 0, topK 1, seed 0

## alpha4 비교 모델

앱 전용 저장공간에 아래 4개 모델을 **서로 다른 파일로 보관**하여 덮어쓰지 않고 비교합니다.

1. TranslateGemma 4B Q4_K_M — 기존 기준점
2. TranslateGemma 4B Q3_K_M — 같은 4B 모델의 더 강한 양자화
3. EXAONE 3.5 2.4B Q4_K_M — 한국어 강점의 소형 후보
4. Qwen3 1.7B Q4_K_M — 속도 우선 소형 후보

alpha3에서 쓰던 `local-model.gguf`가 남아 있으면 TranslateGemma Q4 슬롯에서 기존 파일을 그대로 인식하므로 Q4를 다시 받을 필요가 없습니다.

## alpha4 벤치 조건

- CPU threads: 6
- context: 1024
- gpuLayers: 0 (CPU/NEON)
- PromptMode: COMPACT
- max output: 64 tokens
- 각 모델: 동일 문장 3회 반복 후 평균 표시

결과 화면에 모델 크기, 최초 로딩 시간, 3회 평균 순수 번역 시간, prompt eval, generate, tok/s와 각 회차 번역문을 표시합니다.

## POCO X8 Pro Max 기존 결과

TranslateGemma 4B Q4_K_M 기준 alpha3 최선:

- 6스레드 + 짧은 프롬프트: 약 5.73~5.90초
- prompt eval: 약 2.76~2.92초
- generate: 약 2.96~2.97초
- 생성 속도: 약 11.10~11.15 tok/s

따라서 alpha4의 목적은 단순 양자화(Q3)와 더 작은 2.4B/1.7B 모델이 번역 품질을 유지하면서 1~3초 목표에 얼마나 가까워지는지 확인하는 것입니다.

## 테스트 방법

1. `질올 로컬 모델 테스트` 실행
2. 각 모델 슬롯의 `파일 선택/교체`를 눌러 해당 GGUF를 지정
3. 비교할 일본어 문장을 입력
4. 각 모델의 `3회 벤치`를 실행
5. 4개 모델의 평균 순수 번역 시간, tok/s, 번역 품질을 비교

모델을 바꿀 때 기존 native model은 해제한 뒤 새 모델을 로드합니다. 네 모델을 동시에 RAM에 올리지는 않습니다.

## 기존 API 번역

기존 `질올 OCR 오버레이`의 OpenRouter 번역, Room 캐시, 용어집은 그대로 유지됩니다. alpha4는 로컬 모델 후보를 고르기 위한 별도 벤치 단계입니다.

## 다음 단계

1. 4종 모델 POCO 실기기 비교
2. 속도·품질 우승 모델 선정
3. PPSSPP 동시 실행 RAM/발열/FPS 영향 확인
4. `LocalTranslationProvider`로 실시간 OCR → 로컬 번역 → 오버레이 연결
5. ROI 픽셀 변화 게이트 추가
