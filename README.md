# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 버전은 **0.3.0 · Room 영구 캐시 + 용어집 + 번역 기록**입니다.

## 현재 구현 상태

- MediaProjection 사용자 동의 요청
- Android 14(API 34)+ 기본 디스플레이 전체 캡처 요청
- `mediaProjection` 타입 Foreground Service
- PPSSPP 위 `TYPE_APPLICATION_OVERLAY` 컨트롤
- 드래그 기반 OCR 영역 지정
- 500ms 간격 Google ML Kit 일본어 OCR
- 새 OCR 문장 2회 안정화 및 유사 문장 억제
- OpenRouter Chat Completions API 기반 한국어 번역
- 기본 모델 `openrouter/free`
- 직전 일본어 대사 최대 2개 문맥 전달
- OpenRouter 빈/null 응답 1회 재시도
- **Room 2.8.4 기반 영구 번역 캐시**
- 앱 종료/재실행 후에도 동일 일본어 원문 번역 재사용
- 캐시 최초 생성일, 마지막 사용일, 사용 횟수, 모델 저장
- **사용자 편집 가능 용어집** 추가/수정/삭제
- 현재 대사/문맥에 포함된 용어만 번역 프롬프트에 삽입
- 용어 수정/삭제 시 해당 용어가 들어간 번역 캐시만 선택 무효화
- 최근 번역 기록 최대 30개 조회
- 전체 번역 캐시 삭제 기능
- 실행 중 메모리 LRU 캐시 256개 병행
- 스크린샷 파일 저장 없음

## 기본 용어집

최초 실행 시 용어집이 비어 있으면 아래 3개가 자동 등록됩니다.

- `ロストール → 로스토르`
- `ソウル → 소울`
- `インフィニティア → 인피니티아`

앱의 `용어집 관리`에서 추가/수정/삭제할 수 있습니다.

## 사용 방법

1. OpenRouter API 키를 입력하고 저장합니다.
2. 필요하면 `용어집 관리`에서 고유명사를 등록합니다.
3. `번역 캡처 시작`을 누릅니다.
4. 전체 화면 캡처를 허용합니다.
5. PPSSPP로 전환합니다.
6. `영역` 버튼을 눌러 일본어 대화창을 드래그합니다.
7. 캐시가 있으면 API 호출 없이 즉시 재사용하고, 없으면 OpenRouter로 번역한 뒤 Room에 저장합니다.

## 캐시 동작

```text
OCR 확정
  ↓
메모리 캐시
  ↓ miss
Room 영구 캐시
  ↓ miss
용어집 필터링
  ↓
OpenRouter 번역
  ↓
Room 저장 + 오버레이 표시
```

무료 API 호출 한도를 아끼기 위해 같은 일본어 원문은 가능한 한 재호출하지 않습니다.

## 알려진 한계

- `openrouter/free`는 선택되는 무료 모델이 달라질 수 있어 번역 품질과 말투가 일정하지 않을 수 있습니다.
- 기존 캐시는 원문 기준으로 저장되므로 모델을 바꿔도 자동 재번역하지 않습니다. 필요하면 캐시 삭제 후 다시 번역합니다.
- 화면 회전 중 VirtualDisplay 재구성은 아직 없습니다.
- OCR 시 전체 화면 임시 Bitmap을 만드는 부분은 후속 최적화 대상입니다.
- API 키는 현재 앱 private SharedPreferences에 저장하며 Keystore 암호화는 아직 미적용입니다.

## 검증 상태

- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR: **실기기 성공**
- OpenRouter 무료 번역: **실기기 성공**
- 0.3.0 Room/KSP 포함 GitHub Actions `assembleDebug`: **성공**
- 0.3.0 영구 캐시/용어집 UI: POCO 실기기 확인 예정

## 다음 단계

`TODO.md` 참고. 0.3.0에서 실제 플레이 중 캐시 적중률과 무료 호출 절감 효과를 확인한 뒤 특정 번역 모델 비교와 로컬 LLM 단계로 진행합니다.
