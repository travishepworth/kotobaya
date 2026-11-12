"""Aggregate-only checks on a jpdb export (duplicate vids, grade transitions, time span). No content printed."""
import datetime
import json
import sys
from collections import Counter, defaultdict

with open(sys.argv[1], encoding="utf-8") as f:
    d = json.load(f)
rows = d["cards_vocabulary_jp_en"]
g = defaultdict(list)
for r in rows:
    g[r["vid"]].append(r)
dups = {k: v for k, v in g.items() if len(v) > 1}
print("dup vids", len(dups), "group sizes", Counter(len(v) for v in dups.values()))
print("dups same spelling+reading", sum(1 for v in dups.values() if len({(x["spelling"], x["reading"]) for x in v}) == 1))
print("dups identical reviews", sum(1 for v in dups.values() if len({json.dumps(x["reviews"]) for x in v}) == 1))
print("vid>=2e9 rows", sum(1 for r in rows if r["vid"] >= 2_000_000_000),
      "max vid below 2e9", max(r["vid"] for r in rows if r["vid"] < 2e9))
tr = Counter()
for r in rows:
    gs = [x["grade"] for x in r["reviews"]]
    for a, b in zip(gs, gs[1:]):
        tr[(a, b)] += 1
print({f"{a}>{b}": c for (a, b), c in tr.most_common(14)})
print("known after first", sum(1 for r in rows for x in r["reviews"][1:] if x["grade"] == "known"),
      "unknown after first", sum(1 for r in rows for x in r["reviews"][1:] if x["grade"] == "unknown"))
print("single-event rows", sum(1 for r in rows if len(r["reviews"]) == 1))
print("rows whose last event is known", sum(1 for r in rows if r["reviews"][-1]["grade"] == "known"))
print("rows with pass/fail: review counts", [len(r["reviews"]) for r in rows if any(x["grade"] in ("pass", "fail") for x in r["reviews"])])
ts = [x["timestamp"] for r in rows for x in r["reviews"]]
iso = lambda t: datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()
print("span", iso(min(ts)), iso(max(ts)))
print("spelling==reading rows", sum(1 for r in rows if r["spelling"] == r["reading"]))
print("keys per vocab row", Counter(tuple(sorted(r)) for r in rows))
