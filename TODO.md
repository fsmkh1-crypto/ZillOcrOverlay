# TODO

## 1단계 · 화면 캡처 + OCR
- [x] Android 프로젝트 골격 생성
- [x] MediaProjection 동의 플로우
- [x] 지정 ROI OCR
- [x] PPSSPP 실제 Zill O’ll 일본어 OCR 동작 확인
- [ ] 화면 회전 테스트
- [ ] OCR 영역 크기별 평균 지연 측정

## 2단계 · API 번역 + 오버레이
- [x] OpenRouter API 번역
- [x] 한국어 오버레이
- [x] 직전 문맥 전달
- [x] null/빈 응답 처리
- [ ] 평균 API 번역 지연 측정
- [ ] API 키 Keystore 암호화

## 3단계 · 중복 감지 + 캐시 + 용어집
- [x] 메모리 LRU 캐시
- [x] Room 영구 번역 캐시
- [x] 사용자 용어집 CRUD
- [x] 최근 번역 기록
- [ ] 실제 플레이 1시간 캐시 적중률 측정

## 4단계 · 로컬 LLM
- [x] llama-android CPU/NEON 통합
- [x] GGUF 선택/복사/상주
- [x] TranslateGemma 4B Q4_K_M 실기기 성공
- [x] alpha2 상주 모델 병목 측정
- [x] alpha3 4/6스레드 및 프롬프트 비교
- [x] alpha3 최선: 6스레드 + 짧은 프롬프트 약 5.73~5.90초 / 11.10~11.15 tok/s
- [x] alpha4 4종 모델 독립 슬롯 구현
- [x] alpha4 동일 조건 3회 평균 벤치 구현
- [ ] TranslateGemma 4B Q3_K_M 실기기 벤치
- [ ] EXAONE 3.5 2.4B Q4_K_M 실기기 벤치
- [ ] Qwen3 1.7B Q4_K_M 실기기 벤치
- [ ] 네 모델 번역 품질 비교
- [ ] 로컬 실행 중 RAM / 발열 측정
- [ ] PPSSPP 동시 실행 FPS 영향 측정
- [ ] 우승 모델 선정
- [ ] `LocalTranslationProvider` 구현
- [ ] Room cache → 로컬 모델 → 오버레이 연결
- [ ] API / 로컬 엔진 전환 UI

## 5단계 · 최적화
- [ ] ROI 픽셀 변화 게이트
- [ ] 전체 화면 Bitmap 할당 제거
- [ ] 재사용 버퍼/Bitmap
- [ ] OCR 주기 설정화
- [ ] RAM/CPU/발열/배터리 계측
