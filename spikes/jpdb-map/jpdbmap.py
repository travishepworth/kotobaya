"""jpdb -> JMdict mapping spike.

Subcommands:
  map <export.json> [--jmdict PATH]   map export vocab to JMdict seq numbers; prints AGGREGATE counts only
                                      (with --samples N prints N unmapped samples: only use on public data)
  stats <export.json>                 aggregate review stats (grade counts, ordering, dupes); no content
  deck-api <deck_id>                  fetch deck via jpdb API v1 (needs JPDB_API_KEY)
  deck-page <url>                     fetch a public vocabulary-list page (>= 2 s between requests, cached)
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path
from typing import Callable

REPO = Path(__file__).resolve().parents[2]
CACHE_DIR = Path(os.environ.get("KOTOBAYA_JPDB_CACHE", Path.home() / ".cache" / "kotobaya-jpdb"))
FULL_JMDICT = Path.home() / ".cache" / "kotobaya-fixtures" / "JMdict_e.gz"
SUBSET_JMDICT = REPO / "fixtures" / "dict" / "JMdict_subset.xml"
MIN_INTERVAL_S = 2.0
API_BASE = "https://jpdb.io/api/v1"


# ---------------------------------------------------------------- JMdict index

def kata_to_hira(s: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def norm(s: str) -> str:
    return kata_to_hira(unicodedata.normalize("NFKC", s)).strip()


class Index:
    def __init__(self) -> None:
        self.by_pair: dict[tuple[str, str], set[int]] = defaultdict(set)
        self.by_spelling: dict[str, set[int]] = defaultdict(set)
        self.by_reading: dict[str, set[int]] = defaultdict(set)
        self.kana_only: set[int] = set()  # seqs flagged usually-kana (uk)
        self.entries = 0
        self.seqs: set[int] = set()

    def add(self, seq: int, kebs: list[str], rebs: list[str], uk: bool) -> None:
        self.entries += 1
        self.seqs.add(seq)
        if uk:
            self.kana_only.add(seq)
        for r in rebs:
            self.by_reading[norm(r)].add(seq)
        for k in kebs:
            self.by_spelling[norm(k)].add(seq)
            for r in rebs:
                self.by_pair[(norm(k), norm(r))].add(seq)


def load_jmdict(path: Path) -> Index:
    idx = Index()
    opener: Callable = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rb") as f:
        # JMdict uses entity-defined tags in its DTD; keep them as text via a lenient parse.
        data = f.read().decode("utf-8")
    # replace entity references (&uk; etc.) so ElementTree does not choke; strip the DTD
    ents = dict(re.findall(r'<!ENTITY (\S+) "([^"]*)">', data))
    data = re.sub(r"<!DOCTYPE.*?\]>", "", data, flags=re.S)
    data = re.sub(r"&([A-Za-z0-9_-]+);", lambda m: "@" + m.group(1) + "@" if m.group(1) in ents else m.group(0), data)
    root = ET.fromstring(data)
    for e in root.iter("entry"):
        seq = int(e.findtext("ent_seq"))
        kebs = [k.findtext("keb") for k in e.findall("k_ele")]
        rebs = [r.findtext("reb") for r in e.findall("r_ele")]
        uk = any((m.text or "") == "@uk@" for m in e.iter("misc"))
        idx.add(seq, kebs, rebs, uk)
    return idx


def map_word(idx: Index, spelling: str, reading: str, vid: int | None = None) -> tuple[str, list[int]]:
    """Return (method, candidate seqs). Methods in decreasing confidence.

    jpdb's vid is the JMdict ent_seq for ordinary words (verified on live deck pages), so a vid
    that is a known seq AND agrees with spelling/reading wins; otherwise fall back to text matching.
    """
    s, r = norm(spelling), norm(reading)
    if vid in idx.seqs and vid in idx.by_pair.get((s, r), ()):
        return "vid=seq (confirmed by text)", [vid]
    if vid in idx.seqs and vid in idx.by_spelling.get(s, ()):
        return "vid=seq (spelling only)", [vid]
    if vid in idx.seqs and s == r and vid in idx.by_reading.get(s, ()):
        return "vid=seq (kana)", [vid]
    if (s, r) in idx.by_pair:
        return "spelling+reading", sorted(idx.by_pair[(s, r)])
    if s == r and s in idx.by_reading:  # kana-only jpdb word
        c = idx.by_reading[s]
        uk = sorted(x for x in c if x in idx.kana_only)
        return "kana-reading", uk or sorted(c)
    if s in idx.by_spelling:
        return "spelling-only", sorted(idx.by_spelling[s])
    return "unmapped", []


def cmd_map(args: argparse.Namespace) -> None:
    path = Path(args.jmdict) if args.jmdict else (FULL_JMDICT if FULL_JMDICT.exists() else SUBSET_JMDICT)
    idx = load_jmdict(path)
    print(f"jmdict: {path.name} entries={idx.entries}")
    d = json.load(open(args.export, encoding="utf-8"))
    rows = d.get("cards_vocabulary_jp_en", []) + d.get("cards_vocabulary_en_jp", [])
    methods: Counter[str] = Counter()
    ambiguous = 0
    unmapped: list[tuple[str, str]] = []
    for w in rows:
        m, seqs = map_word(idx, w["spelling"], w["reading"], w.get("vid"))
        methods[m] += 1
        if len(seqs) > 1:
            ambiguous += 1
        if m == "unmapped":
            unmapped.append((w["spelling"], w["reading"]))
    n = len(rows)
    print(f"words={n}")
    for m, c in methods.most_common():
        print(f"  {m}: {c} ({100 * c / max(n, 1):.2f}%)")
    print(f"hit_rate={100 * (n - methods['unmapped']) / max(n, 1):.2f}%  ambiguous(>1 seq)={ambiguous}")
    # classify unmapped by shape only (no content)
    shapes: Counter[str] = Counter()
    for sp, rd in unmapped:
        if sp == rd:
            shapes["kana-only"] += 1
        elif all("぀" <= c <= "ヿ" or c in "ー" for c in sp):
            shapes["kana-spelling-diff-reading"] += 1
        elif re.search(r"[A-Za-z0-9]", sp):
            shapes["contains-latin-or-digit"] += 1
        else:
            shapes["has-kanji"] += 1
    print("unmapped shapes:", dict(shapes))
    if args.samples:
        for sp, rd in unmapped[: args.samples]:
            print(f"  unmapped sample: {sp} / {rd}")


def cmd_stats(args: argparse.Namespace) -> None:
    d = json.load(open(args.export, encoding="utf-8"))
    for key, rows in d.items():
        if not isinstance(rows, list) or not rows:
            print(f"{key}: n={len(rows) if isinstance(rows, list) else 'n/a'}")
            continue
        grades: Counter[str] = Counter()
        anki = unsorted = same_second = 0
        first_grade: Counter[str] = Counter()
        revs = 0
        for r in rows:
            rv = r["reviews"]
            revs += len(rv)
            ts = [x["timestamp"] for x in rv]
            if ts != sorted(ts):
                unsorted += 1
            same_second += len(ts) - len(set(ts))
            for x in rv:
                grades[x["grade"]] += 1
                anki += x["from_anki"]
            first_grade[rv[0]["grade"]] += 1
        ident = "vid" if "vid" in rows[0] else "character"
        ids = [r[ident] for r in rows]
        print(f"{key}: n={len(rows)} reviews={revs} unique_{ident}={len(set(ids))} from_anki={anki} "
              f"rows_with_unsorted_reviews={unsorted} duplicate_timestamps={same_second}")
        print(f"  grades={dict(grades)}")
        print(f"  first_review_grade={dict(first_grade)}")


# ---------------------------------------------------------------- fetching

_last_request = 0.0


def _cache_path(kind: str, key: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", key)[:150]
    return CACHE_DIR / kind / f"{safe}.json"


def polite_fetch(url: str, *, data: bytes | None = None, headers: dict[str, str] | None = None,
                 kind: str = "page", cache_key: str | None = None, sleep: Callable[[float], None] = time.sleep,
                 opener: Callable = urllib.request.urlopen) -> str:
    """GET/POST with an on-disk cache (outside the repo) and >= MIN_INTERVAL_S between network calls."""
    global _last_request
    cp = _cache_path(kind, cache_key or url)
    if cp.exists():
        return json.loads(cp.read_text())["body"]
    wait = MIN_INTERVAL_S - (time.monotonic() - _last_request)
    if wait > 0:
        sleep(wait)
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "kotobaya-spike/0", **(headers or {})})
    with opener(req, timeout=30) as resp:
        body = resp.read().decode("utf-8")
    _last_request = time.monotonic()
    cp.parent.mkdir(parents=True, exist_ok=True)
    cp.write_text(json.dumps({"url": url, "body": body}))
    return body


def api_call(endpoint: str, payload: dict, *, fetch: Callable[..., str] = polite_fetch) -> dict:
    """jpdb API v1 call. Key from JPDB_API_KEY. NOTE: cache key excludes the key itself."""
    key = os.environ.get("JPDB_API_KEY")
    if not key:
        raise SystemExit("JPDB_API_KEY not set")
    body = json.dumps(payload, sort_keys=True)
    out = fetch(f"{API_BASE}/{endpoint}", data=body.encode(),
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                kind="api", cache_key=f"{endpoint}-{body}")
    return json.loads(out)


def deck_vocabulary(deck_id: int, *, fetch: Callable[..., str] = polite_fetch) -> list[dict]:
    """list-vocabulary for a deck: [{vid, sid, spelling, reading, occurrences, frequency_rank}]."""
    # list-user-decks / list-vocabulary are the documented v1 endpoints (see jpdb API docs).
    # Request shape is UNVERIFIED (no key available): fields per docs are id, fetch (list of fields).
    res = api_call("deck/list-vocabulary", {"id": deck_id, "fetch_occurences": True}, fetch=fetch)
    # Response: {"vocabulary": [[vid, sid, rid, spelling, reading, frequency_rank, [forms], ...]], "occurences": [...]}
    # Exact column order depends on requested fields; keep raw and normalise defensively.
    return res.get("vocabulary", [])


def parse_vocab_list_html(html: str) -> list[dict]:
    """Parse a public jpdb vocabulary-list page (verified live 2026-11-12 on an anime deck).

    Per entry: vid (in the link; equals the JMdict ent_seq for normal words), spelling,
    reading (from <rt> furigana; kana-only words have no <rt>), global rank ("Top N" tag, optional),
    occurrences (trailing number in the entry's second column).
    """
    out = []
    for chunk in html.split('<div class="entry">')[1:]:
        m = re.search(r'href="/vocabulary/(\d+)/([^"#?]*)', chunk)
        if not m:
            continue
        rt = re.findall(r"<rt>([^<]*)</rt>", chunk)
        link = re.search(r'<a href="/vocabulary/[^>]*>(.*?)</a>', chunk, flags=re.S)
        spelling = urllib.parse.unquote(m.group(2))
        # reading: reconstruct from ruby blocks: base text replaced by <rt> where present
        reading = ""
        if link:
            for base, ann in re.findall(r"<ruby>([^<]*)(?:<rt>([^<]*)</rt>)?</ruby>", link.group(1)):
                reading += ann or base
        rank = re.search(r'class="tag tooltip"[^>]*>Top (\d+)<', chunk)
        occ = re.findall(r'opacity: 0\.5; margin-top: 1rem;">(\d+)</div>', chunk)
        out.append({
            "vid": int(m.group(1)), "spelling": spelling, "reading": reading or spelling,
            "global_rank_top": int(rank.group(1)) if rank else None,
            "occurrences": int(occ[-1]) if occ else None,
            "has_furigana": bool(rt),
        })
    return out


def next_page_offset(html: str) -> str | None:
    m = re.search(r'href="([^"]*\?offset=\d+)#a">Next page', html)
    return m.group(1) if m else None


def cmd_deck_api(args: argparse.Namespace) -> None:
    rows = deck_vocabulary(args.deck_id)
    print(f"rows={len(rows)}")
    Path(args.out).write_text(json.dumps(rows, ensure_ascii=False)) if args.out else None


def cmd_deck_page(args: argparse.Namespace) -> None:
    html = polite_fetch(args.url, kind="page")
    rows = parse_vocab_list_html(html)
    print(f"entries on page={len(rows)} next={'yes' if next_page_offset(html) else 'no'}")
    if args.map:
        idx = load_jmdict(FULL_JMDICT if FULL_JMDICT.exists() else SUBSET_JMDICT)
        c: Counter[str] = Counter()
        vid_is_seq = 0
        for r in rows:
            meth, seqs = map_word(idx, r["spelling"], r["reading"], r["vid"])
            c[meth] += 1
            vid_is_seq += r["vid"] in seqs
        print(dict(c), f"vid_in_mapped_seqs={vid_is_seq}/{len(rows)}")
        for r in rows:
            if map_word(idx, r["spelling"], r["reading"], r["vid"])[0] == "unmapped":
                print("  unmapped sample:", r["spelling"], r["reading"], r["vid"])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(required=True)
    m = sub.add_parser("map")
    m.add_argument("export")
    m.add_argument("--jmdict")
    m.add_argument("--samples", type=int, default=0)
    m.set_defaults(fn=cmd_map)
    s = sub.add_parser("stats")
    s.add_argument("export")
    s.set_defaults(fn=cmd_stats)
    a = sub.add_parser("deck-api")
    a.add_argument("deck_id", type=int)
    a.add_argument("--out")
    a.set_defaults(fn=cmd_deck_api)
    p = sub.add_parser("deck-page")
    p.add_argument("url")
    p.add_argument("--map", action="store_true")
    p.set_defaults(fn=cmd_deck_page)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
