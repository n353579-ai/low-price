# 단속카메라 데이터

앱에 들어 있는 `app/src/main/assets/cameras.grid.txt` 는 공공데이터포털
**전국무인교통단속카메라표준데이터**(경찰청, https://www.data.go.kr/data/15028200/standard.do)를
`tools/convert_cameras.py` 로 변환한 것입니다. 원본 이용 조건은 해당 데이터셋 페이지를 따릅니다.

## 자동 갱신
`.github/workflows/update-cameras.yml` 이 매월 오픈API(`tools/fetch_cameras.py`)로 최신 데이터를 받아
변환·커밋합니다. 인증키는 저장소 Secret `DATA_GO_KR_KEY` 에만 있습니다.
새 데이터가 기존의 90% 미만이거나 일부만 받아지면 기존 파일을 그대로 둡니다.
(봇 커밋은 APK 빌드를 자동으로 시작하지 않으므로, 필요하면 Actions에서 빌드를 직접 돌리거나 아무 변경이나 푸시하세요.)

## 수동 갱신
이 폴더(`nunppong-alert/data/`)에 CSV를 올리면 빌드 때 자동 변환됩니다.

변환 규칙: 제한속도가 있는 행을 과속 단속으로 보고(주정차 전용 제외), 좌표 없는 행과 같은 위치 중복을 제거합니다.
방향각 컬럼은 없고, `roadRouteDrc` 는 1/2/3 코드라 쓰지 않습니다.
