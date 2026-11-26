#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Sort streams.json in-place by longitude (West -180° -> East +180°),
with roving/orbital streams (no lat/lon) placed at the end.

Usage: ./sort-streams.py [path/to/streams.json]
"""
import json
import sys
from pathlib import Path


def sort_key(stream: dict):
    lat = stream.get("lat")
    lon = stream.get("lon")
    has_coords = isinstance(lat, (int, float)) and isinstance(lon, (int, float))
    return (0 if has_coords else 1, float(lon) if has_coords else 0.0, stream.get("name", "").lower())


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "streams.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    streams = data.get("streams", [])
    streams.sort(key=sort_key)
    data["streams"] = streams
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Sorted {len(streams)} streams by longitude in {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
