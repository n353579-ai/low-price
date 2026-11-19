#!/usr/bin/env python3
"""실제 도로(OSM) 위를 달리는 가상 주행 경로를 만들어, 틀린 알림이 얼마나 나는지 재는 데 쓴다 (개발용).

사용법:  python3 tools/make_test_routes.py <한국.osm.pbf> <출력 routes.txt> [경로 수=300] [--grid=격자파일] [--seed=7] [--limit-min=0] [--limit-max=999]
         (--limit-min/max: 출발 근처 카메라의 제한속도 범위로 도로 종류를 고른다. 고속도로 100+, 국도 60~90, 시내 ~50)

- 카메라 주변 도로에서 무작위로 출발해 약 4km 를 달린다 (교차로에서 70%는 직진, 30%는 무작위 방향).
- 각 경로에 '이 경로 위에 실제로 있는 카메라'(카메라 좌표가 경로에서 25m 이내)를 정답으로 함께 적는다.
- 앱 쪽 테스트(RouteFalseAlertTest)가 이 파일을 읽어 알림이 정답과 맞는지 센다.
형식:
    R
    p <위도> <경도>        (20m 간격 경로 점)
    c <카메라위도> <카메라경도> <경로점번호>   (경로 위에 있는 카메라와 가장 가까운 점)
"""
import math
import random
import sys

import numpy as np

import assign_bearing as A
import build_paths as B

STEP_M = 20.0
ROUTE_M = 4000.0
ON_ROUTE_M = 25.0


def walk(g, start_u, start_v, rng):
    """u->v 로 출발해 ROUTE_M 만큼 도로를 따라간다. 반환: 점 목록 [(lat,lon)]"""
    pts = [(float(g.lat[start_u]), float(g.lon[start_u])), (float(g.lat[start_v]), float(g.lon[start_v]))]
    total = B.hav(pts[0][0], pts[0][1], pts[1][0], pts[1][1])
    prev, cur = start_u, start_v
    while total < ROUTE_M:
        heading = B.brg(g.lat[prev], g.lon[prev], g.lat[cur], g.lon[cur])
        cands = []
        for n in g.nbrs(g.out_ptr, g.out_idx, cur):
            n = int(n)
            if n == prev:
                continue
            d = A.angle_diff(B.brg(g.lat[cur], g.lon[cur], g.lat[n], g.lon[n]), heading)
            cands.append((d, n))
        if not cands:
            break
        cands.sort()
        if rng.random() < 0.7 or len(cands) == 1:
            n = cands[0][1]
        else:
            n = rng.choice(cands)[1]
        total += B.hav(g.lat[cur], g.lon[cur], g.lat[n], g.lon[n])
        pts.append((float(g.lat[n]), float(g.lon[n])))
        prev, cur = cur, n
    return pts


def resample(pts, step):
    out = [pts[0]]
    carry = 0.0
    for a, b in zip(pts, pts[1:]):
        seg = B.hav(a[0], a[1], b[0], b[1])
        if seg == 0:
            continue
        d = step - carry
        while d <= seg:
            t = d / seg
            out.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
            d += step
        carry = seg - (d - step)
    return out


def main(argv):
    flags = [a for a in argv[1:] if a.startswith("--grid=")]
    opts = {a.split("=")[0]: a.split("=")[1] for a in argv[1:] if a.startswith("--") and "=" in a}
    args = [a for a in argv[1:] if not a.startswith("--")]
    lim_min = int(opts.get("--limit-min", 0))
    lim_max = int(opts.get("--limit-max", 999))
    seed = int(opts.get("--seed", 7))
    if len(args) < 2:
        print(__doc__)
        return 2
    pbf, outp = args[0], args[1]
    n_routes = int(args[2]) if len(args) > 2 else 300
    grid = flags[0][7:] if flags else "app/src/main/assets/cameras.grid.txt"
    rng = random.Random(seed)

    cams = []
    cam_limit = []
    for ln in open(grid, encoding="utf-8"):
        if ln.startswith("C "):
            p = ln.split()
            cams.append((float(p[1]), float(p[2])))
            cam_limit.append(int(p[3]))
    print(f"카메라 {len(cams):,}개, 도로 읽는 중...")
    r = A.Roads()
    r.apply_file(pbf, locations=True)
    segs = r.segs
    idx = A.build_index(segs)
    g = B.Graph(segs)

    cam_cell = {}
    for i, (la, lo) in enumerate(cams):
        cam_cell.setdefault((int(la / 0.005), int(lo / 0.005)), []).append(i)

    def cams_near(la, lo, rad_cells=1):
        cy, cx = int(la / 0.005), int(lo / 0.005)
        out = []
        for y in range(cy - rad_cells, cy + rad_cells + 1):
            for x in range(cx - rad_cells, cx + rad_cells + 1):
                out += cam_cell.get((y, x), [])
        return out

    pool = [i for i in range(len(cams)) if lim_min <= cam_limit[i] <= lim_max]
    starts = rng.sample(pool, min(n_routes * 3, len(pool)))
    made = 0
    with open(outp, "w", encoding="utf-8", newline="\n") as f:
        for ci in starts:
            if made >= n_routes:
                break
            la, lo = cams[ci]
            # 카메라 300m 안의 도로 구간에서 출발 (일방통행이면 허용된 방향으로)
            cy, cx = int(la / A.CELL), int(lo / A.CELL)
            near = []
            for y in range(cy - 1, cy + 2):
                for x in range(cx - 1, cx + 2):
                    near += idx.get((y, x), [])
            if not near:
                continue
            si = rng.choice(near)
            u, v = int(g.node_of_seg_a[si]), int(g.node_of_seg_b[si])
            s = segs[si]
            if s[4] == -1 or (s[4] == 0 and rng.random() < 0.5):
                u, v = v, u
            if s[4] == 1 and False:
                pass
            pts = walk(g, u, v, rng)
            if len(pts) < 4:
                continue
            pts = resample(pts, STEP_M)
            if len(pts) < 100:
                continue
            made += 1
            f.write("R\n")
            for p in pts:
                f.write(f"p {p[0]:.6f} {p[1]:.6f}\n")
            # 정답: 경로에서 25m 안에 있는 카메라
            seen = {}
            for k, p in enumerate(pts):
                for cj in cams_near(p[0], p[1]):
                    if cj in seen:
                        continue
                    if B.hav(p[0], p[1], cams[cj][0], cams[cj][1]) <= ON_ROUTE_M:
                        seen[cj] = k
            for cj, k in seen.items():
                f.write(f"c {cams[cj][0]:.6f} {cams[cj][1]:.6f} {k}\n")
    print(f"경로 {made}개 저장: {outp}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
