# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 버전은 **0.2.3 · OpenRouter 무료 번역 / 전체 화면 캡처 안정화 버전**입니다.

## 현재 구현 상태

- MediaProjection 사용자 동의 요청
- Android 14(API 34)+에서는 `MediaProjectionConfig.createConfigForDefaultDisplay()`로 **기본 디스플레이 전체 캡처만 요청**
- `mediaProjection` 타입 Foreground Service
- PPSSPP 위 `TYPE_APPLICATION_OVERLAY` 컨트롤
- 화면에서 드래그하여 OCR 영역 지정
- 지정 영역만 500ms 간격으로 처리
- Google ML Kit 일본어 Text Recognition v2
- 새 OCR 문장은 2회 연속 유사하게 인식된 뒤 확정
- 기존 확정 문장과 약 86% 이상 유사한 결과는 동일 대사로 처리
- OpenRouter Chat Completions API 기반 한국어 번역
- 기본 모델 `openrouter/free`, 앱에서 특정 OpenRouter 모델 slug로 변경 가능
- 직전 일본어 대사 최대 2개를 짧은 문맥으로 전달
- 번역 중 새 OCR이 생기면 오래된 요청을 쌓지 않고 최신 요청 1개만 대기
- 동일 일본어 원문은 실행 중 최대 256개 메모리 캐시에서 재사용
- OpenRouter `null`/빈 응답은 그대로 표시하지 않고 1회 재시도
- 번역 자막을 OCR ROI 바깥쪽에 배치
- 동일 자막/동일 위치면 불필요한 View 갱신 억제
- 오버레이 창에 `FLAG_SECURE`를 적용하여 MediaProjection 재캡처 억제
- 스크린샷 파일 저장 없음

## API 설정

1. OpenRouter에서 API 키를 발급합니다: `https://openrouter.ai/settings/keys`
2. 앱의 `OpenRouter API 키` 입력란에 키를 입력합니다.
3. 모델은 기본 `openrouter/free` 그대로 두면 무료 모델 라우터를 사용합니다.
4. 특정 무료 모델을 쓰려면 OpenRouter의 `:free` 모델 slug를 입력할 수 있습니다.
5. `번역 설정 저장` 후 캡처를 시작합니다.

OpenRouter 무료 계정은 호출 제한이 있으므로 같은 문장은 메모리 캐시로 재호출을 줄입니다. 현재 캐시는 앱 프로세스가 종료되면 사라지며, 영구 Room 캐시는 3단계에서 추가합니다.

## 사용 방법

1. OpenRouter API 키를 입력하고 저장합니다.
2. `번역 캡처 시작`을 누릅니다.
3. Android 14 이상에서는 **전체 화면 캡처 동의**만 진행합니다. PPSSPP를 앱 목록에서 직접 고를 필요가 없습니다.
4. PPSSPP로 전환합니다.
5. 오른쪽 위 플로팅 컨트롤의 `영역` 버튼을 누릅니다.
6. 일본어 대화창을 드래그합니다.
7. 인식된 일본어가 바뀌면 한국어 번역 자막이 표시됩니다.

## 알려진 한계

- `openrouter/free`는 호출마다 선택되는 무료 모델이 달라질 수 있어 번역 말투와 고유명사 일관성이 변할 수 있습니다.
- 무료 계정의 요청 한도 때문에 장시간 플레이에는 영구 캐시 및 특정 저비용/로컬 모델이 필요합니다.
- 화면 회전 중 VirtualDisplay 재구성은 아직 없습니다.
- OCR 시 전체 화면 임시 Bitmap을 만드는 부분은 후속 최적화 대상입니다.
- HyperOS에서 전체 화면 캡처 동의 UI가 제조사 커스텀으로 다르게 보일 수 있으므로 POCO 실기기 확인이 필요합니다.

## 검증 상태

- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR: **실기기 성공 확인**
- OpenRouter 무료 번역: **실기기 동작 확인**
- 동일 대사 자막 깜빡임: 0.2.2에서 안정화 로직 추가
- Android 14+ 전체 화면 캡처 강제: 0.2.3에서 추가, 실기기 확인 예정

## 다음 단계

`TODO.md` 참고. 0.2.3에서 전체 화면 캡처 시작 여부와 자막 안정성을 확인한 뒤 3단계 Room 캐시와 용어집으로 진행합니다.
