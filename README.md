# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 개발 버전은 **0.4.0-alpha3 · TranslateGemma 프롬프트/CPU 스레드 비교 벤치마크**입니다.

## 현재 구현 상태

- MediaProjection + PPSSPP 지정 ROI 캡처
- Google ML Kit 일본어 OCR, 500ms 주기
- 2회 안정화 및 유사 문장 중복 억제
- OpenRouter API 번역
- Room 영구 번역 캐시 + 메모리 LRU 캐시
- 사용자 편집 용어집 및 번역 기록
- Android용 llama.cpp AAR
- GGUF 모델 파일 선택 및 앱 전용 모델 폴더 복사
- TranslateGemma 4B Q4_K_M 로컬 일본어→한국어 번역 성공
- GGUF 모델 상주 재사용
- 최초 모델 로딩 시간과 순수 번역 시간 분리 측정
- prompt eval / generate / tok/s 표시
- temperature 0, topK 1, seed 0 결정론적 생성
- alpha3에서 기존 프롬프트와 짧은 프롬프트 비교
- alpha3에서 CPU 4스레드 / 6스레드 비교
- 로컬 벤치 기본 context 1024, 출력 최대 64 tokens

## POCO 실기기 결과

POCO X8 Pro Max에서 TranslateGemma 4B Q4_K_M(앱 표시 약 2.32GB) 로딩 및 번역에 성공했습니다.

### alpha1

- 총 시간: 약 8.93초 / 9.49초
- 생성 속도: 약 8.54 / 7.60 tok/s
- 매 요청마다 모델을 다시 로드하던 구조

### alpha2 · 상주 모델

동일 문장을 3회 반복한 결과:

- 순수 번역: 8.13초 / 8.99초 / 8.77초
- prompt eval: 4326 / 5072 / 4840 ms
- generate: 3789 / 3913 / 3918 ms
- 생성 속도: 8.71 / 8.43 / 8.42 tok/s
- 번역문 3회 동일

모델 로딩이 아니라 **prompt eval 약 4.3~5.1초 + generation 약 3.8~3.9초**가 주 병목임을 확인했습니다.

## alpha3 테스트 방법

1. `질올 로컬 모델 테스트` 실행
2. 기존 GGUF 모델이 이미 선택되어 있으면 다시 받을 필요 없음
3. `4스레드로 모델 메모리 로드`
4. `기존 프롬프트로 번역` 2회
5. `짧은 프롬프트로 번역` 2회
6. `6스레드로 모델 메모리 로드`
7. 동일하게 기존/짧은 프롬프트를 각각 2회 실행
8. 각 결과의 순수 번역 시간, prompt eval, generate, tok/s, 번역 품질을 비교

짧은 프롬프트는 TranslateGemma의 공식 Hugging Face 구조를 완전히 재현하는 것이 아니라, 현재 llama-android 문자열 completion API 안에서 입력 토큰 오버헤드를 줄일 수 있는지 확인하기 위한 실험입니다.

## 현재 판단 기준

- 순수 번역 1~3초: 4B Q4 유지 후 실시간 파이프라인 연결
- 3~5초: 추가 최적화 후 재평가
- 5초 이상 지속: Q3/Q2 또는 2~3B 모델 비교
- 6스레드가 빨라도 PPSSPP 프레임/발열 악화가 크면 4스레드 유지

## 기존 API 번역

기존 `질올 OCR 오버레이`는 그대로 유지됩니다. OpenRouter API 번역, Room 캐시, 용어집 기능은 기존과 동일합니다.

## 다음 단계

1. alpha3 프롬프트/4·6스레드 비교
2. 4B Q4 유지 여부 결정
3. 통과 시 `LocalTranslationProvider`로 실시간 OCR 파이프라인 연결
4. 느리면 더 작은 양자화 또는 2~3B 모델 비교
5. ROI 픽셀 변화 게이트 추가
