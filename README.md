# Zill OCR Overlay

Android에서 PPSSPP로 실행하는 **Zill O’ll Infinite Plus 일본어판**의 지정 화면 영역을 실시간 OCR하고, 인식된 일본어를 다른 앱 위 오버레이로 표시하는 프로젝트입니다.

현재 버전은 **1단계 프로토타입**입니다. 번역 기능은 아직 넣지 않았습니다.

## 현재 구현 상태

- MediaProjection 사용자 동의 요청
- `mediaProjection` 타입 Foreground Service
- PPSSPP 위 `TYPE_APPLICATION_OVERLAY` 컨트롤
- 화면에서 드래그하여 OCR 영역 지정
- 지정 영역만 500ms 간격으로 처리
- Google ML Kit 일본어 Text Recognition v2
- OCR 결과가 이전 결과와 달라질 때만 오버레이 갱신
- 스크린샷 파일 저장 없음
- 캡처 이미지는 메모리에서 처리 후 폐기
- OCR/이미지 처리는 전용 `HandlerThread`에서 수행
- OCR 영역은 정규화 좌표로 SharedPreferences에 유지

## 빌드 환경

- Android Studio: Android 16 SDK를 지원하는 버전
- JDK: 17 이상
- Gradle: 8.13
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.3.20
- compileSdk / targetSdk: 36
- minSdk: 26
- AndroidX Core KTX: 1.17.0
- AndroidX Activity KTX: 1.12.4
- ML Kit Japanese Text Recognition: `com.google.mlkit:text-recognition-japanese:16.0.1`

## 빌드

GitHub Actions의 `Build debug APK` 워크플로가 `main` 브랜치 변경 시 자동으로 `assembleDebug`를 실행하고 APK를 artifact로 보관합니다.

2026-08-23 실제 GitHub Actions 빌드에서 `assembleDebug`와 debug APK artifact 업로드까지 성공했습니다.

로컬에서 빌드할 경우 Android SDK Platform 36과 JDK 17이 필요합니다.

## 사용 방법

1. 앱 실행 후 `OCR 캡처 시작`을 누릅니다.
2. `다른 앱 위에 표시` 권한이 없으면 허용합니다.
3. Android 화면 공유/캡처 동의 창에서 캡처를 허용합니다.
4. PPSSPP로 전환합니다.
5. 오른쪽 위 플로팅 컨트롤의 `영역` 버튼을 누릅니다.
6. 대화창·메뉴·아이템 설명 등 일본어가 나오는 영역을 드래그합니다.
7. OCR된 일본어가 반투명 검은 자막 박스로 표시됩니다.
8. `중지` 버튼 또는 앱의 `OCR 캡처 중지`를 누르면 종료됩니다.

## Android 14+ 관련 주의

Android 14(API 34) 이상에서는 각 MediaProjection 세션마다 새 사용자 동의가 필요합니다. 같은 캡처 동의 Intent/MediaProjection 인스턴스를 재사용하지 않습니다.

## 알려진 한계

- 실제 POCO X8 Pro Max/HyperOS에서 아직 실기기 검증하지 않았습니다.
- 현재 ImageReader 프레임에서 전체 화면 크기의 임시 Bitmap을 만든 뒤 ROI를 자릅니다. OCR 자체는 ROI에만 수행하지만, 메모리 대역폭 최적화는 5단계에서 개선 대상입니다.
- OCR 결과 오버레이가 지정 OCR 영역과 겹치지 않도록 자동으로 위/아래 배치하지만, 큰 ROI에서는 겹칠 수 있습니다. 자기 오버레이가 캡처 입력에 섞이는 문제는 실기기 테스트 후 별도 억제 로직을 추가할 수 있습니다.
- 회전 중에는 VirtualDisplay를 새로 만들지 않습니다. Android 14+의 세션 재사용 제한 때문에 회전 대응은 `VirtualDisplay.resize()` + `setSurface()` 방식으로 추가해야 합니다.
- 현재 OCR 주기는 코드 상수 500ms입니다. 설정 UI는 후속 단계에서 추가합니다.
- 앱 창 캡처 방식보다 **전체 화면 캡처**를 선택하는 편이 영역 좌표 일치가 단순합니다.

## 다음 단계

`TODO.md` 참고. 현재 다음 작업은 POCO X8 Pro Max에 debug APK를 설치해 PPSSPP 실화면에서 OCR 영역 좌표, 정확도, 지연, HyperOS 오버레이 동작을 검증하는 것입니다.

## 검증 상태

- XML 파싱 확인: 완료
- Kotlin 소스 문법 smoke check: 완료(파서 수준)
- GitHub Actions 실제 `assembleDebug`: **성공**
- debug APK artifact 생성: **성공**
- POCO X8 Pro Max 실기기: **미검증**
