#!/usr/bin/env python3
"""사람이 확인한 제한속도 수정(data/limit_fixes.csv)을 격자 파일의 C 줄에 덮어쓴다.

사용법: python3 tools/apply_fixes.py <격자파일> [data/limit_fixes.csv]
CSV 열: lat,lon,limit,note  (# 로 시작하는 줄과 빈 줄은 무시)
가장 가까운 카메라(30m 이내)의 제한속도를 limit 로 바꾼다. 월 자동 갱신이 원본을 다시 받아도
이 파일이 남아 있으므로 수정이 유지된다. 맞는 카메라가 없으면 경고만 하고 넘어간다(실패 아님).
"""
import csv
import math
import sys

MAX_M = 30.0


def dist_m(a_lat, a_lon, b_lat, b_lon):
    dy = (a_lat - b_lat) * 111320.0
    dx = (a_lon - b_lon) * 111320.0 * math.cos(math.radians(a_lat))
    return math.hypot(dx, dy)


def load_fixes(path):
    fixes = []
    try:
        f = open(path, newline="", encoding="utf-8")
    except FileNotFoundError:
        return fixes
    with f:
        lines = [l for l in f if l.strip() and not l.lstrip().startswith("#")]
    for row in csv.reader(lines):
        if row and row[0].strip().lower() == "lat":
            continue
        fixes.append((float(row[0]), float(row[1]), int(row[2]), row[3] if len(row) > 3 else ""))
    return fixes


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    grid = argv[1]
    fixes = load_fixes(argv[2] if len(argv) > 2 else "data/limit_fixes.csv")
    if not fixes:
        print("수정 목록 없음")
        return 0
    with open(grid, encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")
    cams = []  # (줄번호, lat, lon)
    for i, ln in enumerate(lines):
        if ln.startswith("C "):
            p = ln.split()
            cams.append((i, float(p[1]), float(p[2])))
    applied = 0
    for lat, lon, lim, note in fixes:
        best = None
        for i, cl, co in cams:
            if abs(cl - lat) > 0.001 or abs(co - lon) > 0.001:
                continue
            d = dist_m(lat, lon, cl, co)
            if d <= MAX_M and (best is None or d < best[0]):
                best = (d, i)
        if best is None:
            print(f"경고: ({lat},{lon}) 가까운 카메라 없음 - 건너뜀 {note}")
            continue
        cr = "" if lines[best[1]].endswith("") else ""
        p = lines[best[1]].split()
        p[3] = str(lim)
        lines[best[1]] = " ".join(p) + cr
        applied += 1
    with open(grid, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))
    print(f"제한속도 수정 {applied}/{len(fixes)}건 적용")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
