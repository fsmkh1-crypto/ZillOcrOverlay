# TODO

## 1단계 · 화면 캡처 + OCR

- [x] Android 프로젝트 골격 생성
- [x] MediaProjection 동의 플로우
- [x] Android 14+ 기본 디스플레이 전체 캡처 요청
- [x] `mediaProjection` Foreground Service
- [x] 다른 앱 위 오버레이 권한 플로우
- [x] PPSSPP 위 플로팅 컨트롤 표시
- [x] 드래그 OCR 영역 선택
- [x] 정규화 좌표로 OCR 영역 저장
- [x] ImageReader로 화면 프레임 수신
- [x] 500ms OCR 간격 제한
- [x] 지정 영역만 ML Kit 일본어 OCR 입력
- [x] 동일/유사 OCR 결과 재처리 억제
- [x] 새 OCR 2회 연속 확인 후 확정
- [x] GitHub Actions `assembleDebug` 성공 확인
- [x] debug APK artifact 생성 확인
- [x] POCO X8 Pro Max 실기기 설치 확인
- [x] PPSSPP 실제 Zill O’ll 일본어 OCR 동작 확인
- [ ] 가로/세로 및 PPSSPP 화면 회전 테스트
- [ ] OCR 영역 크기별 평균 지연 측정

## 2단계 · API 번역 + 오버레이

- [x] TranslationProvider 인터페이스
- [x] OpenRouter Chat Completions API
- [x] 기본 모델 `openrouter/free`
- [x] 앱 내 API 키/모델 설정 UI
- [x] OCR → 번역 API 파이프라인
- [x] 직전 일본어 대사 최대 2개 문맥 전달
- [x] 번역 요청 전용 백그라운드 스레드
- [x] 번역 중 새 OCR 발생 시 최신 요청만 대기
- [x] 번역 자막을 OCR ROI 바깥에 배치
- [x] 오버레이 `FLAG_SECURE` 적용
- [x] OpenRouter 무료 번역 POCO 실기기 동작 확인
- [x] OpenRouter null/빈 응답 1회 재시도
- [x] 동일 자막/동일 위치 불필요한 View 갱신 억제
- [ ] 평균 API 번역 지연 측정
- [ ] 일본어 원문 표시 ON/OFF
- [ ] API 키 Android Keystore 암호화 저장

## 3단계 · 중복 감지 + 캐시 + 용어집

- [x] 실행 중 메모리 LRU 캐시(최대 256개)
- [x] OCR 유사 문장 판별 1차 적용
- [x] Room 2.8.4 도입
- [x] 원문 기준 영구 번역 캐시
- [x] 최초 생성일 / 마지막 사용일 / 사용 횟수 / 모델 저장
- [x] 최근 번역 기록 조회
- [x] 전체 캐시 삭제
- [x] 용어집 추가/수정/삭제
- [x] 현재 대사에 관련된 용어만 프롬프트에 전달
- [x] 용어 수정/삭제 시 관련 캐시 선택 무효화
- [x] 기본 용어 3개 자동 시드
- [x] POCO 실기기 API 번역 동작 재확인
- [ ] 실제 플레이 1시간 캐시 적중률 측정

## 4단계 · 로컬 LLM

- [x] Android llama.cpp 사전 빌드 AAR 후보 선정
- [x] `llama-android` CPU/NEON 런타임 Gradle 통합
- [x] GGUF 파일 선택 및 앱 전용 모델 폴더 복사 UI
- [x] context 1024 / threads 4 / max output 128 테스트 설정
- [x] 일본어→한국어 단독 로컬 번역 벤치마크 화면
- [x] 총 처리시간 / tok/s 표시
- [ ] TranslateGemma 4B Q4_K_M POCO 실기기 로딩 확인
- [ ] TranslateGemma 4B Q4_K_M 번역 품질·속도 측정
- [ ] 로컬 실행 중 RAM / 발열 측정
- [ ] PPSSPP 동시 실행 프레임 영향 측정
- [ ] 모델 상주형 `LocalTranslationProvider` 구현
- [ ] Room 캐시 → 로컬 모델 → 오버레이 연결
- [ ] API / 로컬 엔진 전환 UI
- [ ] Qwen 3~4B Q4 비교
- [ ] 필요 시 TranslateGemma Q3/Q2 또는 2~3B 모델 비교

## 5단계 · 최적화

- [ ] ROI 픽셀 변화 게이트 추가: 변화 없으면 ML Kit OCR 생략
- [ ] ROI 복사 시 전체 화면 Bitmap 할당 제거
- [ ] 재사용 버퍼/Bitmap 검토
- [ ] OCR 주기 300~700ms 설정화
- [ ] 평균 OCR/번역 지연 계측
- [ ] RAM/CPU/GPU/발열/배터리 측정
- [ ] PPSSPP FPS 영향 측정
