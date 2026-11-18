#!/usr/bin/env python3
"""카메라마다 '카메라 앞 도로 선'(접근 경로)을 OpenStreetMap 에서 뽑아 격자 파일에 넣는다.

사용법:  python3 tools/build_paths.py <한국.osm.pbf> [격자파일=app/src/main/assets/cameras.grid.txt]

앱은 지도를 갖지 않는다. 대신 카메라마다 '이 카메라에 다가가는 도로 선' 을 가진다.
 - 내 위치가 그 선 위(옆 25m 이내)에 있고, 선을 따라 카메라 쪽으로 달릴 때만 알린다.
   -> 옆 도로·평행 도로·커브 바깥에서 울리지 않고, 남은 거리도 도로를 따라 잰다.
 - 방향을 아는 카메라는 선이 1개(그 방향), 모르면 양쪽 접근 경로 2개.

격자 파일 형식 (v2):  C 줄 바로 뒤에 P 줄이 0~2개 붙는다.
    P <위도*1e5> <경도*1e5> <dlat> <dlon> <dlat> <dlon> ...   (정수, 처음 점은 절대값, 나머지는 앞 점과의 차)
    마지막 점이 카메라가 붙은 도로 위의 위치다. 점은 접근하는 순서대로 나열한다.
"""
import math
import sys
from collections import defaultdict

import numpy as np

import assign_bearing as A

PATH_M = 1100.0        # 카메라 앞으로 이만큼의 도로를 뽑는다 (감지 거리 1km + 여유)
TURN_MAX = 40.0        # 교차로에서 이 각도 이상 꺾이면 '다른 도로'로 보고 선을 끊는다
TURN_MAX_SHORT = 60.0  # 10m 미만의 짧은 구간은 각도가 부정확하므로 조금 더 봐준다
BRANCH_TURN = 30.0     # 이 각도 이내로 합쳐지는 다른 길은 가지 경로로 따로 만든다 (합류 램프)
HEAD_BASE_M = 20.0     # 진행방향을 잴 때 쓰는 기준 길이
CAND_EXTRA_M = 15.0    # 가장 가까운 도로보다 이만큼 이내로 가까운 다른 도로도 '카메라 도로 후보'로 본다
MAX_PATHS = 6          # 카메라 하나당 경로 최대 개수
SIMPLIFY_M = 6.0       # 선 단순화 허용 오차
M_LAT = A.M_LAT


def hav(lat1, lon1, lat2, lon2):
    mx = M_LAT * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lon2 - lon1) * mx, (lat2 - lat1) * M_LAT)


def brg(lat1, lon1, lat2, lon2):
    mx = M_LAT * math.cos(math.radians((lat1 + lat2) / 2))
    return (math.degrees(math.atan2((lon2 - lon1) * mx, (lat2 - lat1) * M_LAT)) + 360.0) % 360.0


