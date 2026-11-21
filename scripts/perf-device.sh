#!/usr/bin/env bash
# PERF-GATE: on-device timing of the local API, from the `KTB-PERF` logcat lines of the installed APK.
#
#   scripts/perf-device.sh [-n N] [--user ID] [--pkg PACKAGE] [--speed] [--serial SERIAL] [--out FILE]
#   scripts/perf-device.sh --from-log saved-log.txt [--version X] [--out FILE]
#
# Runs N cold (force-stop, start) and N warm (HOME key, start again) launches, scrapes KTB-PERF, prints a median/p90
# table per handler and per phase, and writes it to plan/perf/device-<versionName>-<date>[-speed].md.
# Exit 1 when a median misses a target. --speed first runs `cmd package compile -m speed -f` (separates JIT cost).
# --from-log parses a saved log instead of driving a phone, and writes a file only with --out. Lines `#RUN cold|warm <i>`
# split the runs (the live mode writes them; KEEP_LOG=1 keeps that log next to the table); a raw logcat is one warm run.
#
# Targets (ms; checked on medians of `total`; edit the TARGETS table in the python part below, they are listed here too)
#   id          what                                                      limit
#   cold_queue  cold runs: first getReviewQueue                             400
#   warm_queue  warm runs: first getReviewQueue                             150
#   warm_home   warm runs: each Home handler (first call per run)           300
#   answer      answerReview (only when answers were made during the runs)   50
set -euo pipefail

N=5; USER_ID=""; PKG="com.ichi2.anki"; SPEED=0; SERIAL=""; OUT=""; FROM_LOG=""; VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    -n) N="$2"; shift 2;;
    --user) USER_ID="$2"; shift 2;;
    --pkg) PKG="$2"; shift 2;;
    --speed) SPEED=1; shift;;
    --serial) SERIAL="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --from-log) FROM_LOG="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    -h|--help) sed -n 2,19p "$0"; exit 0;;
    *) echo "perf-device: unknown argument $1" >&2; exit 2;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
DAY="$(date +%F)"

if [ -n "$FROM_LOG" ]; then
  [ -r "$FROM_LOG" ] || { echo "perf-device: cannot read $FROM_LOG" >&2; exit 2; }
  cp "$FROM_LOG" "$LOG"; VERSION="${VERSION:-unknown}"
else
  ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
  reset_log_tag() { "${ADB[@]}" shell setprop log.tag.KTB-PERF "" >/dev/null 2>&1 || true; }
  trap 'reset_log_tag; rm -f "$LOG"' EXIT   # also when a run fails partway (set -e) or is interrupted
  UARG=(); [ -n "$USER_ID" ] && UARG=(--user "$USER_ID")
  VERSION="$("${ADB[@]}" shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"
  VERSION="${VERSION:-unknown}"
  "${ADB[@]}" shell setprop log.tag.KTB-PERF DEBUG   # log every call, not only the slow ones
  if [ "$SPEED" = 1 ]; then "${ADB[@]}" shell cmd package compile -m speed -f "$PKG" >&2; fi
  start() { "${ADB[@]}" shell am start "${UARG[@]}" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >/dev/null; }
  run() { # kind index: the log of one launch
    "${ADB[@]}" logcat -c
    if [ "$1" = cold ]; then "${ADB[@]}" shell am force-stop "${UARG[@]}" "$PKG"; else "${ADB[@]}" shell input keyevent KEYCODE_HOME; sleep 1; fi
    start; sleep "${SETTLE:-10}"
    echo "#RUN $1 $2" >> "$LOG"; "${ADB[@]}" logcat -d -s KTB-PERF | tr -d '\r' >> "$LOG"
  }
  start; sleep "${SETTLE:-10}"   # warm runs need the app alive once
  for i in $(seq 1 "$N"); do run cold "$i"; done
  for i in $(seq 1 "$N"); do run warm "$i"; done
  reset_log_tag
  mkdir -p "$ROOT/plan/perf"
  [ "${KEEP_LOG:-0}" = 1 ] && cp "$LOG" "$ROOT/plan/perf/device-$VERSION-$DAY.log"
  if [ -z "$OUT" ]; then
    SUFFIX=""; [ "$SPEED" = 1 ] && SUFFIX="-speed"
    OUT="$ROOT/plan/perf/device-$VERSION-$DAY$SUFFIX.md"
  fi
fi

python3 -I - "$LOG" "$VERSION" "$DAY" "$SPEED" "$OUT" <<'PY'
import math, os, re, sys
from statistics import median

