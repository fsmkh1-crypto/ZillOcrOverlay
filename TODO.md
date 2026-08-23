# TODO

## 1단계 · 화면 캡처 + OCR

- [x] Android 프로젝트 골격 생성
- [x] MediaProjection 동의 플로우
- [x] `mediaProjection` Foreground Service
- [x] 다른 앱 위 오버레이 권한 플로우
- [x] PPSSPP 위 플로팅 컨트롤 표시
- [x] 드래그 OCR 영역 선택
- [x] 정규화 좌표로 OCR 영역 저장
- [x] ImageReader로 화면 프레임 수신
- [x] 500ms OCR 간격 제한
- [x] 지정 영역만 ML Kit 일본어 OCR 입력
- [x] 이전 OCR 결과와 같으면 오버레이 갱신 생략
- [x] OCR 일본어 결과 오버레이 표시
- [x] GitHub Actions `assembleDebug` 성공 확인
- [x] debug APK artifact 생성 확인
- [x] POCO X8 Pro Max 실기기 설치 확인
- [x] PPSSPP 실제 Zill O’ll 일본어 OCR 동작 확인
- [ ] 가로/세로 및 PPSSPP 화면 회전 테스트
- [ ] OCR 영역 크기별 평균 지연 측정

## 2단계 · API 번역 + 오버레이

- [x] TranslationProvider 인터페이스
- [x] OpenAI Responses API 구현
- [x] 앱 내 API 키/모델 설정 UI
- [x] OCR → 번역 API 파이프라인
- [x] 직전 일본어 대사 최대 2개 문맥 전달
- [x] 번역 요청 전용 백그라운드 스레드
- [x] 번역 중 새 OCR 발생 시 최신 요청만 대기
- [x] 번역 자막을 OCR ROI 바깥에 배치
- [x] 오버레이 `FLAG_SECURE` 적용으로 자기 캡처 억제
- [ ] 0.2.0 POCO 실기기 번역 성공 확인
- [ ] 평균 API 번역 지연 측정
- [ ] HyperOS에서 `FLAG_SECURE` 재캡처 억제 확인
- [ ] 일본어 원문 표시 ON/OFF
- [x] 번역만 표시 기본 모드
- [ ] 네트워크 재시도 정책
- [ ] API 키 Android Keystore 암호화 저장

## 3단계 · 중복 감지 + 캐시 + 용어집

- [ ] Room 도입
- [ ] 정규화/해시 기반 문장 캐시
- [ ] 번역 기록
- [ ] 용어집 CRUD
- [ ] OCR 오탈자에 강한 유사 문장 판별 검토

## 4단계 · 로컬 LLM

- [ ] Android 로컬 추론 런타임 후보 벤치마크
- [ ] Qwen 3~4B Q4
- [ ] Gemma 3~4B Q4
- [ ] 1024~2048 context
- [ ] thinking/reasoning 비활성화
- [ ] API/로컬 전환

## 5단계 · 최적화

- [ ] ROI 복사 시 전체 화면 Bitmap 할당 제거
- [ ] 재사용 버퍼/Bitmap 검토
- [ ] OCR 주기 300~700ms 설정화
- [ ] 평균 OCR/번역 지연 계측
- [ ] RAM/CPU/GPU/발열/배터리 측정
- [ ] PPSSPP FPS 영향 측정
