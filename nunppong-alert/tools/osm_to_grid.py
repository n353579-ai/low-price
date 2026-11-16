#!/usr/bin/env python3
"""Overpass(OpenStreetMap) JSON -> 앱 assets 격자 파일.
사용법: python3 tools/osm_to_grid.py <overpass.json> <출력 cameras.grid.txt>
OSM 의 maxspeed, direction(각도/방위) 태그가 있으면 제한속도·방향각으로 쓴다.
"""
import json, math, re, sys

COMPASS = {"N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
           "S": 180, "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337}


def parse_dir(v):
    if not v:
        return -1
    v = v.strip().upper()
    if v in COMPASS:
        return COMPASS[v]
    try:
        f = float(v)
        return int(round(f)) % 360 if 0 <= f < 360 else -1
    except ValueError:
        return -1


def main(src, dst):
    els = json.load(open(src, encoding="utf-8")).get("elements", [])
    seen, withdir, withlim = {}, 0, 0
    for e in els:
        if e.get("type") != "node" or "lat" not in e:
            continue
        lat, lon = e["lat"], e["lon"]
        if not (32.5 <= lat <= 39.5 and 123.5 <= lon <= 132.5):
            continue
        t = e.get("tags", {})
        m = re.search(r"\d+", t.get("maxspeed", ""))
        limit = int(m.group()) if m else 0
        brg = parse_dir(t.get("direction", "") or t.get("camera:direction", ""))
        key = (round(lat, 5), round(lon, 5), brg)
        if key in seen:
            continue
        seen[key] = (lat, lon, limit, brg)
        withdir += brg >= 0
        withlim += limit > 0
    cells = {}
    for lat, lon, limit, brg in seen.values():
        cells.setdefault((math.floor(lat * 100), math.floor(lon * 100)), []).append((lat, lon, limit, brg))
    with open(dst, "w", encoding="utf-8") as f:
        f.write("# nunppong cameras v1 cell=0.01 (source: OpenStreetMap, ODbL)\n")
        for (la, lo) in sorted(cells):
            f.write(f"G {la} {lo}\n")
            for lat, lon, limit, brg in cells[(la, lo)]:
                f.write(f"C {lat:.6f} {lon:.6f} {limit} {brg}\n")
    print(f"OSM elements={len(els)} cameras={len(seen)} cells={len(cells)} withLimit={withlim} withDirection={withdir}")
    return len(seen)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
