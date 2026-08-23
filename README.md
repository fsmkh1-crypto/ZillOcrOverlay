# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 개발 버전은 **0.4.0-alpha2 · 로컬 GGUF 상주 모델 벤치마크**입니다.

## 현재 구현 상태

- MediaProjection + PPSSPP 지정 ROI 캡처
- Google ML Kit 일본어 OCR, 500ms 주기
- 2회 안정화 및 유사 문장 중복 억제
- OpenRouter API 번역
- Room 영구 번역 캐시 + 메모리 LRU 캐시
- 사용자 편집 용어집 및 번역 기록
- Android용 llama.cpp AAR
- GGUF 모델 파일 선택 및 앱 전용 모델 폴더 복사
- 로컬 일본어→한국어 테스트 번역
- **GGUF 모델을 한 번 메모리에 로드한 뒤 재사용**
- **최초 모델 로딩 시간과 순수 번역 시간을 분리 측정**
- prompt eval / generate 시간 및 tok/s 표시
- deterministic 번역을 위해 temperature 0, topK 1, seed 0
- 기본 로컬 벤치 설정: context 1024, CPU threads 4, 출력 최대 96 tokens

## 실기기 1차 결과

POCO X8 Pro Max에서 TranslateGemma 4B Q4_K_M(약 2.32GB) 로딩 및 번역에 성공했습니다.

- alpha1 총 시간: 약 8.93초 / 9.49초
- 생성 속도: 약 8.54 tok/s / 7.60 tok/s
- 번역 품질: 의미 전달 및 자연스러움 양호
- alpha1은 매 요청마다 모델을 다시 로드했으므로 실제 상주형 지연과 동일하지 않음

alpha2에서는 모델을 한 번만 로드한 상태에서 순수 추론 시간을 다시 측정합니다.

## 로컬 모델 테스트 방법

1. `질올 로컬 모델 테스트` 실행
2. 기존 TranslateGemma GGUF가 이미 선택되어 있으면 다시 받을 필요 없음
3. `모델 메모리 로드`를 누르고 최초 로딩 시간을 확인
4. `상주 모델로 테스트 번역`을 3~5회 반복
5. 순수 번역 시간, 생성 속도, prompt eval, generate 시간을 기록
6. 같은 일본어 문장이 매번 같은 번역으로 나오는지도 확인

## 현재 판단 기준

- 순수 번역 1~3초: 4B Q4 유지 후 실시간 파이프라인 연결 검토
- 3~5초: 추가 최적화 후 재평가
- 5초 이상 지속: Q3/Q2 또는 2~3B 모델 비교
- PPSSPP 동시 실행 시 OOM/프레임 저하/과열이 크면 더 작은 모델로 하향

## 기존 API 번역

기존 `질올 OCR 오버레이`는 그대로 유지됩니다. OpenRouter API 번역, Room 캐시, 용어집 기능은 기존과 동일합니다.

## 다음 단계

1. alpha2 상주 모델 순수 번역 지연 측정
2. 3~5회 반복 결과 비교
3. 4B Q4 유지 여부 결정
4. 통과 시 `LocalTranslationProvider`로 실시간 OCR 파이프라인 연결
5. ROI 픽셀 변화 게이트 추가 검토
