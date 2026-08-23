# Changelog

## 0.4.0-alpha4 - 2026-08-23

### Added

- 4개 로컬 GGUF 모델을 독립 슬롯으로 보관하는 비교 UI
- TranslateGemma 4B Q4_K_M / Q3_K_M 슬롯
- EXAONE 3.5 2.4B Q4_K_M 슬롯
- Qwen3 1.7B Q4_K_M 슬롯
- 각 모델 동일 문장 3회 반복 벤치 및 평균값 표시
- 모델 크기 / 로딩 시간 / 순수 번역 / prompt eval / generate / tok/s / 각 회차 번역문 표시
- alpha1~3의 기존 `local-model.gguf`를 TranslateGemma Q4 슬롯에서 자동 재사용하는 legacy fallback

### Changed

- 모델 비교 조건을 6 CPU threads + COMPACT prompt + greedy + max 64 tokens로 고정
- 모델 변경 시 이전 native model을 해제하고 한 모델만 RAM에 상주
- 버전 0.4.0-alpha4 / versionCode 11

### Validation target

- alpha3 POCO 최선: TranslateGemma 4B Q4, 6 threads + compact prompt 약 5.73~5.90초, 11.10~11.15 tok/s
- alpha4에서 Q3 / EXAONE 2.4B / Qwen3 1.7B의 속도와 일본어→한국어 품질을 동일 조건에서 비교

## 0.4.0-alpha3 - 2026-08-23

### Added

- TranslateGemma 4B용 프롬프트 비교 벤치마크
- 기존 RPG 번역 system prompt와 짧은 일본어→한국어 프롬프트를 동일 모델에서 비교
- CPU 4스레드 / 6스레드 모델 로드 및 추론 비교 버튼
- 결과 화면에 사용 스레드와 프롬프트 모드 표시

### Changed

- 로컬 벤치 출력 최대 길이 96 → 64 tokens
- 모델 경로뿐 아니라 스레드 수가 바뀌면 모델을 재로딩하도록 상주 모델 상태 관리 확장
- temperature 0 / topK 1 / seed 0 결정론적 생성 유지
- 버전 0.4.0-alpha3 / versionCode 10

### Validation

- POCO X8 Pro Max, TranslateGemma 4B Q4_K_M
- 4 threads + baseline: 약 8.45~8.57초, 8.35~8.41 tok/s
- 4 threads + compact: 약 6.51~7.89초, 8.39~9.86 tok/s
- 6 threads + baseline: 약 6.48~6.49초, 11.02~11.08 tok/s
- 6 threads + compact: 약 5.73~5.90초, 11.10~11.15 tok/s

## 0.4.0-alpha2 - 2026-08-23

- GGUF 모델 상주형 벤치 구조
- 최초 로딩과 순수 추론 시간 분리
- alpha2 POCO 순수 번역 8.13 / 8.99 / 8.77초
- prompt eval 4326 / 5072 / 4840 ms
- generate 3789 / 3913 / 3918 ms

## 0.4.0-alpha1 - 2026-08-23

- Android llama.cpp AAR 통합
- GGUF 선택/복사 및 로컬 일본어→한국어 벤치
- TranslateGemma 4B Q4_K_M POCO 실기기 성공

## 0.3.1 - 2026-08-23

- OpenRouter reasoning 강제 비활성화 제거

## 0.3.0 - 2026-08-23

- Room 영구 번역 캐시
- 사용자 편집 용어집
- 번역 기록

## 0.2.x

- OpenRouter API 번역
- OCR 안정화 및 오버레이 깜빡임 억제
- Android 14+ 전체 디스플레이 MediaProjection 흐름

## 0.1.0 - 2026-08-23

- Kotlin Android 프로젝트
- MediaProjection / Overlay / ML Kit 일본어 OCR
- GitHub Actions debug APK 빌드
