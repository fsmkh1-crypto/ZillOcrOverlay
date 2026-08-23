# 0.4.0-alpha3 벤치마크 메모

목적: TranslateGemma 4B Q4_K_M의 병목이 prompt eval인지 확인하고 CPU thread 수에 따른 차이를 측정합니다.

## 실기기 기준 이전 결과

- 0.4.0-alpha2 순수 번역: 8.13~8.99초
- prompt eval: 4.33~5.07초
- generate: 3.79~3.92초
- 생성 속도: 8.42~8.71 tok/s
- 동일 문장 3회 번역 결과 일치

## alpha3 테스트

1. 4스레드로 모델 로드
2. 기존 프롬프트 번역 2회
3. 짧은 프롬프트 번역 2회
4. 6스레드로 모델 재로드
5. 기존 프롬프트 번역 2회
6. 짧은 프롬프트 번역 2회

각 결과의 순수 번역 시간, prompt eval, generate, tok/s를 비교합니다.

짧은 프롬프트는 공식 TranslateGemma 구조를 완전히 재현하는 것이 아니라, 현재 llama-android의 문자열 기반 completion API 안에서 입력 오버헤드를 줄일 수 있는지 확인하기 위한 실험용입니다.
