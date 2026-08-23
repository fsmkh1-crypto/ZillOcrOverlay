# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 한국어로 번역해 게임 화면 위에 표시하는 프로젝트입니다.

현재 버전은 **0.2.0 · 2단계 API 번역 프로토타입**입니다.

## 현재 구현 상태

- MediaProjection 사용자 동의 요청
- `mediaProjection` 타입 Foreground Service
- PPSSPP 위 `TYPE_APPLICATION_OVERLAY` 컨트롤
- 화면에서 드래그하여 OCR 영역 지정
- 지정 영역만 500ms 간격으로 처리
- Google ML Kit 일본어 Text Recognition v2
- OCR 결과가 이전 결과와 달라질 때만 후속 처리
- OpenAI Responses API 기반 한국어 번역
- 기본 모델 `gpt-5.6-luna`, 앱에서 모델명 변경 가능
- 직전 일본어 대사 최대 2개를 짧은 문맥으로 전달
- 번역 요청은 전용 단일 백그라운드 스레드에서 실행
- 번역 중 새 OCR이 생기면 오래된 요청을 쌓지 않고 최신 요청 1개만 대기
- 번역 자막을 OCR ROI 바깥쪽에 배치
- 오버레이 창에 `FLAG_SECURE`를 적용하여 MediaProjection 재캡처 억제
- 스크린샷 파일 저장 없음
- 캡처 이미지는 메모리에서 처리 후 폐기
- OCR 영역은 정규화 좌표로 SharedPreferences에 유지

## 빌드 환경

- JDK: 17
- Gradle: 8.13
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.3.20
- compileSdk / targetSdk: 36
- minSdk: 26
- AndroidX Core KTX: 1.17.0
- AndroidX Activity KTX: 1.12.4
- ML Kit Japanese Text Recognition: `com.google.mlkit:text-recognition-japanese:16.0.1`

## API 설정

1. OpenAI Platform에서 API 키를 발급합니다.
2. 앱의 `OpenAI API 키` 입력란에 키를 입력합니다.
3. 기본 모델은 `gpt-5.6-luna`입니다.
4. `번역 설정 저장`을 누른 뒤 캡처를 시작합니다.

ChatGPT 구독과 OpenAI API 과금은 별도입니다.

현재 0.2.0에서는 API 키를 앱의 private SharedPreferences에 저장합니다. 일반 앱에서는 직접 읽을 수 없지만 하드웨어 기반 암호화 저장은 아니므로, 후속 버전에서 Android Keystore 적용 예정입니다.

## 사용 방법

1. 앱에서 API 키와 모델을 설정합니다.
2. `번역 캡처 시작`을 누릅니다.
3. `다른 앱 위에 표시` 권한이 없으면 허용합니다.
4. Android 화면 공유/캡처 동의 창에서 **전체 화면 캡처**를 허용합니다.
5. PPSSPP로 전환합니다.
6. 오른쪽 위 플로팅 컨트롤의 `영역` 버튼을 누릅니다.
7. 대화창·메뉴·아이템 설명 등 일본어가 나오는 영역을 드래그합니다.
8. 인식된 일본어가 바뀌면 API 번역을 요청하고 한국어 자막을 화면 위에 표시합니다.
9. `중지` 버튼 또는 앱의 `캡처 중지`를 누르면 종료됩니다.

## Android 14+ 관련 주의

Android 14(API 34) 이상에서는 각 MediaProjection 세션마다 새 사용자 동의가 필요합니다. 같은 캡처 동의 Intent/MediaProjection 인스턴스를 재사용하지 않습니다.

## 알려진 한계

- 현재 ImageReader 프레임에서 전체 화면 크기의 임시 Bitmap을 만든 뒤 ROI를 자릅니다. OCR 자체는 ROI에만 수행하지만 메모리 대역폭 최적화는 후속 대상입니다.
- `FLAG_SECURE`가 HyperOS/MediaProjection 조합에서 실제로 오버레이 재캡처를 완전히 차단하는지는 0.2.0 실기기 확인이 필요합니다.
- 화면 회전 중에는 VirtualDisplay를 재구성하지 않습니다.
- OCR 주기는 현재 코드 상수 500ms입니다.
- 번역 캐시와 사용자 편집 용어집은 아직 없습니다.
- API 네트워크 장애나 모델별 응답 속도 편차가 있을 수 있습니다.

## 검증 상태

- GitHub Actions 실제 `assembleDebug`: 자동 검증
- debug APK artifact 생성: 자동 검증
- POCO X8 Pro Max + PPSSPP + Zill O’ll 일본어 OCR: **1단계 실기기 성공 확인**
- 0.2.0 API 번역: **실기기 확인 예정**

## 다음 단계

`TODO.md` 참고. 0.2.0 실기기에서 번역 지연, 번역 품질, 자막 위치, 자기 오버레이 재캡처 여부를 확인한 뒤 3단계 캐시·용어집으로 진행합니다.
