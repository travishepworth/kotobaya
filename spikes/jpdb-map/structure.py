"""Print the STRUCTURE of a jpdb export (no words, no ids). Usage: python structure.py <export.json>

Enum values are only printed for short, ASCII, low-cardinality string fields, so
spellings/readings/ids never reach the output.
"""
import json
import sys
from collections import Counter, defaultdict


def walk(node, path, stats):
    s = stats[path]
    if isinstance(node, dict):
        s["types"]["object"] += 1
        for k, v in node.items():
            walk(v, f"{path}.{k}", stats)
    elif isinstance(node, list):
        s["types"]["array"] += 1
        s["lens"].append(len(node))
        for v in node:
            walk(v, f"{path}[]", stats)
    else:
        s["types"][type(node).__name__] += 1
        if isinstance(node, (int, float)) and not isinstance(node, bool):
            s["min"] = node if s["min"] is None else min(s["min"], node)
            s["max"] = node if s["max"] is None else max(s["max"], node)
        if isinstance(node, str):
            s["strlens"].append(len(node))
            if len(s["distinct"]) <= 40:
                s["distinct"].add(node)


def main(p):
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    stats = defaultdict(
        lambda: {"types": Counter(), "lens": [], "strlens": [], "distinct": set(), "min": None, "max": None}
    )
    walk(d, "$", stats)
    for path, s in sorted(stats.items()):
        line = f"{path}: {dict(s['types'])}"
        if s["lens"]:
            line += f" len[min={min(s['lens'])},max={max(s['lens'])},n={len(s['lens'])},sum={sum(s['lens'])}]"
        if s["min"] is not None:
            line += f" range[{s['min']}..{s['max']}]"
        if s["strlens"]:
            line += f" strlen[{min(s['strlens'])}..{max(s['strlens'])}]"
            dist = s["distinct"]
            if len(dist) <= 12 and all(len(x) <= 16 and x.isascii() for x in dist):
                line += f" enum={sorted(dist)}"
        print(line)


main(sys.argv[1])
