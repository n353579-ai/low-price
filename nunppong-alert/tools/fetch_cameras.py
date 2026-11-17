#!/usr/bin/env python3
"""공공데이터포털 오픈API에서 전국무인교통단속카메라표준데이터를 받아 CSV로 저장.

사용법: DATA_GO_KR_KEY=<인증키> python3 tools/fetch_cameras.py <출력.csv>
- 인증키는 환경변수로만 받는다 (로그에 찍지 않음).
- 전부 받지 못하면(수가 너무 적으면) 실패 코드로 끝내 기존 데이터를 지키게 한다.
"""
import csv
import json
import os
import sys
import time
import urllib.parse
import urllib.request

URL = "https://api.data.go.kr/openapi/tn_pubr_public_unmanned_traffic_camera_api"
PAGE = 1000
MIN_ROWS = 20000


def get(key, page):
    q = urllib.parse.urlencode({"pageNo": page, "numOfRows": PAGE, "type": "json"})
    # 키는 이미 인코딩된 값일 수 있어 unquote 후 한 번만 인코딩
    q += "&serviceKey=" + urllib.parse.quote(urllib.parse.unquote(key), safe="")
    last = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(URL + "?" + q, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # 키가 URL에 들어 있으므로 예외 문구에서 제거
            last = str(e).replace(key, "***")
            time.sleep(3 * (attempt + 1))
    raise SystemExit("API 호출 실패: " + str(last))


def main(argv):
    key = os.environ.get("DATA_GO_KR_KEY", "").strip()
    if not key or len(argv) < 2:
        print(__doc__)
        return 2
    items, total, page = [], None, 1
    while True:
        d = get(key, page)
        body = d.get("response", {}).get("body")
        if body is None:
            hdr = d.get("response", {}).get("header", d)
            raise SystemExit("응답 이상: " + json.dumps(hdr, ensure_ascii=False)[:300])
        total = int(body.get("totalCount", 0))
        got = body.get("items") or []
        if isinstance(got, dict):
            got = got.get("item", [])
        items += got
        if page == 1 and got:
            print("필드:", sorted(got[0].keys()))
        if not got or len(items) >= total:
            break
        page += 1
    print(f"받은 행 {len(items)} / 전체 {total}")
    if len(items) < MIN_ROWS or len(items) < total:
        raise SystemExit("데이터가 너무 적거나 일부만 받아서 중단 (기존 데이터 유지)")
    cols = sorted({k for it in items for k in it})
    with open(argv[1], "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for it in items:
            w.writerow([it.get(c, "") for c in cols])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
