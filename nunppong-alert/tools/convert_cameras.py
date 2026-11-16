#!/usr/bin/env python3
"""공공데이터포털 '전국무인교통단속카메라표준데이터' CSV -> 앱 assets 격자 파일 변환.

사용법:  python3 tools/convert_cameras.py <입력.csv> [출력=app/src/main/assets/cameras.grid.txt] [--all]

- 좌표 없는/범위 밖(한국 밖) 행 제거, 같은 위치 중복 제거
- 기본은 '과속 단속' 행만 사용 (--all 이면 모든 단속 유형)
- 위경도 0.01도 격자로 묶어 저장 (앱은 주변 3x3칸만 조회)
- 컬럼명은 부분 일치로 찾는다: 위도 / 경도 / 제한속도 / 단속구분 / (선택) 방향각
출력 형식:
    # nunppong cameras v1 cell=0.01
    G <latIdx> <lonIdx>
    C <lat> <lon> <제한속도 없으면 0> <방향각 없으면 -1>
"""
import csv
import math
import re
import sys


def read_rows(path):
    last = None
    for enc in ("utf-8-sig", "cp949", "euc-kr"):
        try:
            with open(path, newline="", encoding=enc) as f:
                return list(csv.reader(f))
        except UnicodeDecodeError as e:
            last = e
    raise last


def find_col(header, *needles, exclude=()):
    for i, h in enumerate(header):
        h = h.strip().replace(" ", "")
        if any(n in h for n in needles) and not any(x in h for x in exclude):
            return i
    return None


def to_float(s):
    try:
        return float(str(s).strip())
    except ValueError:
        return None


def first_int(s, default):
    m = re.search(r"\d+", str(s))
    return int(m.group()) if m else default


def is_speed_kind(text):
    t = text.strip()
    if not t:
        return False
    if "과속" in t or "속도" in t:
        return True
    return t in ("1", "3")  # 코드값: 1=속도위반, 3=속도+신호위반 (파일마다 다를 수 있음)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    keep_all = "--all" in argv
    if not args:
        print(__doc__)
        return 2
    src = args[0]
    dst = args[1] if len(args) > 1 else "app/src/main/assets/cameras.grid.txt"

    rows = read_rows(src)
    header = rows[0]
    c_lat = find_col(header, "위도", "latitude", "lat")
    c_lon = find_col(header, "경도", "longitude", "lon")
    c_lim = find_col(header, "제한속도", "limit")
    c_kind = find_col(header, "단속구분", "단속유형", "단속종류")
    c_brg = find_col(header, "방향각", "bearing", "azimuth")
    if c_lat is None or c_lon is None:
        print("위도/경도 컬럼을 찾지 못했습니다. 헤더:", header)
        return 1
    if c_kind is None and not keep_all:
        print("경고: 단속구분 컬럼이 없어 모든 행을 사용합니다.")
        keep_all = True

    seen = {}
    stats = dict(rows=0, no_coord=0, out_of_range=0, not_speed=0, dup=0)
    for r in rows[1:]:
        stats["rows"] += 1
        if len(r) <= max(x for x in (c_lat, c_lon) if x is not None):
            stats["no_coord"] += 1
            continue
        lat, lon = to_float(r[c_lat]), to_float(r[c_lon])
        if lat is None or lon is None or lat == 0 or lon == 0:
            stats["no_coord"] += 1
            continue
        if lat > 90 or lon < 0:  # 위경도가 뒤바뀐 행 보정
            pass
        if not (32.5 <= lat <= 39.5 and 123.5 <= lon <= 132.5):
            if 32.5 <= lon <= 39.5 and 123.5 <= lat <= 132.5:
                lat, lon = lon, lat
            else:
                stats["out_of_range"] += 1
                continue
        if not keep_all and c_kind is not None and not is_speed_kind(r[c_kind] if c_kind < len(r) else ""):
            stats["not_speed"] += 1
            continue
        limit = first_int(r[c_lim], 0) if c_lim is not None and c_lim < len(r) else 0
        brg = -1
        if c_brg is not None and c_brg < len(r):
            b = to_float(r[c_brg])
            if b is not None and 0 <= b < 360:
                brg = int(round(b)) % 360
        key = (round(lat, 5), round(lon, 5))
        if key in seen:
            stats["dup"] += 1
            old = seen[key]
            if old[2] == 0 and limit:  # 제한속도가 있는 쪽을 남긴다
                seen[key] = (lat, lon, limit, brg)
            continue
        seen[key] = (lat, lon, limit, brg)

    cells = {}
    for lat, lon, limit, brg in seen.values():
        k = (math.floor(lat * 100), math.floor(lon * 100))  # 앱(Kotlin)과 동일한 식
        cells.setdefault(k, []).append((lat, lon, limit, brg))

    with open(dst, "w", encoding="utf-8") as f:
        f.write("# nunppong cameras v1 cell=0.01\n")
        for (la, lo) in sorted(cells):
            f.write(f"G {la} {lo}\n")
            for lat, lon, limit, brg in cells[(la, lo)]:
                f.write(f"C {lat:.6f} {lon:.6f} {limit} {brg}\n")

    print(f"입력 {stats['rows']}행 -> 카메라 {len(seen)}개, 격자 {len(cells)}칸")
    print("제외:", {k: v for k, v in stats.items() if k != "rows"})
    print("방향각 컬럼:", "있음" if c_brg is not None else "없음(방향 필터는 내 진행방향 기준만 적용)")
    print("저장:", dst)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