def simplify(pts, tol):
    """Douglas-Peucker (미터 근사)."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    lat0 = pts[0][0]
    mx = M_LAT * math.cos(math.radians(lat0))
    xy = [((p[1]) * mx, p[0] * M_LAT) for p in pts]
    while stack:
        i, j = stack.pop()
        ax, ay = xy[i]
        bx, by = xy[j]
        dx, dy = bx - ax, by - ay
        ll = dx * dx + dy * dy
        best, bk = -1.0, -1
        for k in range(i + 1, j):
            px, py = xy[k]
            if ll == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
                d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > best:
                best, bk = d, k
        if best > tol:
            keep[bk] = True
            stack += [(i, bk), (bk, j)]
    return [p for p, k in zip(pts, keep) if k]


class Graph:
    """도로 구간을 노드 그래프로. out[u] = u에서 갈 수 있는 이웃, inn[v] = v로 들어올 수 있는 이웃."""

    def __init__(self, segs):
        na = np.fromiter((s[5] for s in segs), dtype=np.int64, count=len(segs))
        nb = np.fromiter((s[6] for s in segs), dtype=np.int64, count=len(segs))
        one = np.fromiter((s[4] for s in segs), dtype=np.int8, count=len(segs))
        lat = np.empty(len(segs) * 2)
        lon = np.empty(len(segs) * 2)
        lat[0::2] = [s[0] for s in segs]
        lat[1::2] = [s[2] for s in segs]
        lon[0::2] = [s[1] for s in segs]
        lon[1::2] = [s[3] for s in segs]
        ids = np.empty(len(segs) * 2, dtype=np.int64)
        ids[0::2] = na
        ids[1::2] = nb
        uniq, first = np.unique(ids, return_index=True)
        self.lat = lat[first]
        self.lon = lon[first]
        ia = np.searchsorted(uniq, na)
        ib = np.searchsorted(uniq, nb)
        # u->v 허용 간선
        fwd = one >= 0   # a->b 가능 (양방향 0, 정방향 1)
        bwd = one <= 0   # b->a 가능 (양방향 0, 역방향 -1)
        U = np.concatenate([ia[fwd], ib[bwd]])
        V = np.concatenate([ib[fwd], ia[bwd]])
        n = len(uniq)
        self.out_ptr, self.out_idx = self._csr(U, V, n)
        self.in_ptr, self.in_idx = self._csr(V, U, n)
        self.node_of_seg_a = ia
        self.node_of_seg_b = ib

    @staticmethod
    def _csr(key, val, n):
        order = np.argsort(key, kind="stable")
        key, val = key[order], val[order]
        ptr = np.zeros(n + 1, dtype=np.int64)
        np.add.at(ptr, key + 1, 1)
        np.cumsum(ptr, out=ptr)
        return ptr, val

    def nbrs(self, ptr, idx, u):
        return idx[ptr[u]:ptr[u + 1]]

    def approach_paths(self, u, v, cam_lat, cam_lon, budget, max_paths):
        """u->v 방향으로 달려 카메라에 닿는 접근 경로들을 거꾸로 따라가며 만든다.
        합류(비스듬히 들어오는 길)는 가지로 따로 만든다. 반환: 경로 목록, 각 경로는 접근 순서의 노드/점.
        각 경로 = ([(lat,lon)...처음~카메라], [지나는 방향 간선 (a,b)...])"""
        done = []
        # 스택 항목: (거꾸로 쌓은 점들[카메라 먼저], 거꾸로 쌓은 노드들, cur, 누적거리, 방문)
        start_pts = [(cam_lat, cam_lon), (float(self.lat[u]), float(self.lon[u]))]
        d0 = hav(cam_lat, cam_lon, self.lat[u], self.lon[u])
        stack = [(start_pts, [v, u], d0, {u, v})]
        while stack:
            pts, nodes, total, visited = stack.pop()
            while True:
                cur = nodes[-1]
                if total >= budget:
                    break
                # 진행방향: cur 에서 카메라 쪽으로 약 20m 앞 지점을 본다 (짧은 구간의 꺾임 잡음 제거)
                acc, k = 0.0, len(pts) - 1
                while k > 0 and acc < HEAD_BASE_M:
                    acc += hav(pts[k][0], pts[k][1], pts[k - 1][0], pts[k - 1][1])
                    k -= 1
                heading = brg(pts[-1][0], pts[-1][1], pts[k][0], pts[k][1])
                cands = []
                for p in self.nbrs(self.in_ptr, self.in_idx, cur):
                    p = int(p)
                    if p in visited:
                        continue
                    el = hav(self.lat[p], self.lon[p], self.lat[cur], self.lon[cur])
                    diff = A.angle_diff(brg(self.lat[p], self.lon[p], self.lat[cur], self.lon[cur]), heading)
                    cands.append((diff, p, el))
                cands.sort()
                if not cands:
                    break
                diff, best, el = cands[0]
                if diff > (TURN_MAX_SHORT if el < 10.0 else TURN_MAX):
                    break
                # 가지: 비스듬히 합쳐지는 다른 길 (합류 램프 등)
                for d2, p2, el2 in cands[1:]:
                    if d2 > BRANCH_TURN or len(done) + len(stack) + 1 >= max_paths:
                        break
                    stack.append((
                        pts + [(float(self.lat[p2]), float(self.lon[p2]))], nodes + [p2],
                        total + el2, visited | {p2},
                    ))
                total += el
                pts = pts + [(float(self.lat[best]), float(self.lon[best]))]
                nodes = nodes + [best]
                visited = visited | {best}
            done.append((pts[::-1], nodes[::-1]))
            if len(done) >= max_paths:
                break
        return done


def encode(pts):
    q = [(int(round(la * 1e5)), int(round(lo * 1e5))) for la, lo in pts]
    out = [q[0][0], q[0][1]]
    for a, b in zip(q, q[1:]):
        out += [b[0] - a[0], b[1] - a[1]]
    return "P " + " ".join(str(x) for x in out)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    pbf = argv[1]
    grid = argv[2] if len(argv) > 2 else "app/src/main/assets/cameras.grid.txt"
    print("도로 읽는 중...")
    r = A.Roads()
    r.apply_file(pbf, locations=True)
    segs = r.segs
    print(f"도로 구간 {len(segs):,}개 / 그래프 만드는 중...")
    idx = A.build_index(segs)
    g = Graph(segs)

    with open(grid, encoding="utf-8") as f:
        lines = [ln for ln in f.read().splitlines() if not ln.startswith("P ")]
    out, stats = [], defaultdict(int)
    total_pts = 0
    for ln in lines:
        out.append(ln)
        if not ln.startswith("C "):
            continue
        p = ln.split()
        lat, lon = float(p[1]), float(p[2])
        bearing, why, i0 = A.assign(segs, idx, lat, lon)
        # 방향각은 '상·하행이 따로 그려진 도로'로 확정된 경우에만 파일에 쓴다.
        # 길가 위치로 추정한 방향(side, 약 96%)은 경로만 그 방향으로 만들고 방향각은 -1 로 둔다
        # -> 추정이 틀려도 300m 안전망이 양쪽 방향 모두 잡아 준다.
        out[-1] = f"C {p[1]} {p[2]} {p[3]} {bearing if why == 'ok' else -1}"
        stats[why] += 1
        if i0 is None:
            stats["no_path"] += 1
            continue
        # 후보 구간: 가장 가까운 구간 + (방향을 모를 때만) 비슷하게 가까운 다른 도로
        cands = [(A.seg_dist_and_bearing(segs[i0], lat, lon)[0], i0)]
        if bearing < 0:
            cy, cx = int(lat / A.CELL), int(lon / A.CELL)
            near = set()
            for y in range(cy - 1, cy + 2):
                for x in range(cx - 1, cx + 2):
                    near.update(idx.get((y, x), ()))
            d0 = cands[0][0]
            more = []
            for i in near:
                if i == i0:
                    continue
                d = A.seg_dist_and_bearing(segs[i], lat, lon)[0]
                if d <= min(A.SNAP_M, d0 + CAND_EXTRA_M):
                    more.append((d, i))
            cands += sorted(more)
        covered = set()   # 이미 만든 경로가 지나는 방향 간선
        made = 0
        for _, i in cands:
            if made >= MAX_PATHS:
                break
            s = segs[i]
            u, v = int(g.node_of_seg_a[i]), int(g.node_of_seg_b[i])
            d, b0, side = A.seg_dist_and_bearing(s, lat, lon)
            # 구간 위 카메라 위치(수선의 발)
            mx = M_LAT * math.cos(math.radians(lat))
            ax, ay = (s[1] - lon) * mx, (s[0] - lat) * M_LAT
            bx, by = (s[3] - lon) * mx, (s[2] - lat) * M_LAT
            dx, dy = bx - ax, by - ay
            ll = dx * dx + dy * dy
            t = 0.0 if ll == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / ll))
            clat = s[0] + t * (s[2] - s[0])
            clon = s[1] + t * (s[3] - s[1])
            dirs = []
            if bearing >= 0:
                dirs.append((u, v) if A.angle_diff(bearing, b0) < 90.0 else (v, u))
            else:
                dirs += [(u, v), (v, u)]
            for (a, b) in dirs:
                # 이 구간을 a->b 로 달릴 수 있는가 (일방통행이면 허용된 방향만)
                ok = s[4] == 0 or (s[4] == 1 and (a, b) == (u, v)) or (s[4] == -1 and (a, b) == (v, u))
                if not ok or (a, b) in covered:
                    continue
                for pts, nodes in g.approach_paths(a, b, clat, clon, PATH_M, MAX_PATHS - made):
                    covered.update(zip(nodes, nodes[1:]))
                    pts = simplify(pts, SIMPLIFY_M)
                    if len(pts) < 2:
                        continue
                    total_pts += len(pts)
                    out.append(encode(pts))
                    stats["paths"] += 1
                    made += 1
        stats["cams_with_path"] += 1 if made else 0
    with open(grid, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print(f"경로 {stats['paths']:,}개, 점 {total_pts:,}개")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