log, version, day, speed, out = sys.argv[1:6]
TARGETS = {  # id -> limit in ms
    "cold_queue": 400,  # cold: first getReviewQueue
    "warm_queue": 150,  # warm: getReviewQueue
    "warm_home": 300,   # warm: each Home handler
    "answer": 50,       # answerReview (answer to next card)
}
HOME = {"getStatsSummary", "getStatsHeatmap", "getStatsRetention", "listHotWords", "listHotKanji", "listLeeches", "listDecks"}
PHASES = ["gate", "lane", "mutex", "compute", "total", "encode"]
CALL = re.compile(r"KTB-PERF\S*:?\s+call op=(\S+) status=(\d+) gate=(\d+) lane=(\d+) mutex=(\d+) compute=(\d+) total=(\d+) encode=(\d+)")
GATE = re.compile(r"KTB-PERF\S*:?\s+gate open after (\d+) ms")
RUN = re.compile(r"^#RUN (cold|warm) (\d+)")

runs = []  # (kind, index, [call dicts], [gate-open ms])
for line in open(log, errors="replace"):
    m = RUN.match(line)
    if m:
        runs.append((m.group(1), int(m.group(2)), [], []))
        continue
    if not runs:
        runs.append(("warm", 0, [], []))  # a raw logcat: one unlabelled run, treated as warm
    m = CALL.search(line)
    if m:
        v = list(map(int, m.groups()[1:]))
        runs[-1][2].append(dict(op=m.group(1), status=v[0], **dict(zip(PHASES, v[1:]))))
        continue
    m = GATE.search(line)
    if m:
        runs[-1][3].append(int(m.group(1)))


def p90(xs):
    s = sorted(xs)
    return s[max(0, math.ceil(0.9 * len(s)) - 1)]


def firsts(kind):  # per run, the first call of each op
    res = {}
    for k, _, calls, _ in runs:
        if k != kind:
            continue
        seen = set()
        for c in calls:
            if c["op"] not in seen:
                seen.add(c["op"])
                res.setdefault(c["op"], []).append(c)
    return res


ncold = sum(1 for r in runs if r[0] == "cold")
nwarm = sum(1 for r in runs if r[0] == "warm")
lines = [f"# Device timing {version} {day}" + (" (compiled -m speed)" if speed == "1" else ""), "",
         f"Runs: {ncold} cold, {nwarm} warm. Times in ms. gate = readiness gate, lane = background lane queue, "
         "mutex = same-request lock, compute = the handler, total = whole handle, encode = JSON encode + post.", ""]
for k in ("cold", "warm"):
    g = [x for kk, _, _, gs in runs if kk == k for x in gs]
    if g:
        lines.append(f"- gate open after, {k}: median {median(g):.0f}, p90 {p90(g)} (n={len(g)})")
lines += ["", "## Per handler and phase (first call per run; median / p90)", "",
          "| kind | handler | n | " + " | ".join(PHASES) + " |", "|---|---|---|" + "---|" * len(PHASES)]
medians = {}
for kind in ("cold", "warm"):
    for op, cs in sorted(firsts(kind).items()):
        cells = [f"{median(c[p] for c in cs):.0f} / {p90([c[p] for c in cs])}" for p in PHASES]
        lines.append(f"| {kind} | {op} | {len(cs)} | " + " | ".join(cells) + " |")
        medians[(kind, op)] = median(c["total"] for c in cs)
answers = [c["total"] for _, _, cs, _ in runs for c in cs if c["op"] == "answerReview"]

results = []  # (description, measured, limit, ok or None when there is no data)


def check(desc, limit, value):
    results.append((desc, "n/a" if value is None else f"{value:.0f}", limit, None if value is None else value <= limit))


check("cold: first getReviewQueue", TARGETS["cold_queue"], medians.get(("cold", "getReviewQueue")))
check("warm: getReviewQueue", TARGETS["warm_queue"], medians.get(("warm", "getReviewQueue")))
homes = [(op, m) for (k, op), m in sorted(medians.items()) if k == "warm" and op in HOME]
if not homes:
    check("warm: Home handlers", TARGETS["warm_home"], None)
for op, m in homes:
    check(f"warm: {op}", TARGETS["warm_home"], m)
check("answerReview (answer to next card)", TARGETS["answer"], median(answers) if answers else None)

lines += ["", "## Targets (medians of total)", "", "| target | measured | limit | result |", "|---|---|---|---|"]
for desc, val, limit, ok in results:
    lines.append(f"| {desc} | {val} | {limit} | {'n/a (no data)' if ok is None else 'PASS' if ok else 'FAIL'} |")
text = "\n".join(lines) + "\n"
print(text)
if out:
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    open(out, "w").write(text)
    print(f"wrote {out}", file=sys.stderr)
sys.exit(1 if any(r[3] is False for r in results) else 0)
PY
