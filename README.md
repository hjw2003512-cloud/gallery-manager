# 앨범 정리 (AlbumSorter)

웹사이트나 다른 앱에서 이미지를 저장하면 알림이 오고, 알림을 눌러 앨범을 고르면 그 앨범(폴더)으로 옮겨 주는 안드로이드 앱입니다.

- 지원: 안드로이드 11 이상
- 앨범 = 휴대폰 `Pictures/` 또는 `DCIM/` 아래 폴더 (삼성 갤러리, 구글 포토 '기기 폴더'에 그대로 보임)

## APK 만드는 방법 (GitHub 자동 빌드)

1. https://github.com 에 로그인 → 오른쪽 위 **+** → **New repository**
   - 이름: `AlbumSorter`, Private 선택해도 됨 → **Create repository**
2. 생성된 화면에서 **uploading an existing file** 클릭
3. 압축을 푼 `AlbumSorter` 폴더 **안의 내용 전부**(app, .github, build.gradle, settings.gradle, gradle.properties, README.md)를 끌어다 놓기 → **Commit changes**
   - `.github` 폴더가 안 보이면(맥) Finder에서 `Cmd + Shift + .` 을 누르면 보입니다.
4. 위쪽 **Actions** 탭 → "APK 빌드" 작업이 자동 실행됨 (약 3~6분)
   - 작업이 안 보이면: Actions → **set up a workflow yourself** → `build.yml` 내용 붙여넣고 커밋
5. 초록색 ✓ 가 뜨면 그 작업을 클릭 → 맨 아래 **Artifacts** 의 `AlbumSorter-apk` 다운로드
6. 받은 zip을 풀면 `app-debug.apk` → 휴대폰으로 옮겨서 설치
   - "출처를 알 수 없는 앱 설치"를 허용해야 합니다.

## 처음 실행할 때

1. 사진 접근 허용 → **모두 허용** 선택 ('선택한 사진만'이면 감지 안 됨)
2. 알림 허용
3. (선택) 모든 파일 접근 허용 → 옮길 때마다 뜨는 '수정 허용' 창이 사라짐
4. (권장) 배터리 최적화 제외
5. **감시 시작하기**

## 동작 방식

- 새 이미지가 갤러리에 등록되면 알림: [앨범 선택] [→ 최근 앨범] [그대로 두기]
- 카메라 사진·스크린샷은 기본적으로 알림 제외 (설정에서 변경)
- 이름이 겹치면 `이름 (1).jpg` 로 저장
