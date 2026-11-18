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
import csv
import math
import re
import sys
from collections import defaultdict

import numpy as np
import osmium

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


WEAK_RANGE_M = 200     # 방향이 확실하지 않은 쪽 접근 경로는 카메라 앞 이 거리 안에서만 알린다


class Names(osmium.SimpleHandler):
    """OSM 지점 이름 -> 좌표들 (설치장소 글자의 '도착지/출발지' 위치를 찾는 데 쓴다)."""

    def __init__(self):
        super().__init__()
        self.n = defaultdict(list)

    def node(self, n):
        nm = n.tags.get("name")
        if nm and n.location.valid():
            self.n[nm].append((n.location.lat, n.location.lon))

    def way(self, w):
        # 학교·건물·공원 같은 영역(way)은 중심점으로 쓴다
        nm = w.tags.get("name")
        if not nm:
            return
        la = lo = c = 0
        for n in w.nodes:
            if n.location.valid():
                la += n.location.lat
                lo += n.location.lon
                c += 1
        if c:
            self.n[nm].append((la / c, lo / c))


def parse_route_text(t):
    """'(풍세교차로→두남교차로)' -> ('풍세교차로', '두남교차로'), 아니면 None"""
    m = re.search(r"\(?\s*([^()→]+?)\s*→\s*([^()→]+?)\s*\)?\s*$", t)
    return (m.group(1), m.group(2)) if m else None


ABBR = (("초교", "초등학교"), ("고교", "고등학교"), ("중교", "중학교"))


def name_variants(n):
    """표기 차이(사거리/교차로, 초교/초등학교 등)를 감안한 이름 후보들"""
    base = re.sub(r"(교차로|사거리|삼거리|오거리|입구|앞|방면|IC|JC)$", "", n)
    out = [n, base] + [base + x for x in ("교차로", "사거리", "삼거리", "오거리", "IC", "입구", "교", "역")]
    for a, b in ABBR:
        if a in n:
            out.append(n.replace(a, b))
    return [v for v in dict.fromkeys(out) if v]


def find_place(names, name, la, lo, lo_m=200, hi_m=8000):
    pts = []
    for v in name_variants(name):
        pts = [p for p in names.get(v, []) if lo_m <= hav(la, lo, p[0], p[1]) <= hi_m]
        if pts:
            break
    if not pts:
        return None
    pts.sort(key=lambda p: hav(la, lo, p[0], p[1]))
    # 가장 가까운 점과 600m 넘게 떨어진 다른 점이 있으면 동명이인 -> 포기
    if any(hav(pts[0][0], pts[0][1], q[0], q[1]) > 600 for q in pts[1:]):
        return None
    return pts[0]


def text_vote(names, itlpc, la, lo):
    """설치장소 글자('A→B')로 추정한 진행방향(방위각), 모르면 None.
    도착지 B 쪽을 향하고 출발지 A 에서 오는 방향. 둘이 서로 어긋나면 포기."""
    ab = parse_route_text(itlpc)
    if not ab:
        return None
    pa = find_place(names, ab[0], la, lo)
    pb = find_place(names, ab[1], la, lo)
    votes = []
    if pb:
        votes.append(brg(la, lo, pb[0], pb[1]))
    if pa:
        votes.append((brg(la, lo, pa[0], pa[1]) + 180.0) % 360.0)
    if not votes:
        return None
    if len(votes) == 2:
        if A.angle_diff(votes[0], votes[1]) > 90.0:
            return None
        x = sum(math.sin(math.radians(v)) for v in votes)
        y = sum(math.cos(math.radians(v)) for v in votes)
        return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0
    return votes[0]


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


def encode(pts, weak=False):
    q = [(int(round(la * 1e5)), int(round(lo * 1e5))) for la, lo in pts]
    out = [q[0][0], q[0][1]]
    for a, b in zip(q, q[1:]):
        out += [b[0] - a[0], b[1] - a[1]]
    return ("Q " if weak else "P ") + " ".join(str(x) for x in out)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    flags = [a for a in argv[1:] if a.startswith("--csv=")]
    argv = [argv[0]] + [a for a in argv[1:] if not a.startswith("--")]
    pbf = argv[1]
    grid = argv[2] if len(argv) > 2 else "app/src/main/assets/cameras.grid.txt"
    names, itlpc = {}, {}
    if flags:
        print("지점 이름 읽는 중...")
        nm = Names()
        nm.apply_file(pbf, locations=True)
        names = nm.n
        with open(flags[0][6:], encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f):
                try:
                    itlpc[(round(float(r["latitude"]), 6), round(float(r["longitude"]), 6))] = r.get("itlpc", "")
                except (ValueError, KeyError):
                    pass
    print("도로 읽는 중...")
    r = A.Roads()
    r.apply_file(pbf, locations=True)
    segs = r.segs
    print(f"도로 구간 {len(segs):,}개 / 그래프 만드는 중...")
    idx = A.build_index(segs)
    g = Graph(segs)

    with open(grid, encoding="utf-8") as f:
        lines = [ln for ln in f.read().splitlines() if not ln.startswith(("P ", "Q "))]
    out, stats = [], defaultdict(int)
    total_pts = 0
    for ln in lines:
        out.append(ln)
        if not ln.startswith("C "):
            continue
        p = ln.split()
        lat, lon = float(p[1]), float(p[2])
        bearing, why, i0 = A.assign(segs, idx, lat, lon)
        # 방향 근거: (1) 상·하행이 따로 그려진 도로(ok) = 확정
        #           (2) 길가 위치(side, 약 96%)  (3) 설치장소 글자 'A→B' 가 가리키는 방향
        # 확정(ok)이거나 (2)(3)이 서로 같은 방향이면 방향각을 기록해 반대 차선을 끝까지 막는다(검증 정확도 99%).
        # 근거가 하나뿐이면 방향각은 쓰지 않고, 반대쪽 접근 경로만 '약한 경로'(200m 안에서만 알림)로 둔다.
        infer = -1          # 길가/글자로 추정한 방향 (약한 근거)
        out_bearing = bearing if why == "ok" else -1
        if why in ("side", "two_way") and i0 is not None:
            tv = text_vote(names, itlpc.get((round(lat, 6), round(lon, 6)), ""), lat, lon) if names else None
            sv = bearing if why == "side" else None
            if sv is not None and tv is not None:
                if A.angle_diff(sv, tv) <= 60.0:
                    out_bearing = bearing = sv
                    stats["strong_side+text"] += 1
                else:
                    stats["conflict_side_text"] += 1
                    bearing = -1
            elif sv is not None:
                infer = sv
                stats["weak_side"] += 1
            elif tv is not None:
                infer = int(round(tv)) % 360
                stats["weak_text"] += 1
        if why != "ok" and out_bearing < 0:
            bearing = -1
        out[-1] = f"C {p[1]} {p[2]} {p[3]} {out_bearing}"
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
            dirs = []   # (a, b, 약한 경로 여부)
            if bearing >= 0:
                dirs.append(((u, v) if A.angle_diff(bearing, b0) < 90.0 else (v, u)) + (False,))
            elif infer >= 0 and i == i0:
                fwd = A.angle_diff(infer, b0) < 90.0
                dirs += [(u, v, not fwd), (v, u, fwd)]
            else:
                dirs += [(u, v, False), (v, u, False)]
            for (a, b, weak) in dirs:
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
                    out.append(encode(pts, weak))
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
