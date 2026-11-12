"""Synthetic device-package rows (no Postgres), shaped like real JMdict rows."""

from __future__ import annotations

import json
import random

from kotobaya_worker.dict.package.sqlite import Row

KANJI = "日本語学校先生食飲見行来水火木金土山川田人大小中高"
KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねの"


def j(x: object) -> str:
    return json.dumps(x, ensure_ascii=False, separators=(",", ":"))


def make_rows(
    release_id: int,
    n: int,
    *,
    seed: int = 1,
    retired: frozenset[int] = frozenset(),
    changed: frozenset[int] = frozenset(),
    skip: frozenset[int] = frozenset(),
    extra: tuple[int, ...] = (),
    scope: str = "top60k",
) -> dict[str, list[Row]]:
    """Entries 1..n (+ ``extra`` seqs); ``skip`` are left out (left the scope)."""
    rnd = random.Random(seed)
    rows: dict[str, list[Row]] = {k: [] for k in (
        "dict_release", "dict_entry", "dict_entry_form", "dict_entry_gloss", "entry_kanji",
        "freq_list", "entry_freq", "entry_pitch", "kanji", "example_sentence",
        "example_sentence_entry",
    )}  # fmt: skip
    rows["dict_release"].append(
        (release_id, f"2026.11.{release_id}", 1_700_000_000_000 + release_id, scope,
         j({"jmdict": {"version": "x", "license": "CC BY-SA 4.0", "url": "u"}}))
    )  # fmt: skip
    rows["freq_list"] += [("jpdb", "jpdb frequency", 0), ("bccwj", "BCCWJ", 1)]
    for lit in KANJI:
        rows["kanji"].append(
            (lit, j(["ニチ"]), j(["ひ"]), j([]), j(["sun", "day"]), 4, 1, 5, 1, lit, j([lit]))
        )
    for seq in [*range(1, n + 1), *extra]:
        r = random.Random(seed * 1_000_003 + seq)  # per-entry stream: stable across releases
        if seq in skip:
            continue
        k1 = "".join(r.choice(KANJI) for _ in range(r.randint(1, 3)))
        kana = "".join(r.choice(KANA) for _ in range(r.randint(2, 5)))
        gl = [f"meaning {seq} {i} " + "word " * r.randint(1, 6) for i in range(r.randint(1, 4))]
        if seq in changed:
            gl.append(f"revised in {release_id}")
        senses = [
            {"pos": ["n"], "misc": [], "field": [], "dialect": [], "info": [],
             "applies_to_kanji": ["*"], "applies_to_kana": ["*"],
             "gloss": [{"lang": "eng", "text": g} for g in gl], "xref": []}
        ]  # fmt: skip
        rows["dict_entry"].append(
            (seq, "ja", k1, kana, 1, j([{"text": k1, "common": True, "tags": []}]),
             j([{"text": kana, "common": True, "tags": [], "applies_to": ["*"]}]),
             j(senses), j(["n"]), j([]), seq if seq <= n else None, int(seq in retired))
        )  # fmt: skip
        rows["dict_entry_form"] += [
            (seq, "kanji", 0, k1, k1, 1),
            (seq, "reading", 0, kana, kana, 1),
        ]
        for i, g in enumerate(gl):
            rows["dict_entry_gloss"].append((seq, 0, i, "eng", g, g.lower()))
        for lit in sorted(set(k1)):
            rows["entry_kanji"].append((seq, lit))
        rows["entry_freq"].append((seq, "jpdb", seq))
        if seq % 3 == 0:
            rows["entry_pitch"].append((seq, kana, j([0, 2])))
        if seq % 5 == 0:
            sid = 10_000 + seq
            rows["example_sentence"].append((sid, "ja", f"{k1}です。", f"It is {seq}.", sid + 1))
            rows["example_sentence_entry"].append((sid, seq))
    _ = rnd
    return rows
