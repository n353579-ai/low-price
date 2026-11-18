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

## 도로 선 (오탐 줄이기)
앱은 지도를 넣지 않고, 카메라마다 **카메라 앞 약 1.1km 의 도로 선**만 가집니다(`cameras.grid.txt` 의 `P` 줄).
`tools/build_paths.py <한국.osm.pbf>` 가 OpenStreetMap(Geofabrik 한국 추출본)에서 뽑아 붙입니다. 자동 갱신 워크플로가 매월 같이 실행합니다.
- 내가 그 선 위(옆 30m 이내)를 카메라 쪽으로 달릴 때만 1km 앞부터 알립니다. 남은 거리는 도로를 따라 잽니다.
- 선과 안 맞아도 300m 안 바로 정면이면 알립니다(도로 정보가 틀렸을 때의 안전망).
- 상·하행이 따로 그려진 도로의 카메라는 방향각이 확정되어 반대 차선에서 울리지 않습니다.
- `P` 줄이 없는 카메라(근처 도로 없음)는 예전 방식(진행방향 ±30°)으로 판단합니다.
- 수동 CSV 갱신을 하면 `P` 줄이 사라지므로 `build_paths.py` 를 다시 돌려야 합니다(안 돌리면 테스트 `RealDataPathTest` 가 실패해 알려 줍니다).

도로 정보 © OpenStreetMap contributors (ODbL). 앱 화면에도 표시합니다.
