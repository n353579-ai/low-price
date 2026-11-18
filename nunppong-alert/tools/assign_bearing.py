#!/usr/bin/env python3
"""카메라 격자 파일(cameras.grid.txt)의 '방향각'을 OpenStreetMap 도로에서 채운다.

사용법:  python3 tools/assign_bearing.py <한국.osm.pbf> [격자파일=app/src/main/assets/cameras.grid.txt]

원리 (앱 안에는 도로 지도를 넣지 않는다 - 카메라마다 '이 카메라는 어느 방향 차량을 단속하나'만 미리 계산):
 1. OSM 에서 자동차가 다니는 도로(고속~일반 도로)만 뽑는다.
 2. 카메라를 가장 가까운 도로 구간(SNAP_M 이내)에 붙인다.
 3. 그 구간이 '일방통행'(상·하행이 따로 그려진 도로 포함)이면 진행방향을 카메라 방향각으로 쓴다.
 4. 양방향 도로이거나, 반대 방향 도로가 거의 같은 거리에 있어 헷갈리면 -1(방향 모름)로 둔다.
    -> 모르는 경우는 예전처럼 동작하므로 지금보다 나빠지지 않는다.
앱은 방향각이 있는 카메라는 내 진행방향이 그 각도와 비슷할 때만 알려 준다 (반대 차선 오탐 제거).
"""
import math
import sys
from collections import defaultdict

import osmium

SIDE_M = 10.0          # 양방향 도로에서는 카메라가 도로 한쪽 옆으로 이만큼 떨어져 있을 때만 방향을 추정한다
SNAP_M = 35.0          # 카메라가 도로에서 이만큼 이내여야 붙인다
AMBIG_M = 10.0         # 반대 방향 도로가 최근접보다 이 안쪽이면 헷갈리는 것으로 본다
CELL = 0.002           # 공간 색인 칸 (약 200m)
DRIVABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
}
M_LAT = 111320.0


class Roads(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.segs = []  # (lat1, lon1, lat2, lon2, oneway)  oneway: 1 정방향, -1 역방향, 0 양방향

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in DRIVABLE:
            return
        ow = w.tags.get("oneway", "")
        if ow in ("yes", "true", "1"):
            one = 1
        elif ow == "-1":
            one = -1
        elif hw in ("motorway", "motorway_link") and ow != "no":
            one = 1
        elif w.tags.get("junction") == "roundabout":
            one = 1
        else:
            one = 0
        pts = []
        for n in w.nodes:
            if n.location.valid():
                pts.append((n.location.lat, n.location.lon))
        for a, b in zip(pts, pts[1:]):
            if a != b:
                self.segs.append((a[0], a[1], b[0], b[1], one))


def build_index(segs):
    idx = defaultdict(list)
    for i, (la1, lo1, la2, lo2, _) in enumerate(segs):
        y0, y1 = sorted((int(la1 / CELL), int(la2 / CELL)))
        x0, x1 = sorted((int(lo1 / CELL), int(lo2 / CELL)))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                idx[(y, x)].append(i)
    return idx


def seg_dist_and_bearing(seg, lat, lon):
    """카메라(lat,lon)에서 구간까지 거리(m)와 구간의 진행 방위각(0~360, 정방향 기준)."""
    la1, lo1, la2, lo2, _ = seg
    mx = M_LAT * math.cos(math.radians(lat))
    ax, ay = (lo1 - lon) * mx, (la1 - lat) * M_LAT
    bx, by = (lo2 - lon) * mx, (la2 - lat) * M_LAT
    dx, dy = bx - ax, by - ay
    ll = dx * dx + dy * dy
    t = 0.0 if ll == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / ll))
    px, py = ax + t * dx, ay + t * dy
    d = math.hypot(px, py)
    brg = (math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0
    # 구간 진행방향 기준 카메라가 오른쪽(+)/왼쪽(-) 몇 m 떨어졌나 (카메라가 원점)
    side = (dx * ay - dy * ax) / math.sqrt(ll) if ll > 0 else 0.0
    return d, brg, side


def angle_diff(a, b):
    d = abs(a - b) % 360.0
    return 360.0 - d if d > 180.0 else d


def assign(segs, idx, lat, lon):
    """-> (방향각 또는 -1, 사유)"""
    cy, cx = int(lat / CELL), int(lon / CELL)
    cand = set()
    for y in range(cy - 1, cy + 2):
        for x in range(cx - 1, cx + 2):
            cand.update(idx.get((y, x), ()))
    best = []
    for i in cand:
        d, brg, side = seg_dist_and_bearing(segs[i], lat, lon)
        if d <= SNAP_M:
            best.append((d, brg, segs[i][4], side))
    if not best:
        return -1, "no_road"
    best.sort()
    d0, b0, one0, side0 = best[0]
    if one0 == 0:
        # 양방향 도로: 카메라는 단속하는 차선 쪽 길가에 서 있는 경우가 대부분이다
        # (방향이 확실한 도로 8천여 개로 검증: 10m 이상 떨어지면 96% 일치).
        if abs(side0) < SIDE_M:
            return -1, "two_way"
        for d, brg, one, _ in best[1:]:
            if d - d0 > AMBIG_M:
                break
            if one != 0 or min(angle_diff(brg, b0), 180.0 - angle_diff(brg, b0)) > 30.0:
                return -1, "ambiguous"
        # 진행방향 b0 기준 카메라가 오른쪽(side>0)에 있으면 b0 방향 차량을 단속 (우측통행)
        # side 부호: 카메라가 원점일 때 구간이 카메라 왼쪽을 지나면 +  -> 카메라는 구간 오른쪽
        return (int(round(b0)) if side0 > 0 else int(round((b0 + 180.0) % 360.0))) % 360, "side"
    f0 = (b0 if one0 == 1 else (b0 + 180.0) % 360.0)
    # 거의 같은 거리에 반대 방향 일방통행/양방향 도로가 있으면 헷갈리는 경우
    for d, brg, one, _ in best[1:]:
        if d - d0 > AMBIG_M:
            break
        if one == 0:
            return -1, "ambiguous"
        f = brg if one == 1 else (brg + 180.0) % 360.0
        if angle_diff(f, f0) > 90.0:
            return -1, "ambiguous"
    return int(round(f0)) % 360, "ok"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    pbf = argv[1]
    grid = argv[2] if len(argv) > 2 else "app/src/main/assets/cameras.grid.txt"
    print("도로 읽는 중 (몇 분 걸려요)...")
    r = Roads()
    r.apply_file(pbf, locations=True)
    segs = r.segs
    print(f"도로 구간 {len(segs):,}개")
    idx = build_index(segs)

    with open(grid, encoding="utf-8") as f:
        lines = f.read().splitlines()
    out, stats = [], defaultdict(int)
    for ln in lines:
        if ln.startswith("C "):
            p = ln.split()
            lat, lon, lim = float(p[1]), float(p[2]), p[3]
            brg, why = assign(segs, idx, lat, lon)
            stats[why] += 1
            out.append(f"C {p[1]} {p[2]} {lim} {brg}")
        else:
            out.append(ln)
    with open(grid, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    total = sum(stats.values())
    print(f"카메라 {total:,}개 처리")
    names = {"ok": "방향 확정", "side": "길가 위치로 추정", "two_way": "양방향 도로(방향 모름)", "ambiguous": "헷갈림(방향 모름)", "no_road": "근처 도로 없음"}
    for k in ("ok", "side", "two_way", "ambiguous", "no_road"):
        print(f"  {names[k]}: {stats[k]:,} ({100 * stats[k] / max(total, 1):.1f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
