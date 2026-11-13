#!/usr/bin/env python3
"""Checks the parser contract (C-12, contracts/api/PARSER.md).

    check_parser.py [<bundled openapi.json>]

1. examples/tokens.json: every token validates against token.schema.json, the array follows
   PARSER.md 1.2, and it agrees with its fixture sentence (fixtures/parse/sentences.json).
2. examples/parser-cases.json: a reference implementation of PARSER.md 2 (class table), 2.1
   (pos_class tie-break) and 4 (override application) reproduces every expected value.
3. contracts/db/media/examples/*.content.v1.jsonl (C-11, read-only): every sentence's tokens
   follow token format 1, and every occurrence's pos_class follows the 2.1 tie-break.
4. With the bundled openapi.json: SentenceToken has exactly the token schema's members, required
   list and class values; OverrideToken offers the optional reading/class.

Needs python3 with jsonschema (>= 4.18). Exits non-zero on the first failure.
"""

from __future__ import annotations

import json
import pathlib
import sys
from collections import Counter
from typing import Any

import jsonschema

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CLASSES = ("content", "name", "particle", "auxiliary", "other")  # tie-break order (2.1)
NON_WORD_POS = frozenset({"補助記号", "空白"})
CONTENT_POS = frozenset({"名詞", "代名詞", "動詞", "形容詞", "形状詞", "副詞", "連体詞",
                         "接続詞", "感動詞", "接頭辞", "接尾辞"})

Token = dict[str, Any]


class Fail(Exception):
    pass


# ---------------------------------------------------------------- helpers

def u16(text: str) -> bytes:
    return text.encode("utf-16-le")


def len16(text: str) -> int:
    return len(u16(text)) // 2


def slice16(text: str, s: int, e: int) -> str:
    return u16(text)[2 * s:2 * e].decode("utf-16-le")


def is_low_surrogate_at(text: str, i: int) -> bool:
    b = u16(text)
    if not 0 < i < len(b) // 2:
        return False
    unit = int.from_bytes(b[2 * i:2 * i + 2], "little")
    return 0xDC00 <= unit <= 0xDFFF


def hiragana(s: str) -> str:
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)


def trim_pos(pos: list[str]) -> list[str]:
    return [p for p in pos[:4] if p != "*"]


def check_array(where: str, text: str, tokens: list[Token], validator: Any) -> None:
    """PARSER.md 1.1 (schema) and 1.2 (array invariants)."""
    n = len16(text)
    prev_e = 0
    for k, t in enumerate(tokens):
        errs = sorted(validator.iter_errors(t), key=str)
        if errs:
            raise Fail(f"{where}: token {k}: {errs[0].message}")
        s, e = t["s"], t["e"]
        if not (0 <= s < e <= n):
            raise Fail(f"{where}: token {k}: offsets [{s},{e}) outside text of UTF-16 length {n}")
        if s < prev_e:
            raise Fail(f"{where}: token {k}: overlaps or is out of order (s={s} < previous e={prev_e})")
        if is_low_surrogate_at(text, s) or is_low_surrogate_at(text, e):
            raise Fail(f"{where}: token {k}: offset splits a surrogate pair")
        if slice16(text, s, e) != t["surface"]:
            raise Fail(f"{where}: token {k}: surface {t['surface']!r} != text[{s}:{e}] {slice16(text, s, e)!r}")
        if not t["override"] and (not t["pos"] or t["reading"] == ""):
            raise Fail(f"{where}: token {k}: empty pos/reading allowed only for override tokens")
        if t["surface"].isspace():
            raise Fail(f"{where}: token {k}: whitespace-only token")
        prev_e = e


# ---------------------------------------------------------------- reference implementation

def derive_class(pos: list[str], seq: int | None, entry_pos: list[str] | None) -> str:
    """PARSER.md 2. `pos` may be the full six fields or the trimmed list (equivalent)."""
    p0 = pos[0] if len(pos) > 0 else None
    p1 = pos[1] if len(pos) > 1 else None
    e = set(entry_pos) if (seq is not None and entry_pos) else set()
    if p0 == "助詞":
        return "particle"
    if p0 == "助動詞":
        return "auxiliary"
    if p0 == "名詞" and p1 == "固有名詞":
        return "name"
    if e and e <= {"n-pr"}:
        return "name"
    if e and e <= {"prt"}:
        return "particle"
    if e and e <= {"aux", "aux-v", "aux-adj"}:
        return "auxiliary"
    if p0 == "名詞" and p1 == "数詞":
        return "other"
    if p0 in CONTENT_POS:
        return "content"
    if not pos and seq is not None:
        return "content"
    return "other"


def pos_class(classes: list[str]) -> str:
    """PARSER.md 2.1."""
    counts = Counter(classes)
    best = max(counts.values())
    return next(c for c in CLASSES if counts.get(c, 0) == best)


def apply_overrides(case: dict[str, Any]) -> list[Token]:
    """PARSER.md 4 over one sentence."""
    text: str = case["text"]
    base: list[Token] = case["base"]
    media_id, sentence_id = case["media_id"], case["sentence_id"]
    entry_pos: dict[str, list[str]] = case["entry_pos"]
    analysis: dict[str, list[dict[str, Any]]] = case["analysis"]

    def rank(o: dict[str, Any]) -> int | None:  # 4.1
        if o["deleted"]:
            return None
        if o["media_id"] is None:
            return 2
        if media_id is None or o["media_id"] != media_id:
            return None
        if o["sentence_id"] is None:
            return 1
        return 0 if o["sentence_id"] == sentence_id else None

    def inside_token(i: int) -> bool:
        return any(t["s"] < i < t["e"] for t in base)

    cands = []  # 4.2
    for o in case["overrides"]:
        r = rank(o)
        if r is None:
            continue
        if "".join(t["surface"] for t in o["tokens"]) != o["match_text"]:
            continue
        m = u16(o["match_text"])
        hay = u16(text)
        L = len(m) // 2
        for i in range(0, len16(text) - L + 1):
            if hay[2 * i:2 * (i + L)] == m and not inside_token(i) and not inside_token(i + L):
                cands.append((r, -L, o["hlc"], o["id"], i, o))

    # 4.3: rank asc, length desc, hlc desc, id asc, i asc
    cands.sort(key=lambda c: (c[0], c[1], tuple(-ord(ch) for ch in c[2]), c[3], c[4]))
    accepted: list[tuple[int, int, dict[str, Any]]] = []
    for _r, negL, _h, _id, i, o in cands:
        j = i - negL
        if all(j <= a or i >= b for a, b, _ in accepted):
            accepted.append((i, j, o))

    def same_span(s: int, e: int) -> Token | None:
        return next((t for t in base if t["s"] == s and t["e"] == e), None)

    out = [t for t in base if not any(a <= t["s"] and t["e"] <= b for a, b, _ in accepted)]
    for a, _b, o in accepted:  # 4.4
        pos_ = a
        for ot in o["tokens"]:
            surf = ot["surface"]
            s, e = pos_, pos_ + len16(surf)
            pos_ = e
            if surf.isspace():  # Unicode White_Space only: stays a gap
                continue
            seq = ot.get("seq")
            st = same_span(s, e)
            full_pos: list[str]
            if st is not None:
                reading, full_pos, cform = st["reading"], list(st["pos"]), st["cform"]
            else:
                morphs = analysis.get(surf)
                if morphs is None:
                    raise Fail(f"case {case['name']!r}: vector lacks analysis for {surf!r}")
                if len(morphs) == 1 and morphs[0]["pos"][0] not in NON_WORD_POS:
                    m0 = morphs[0]
                    reading, full_pos = hiragana(m0["reading_form"]), list(m0["pos"])
                    cform = m0["pos"][5] if m0["pos"][5] != "*" else None
                else:
                    reading = "".join(hiragana(m["reading_form"]) for m in morphs)
                    full_pos, cform = [], None
            if "reading" in ot:
                reading = ot["reading"]
            lemma = ot.get("lemma") or (st["lemma"] if st is not None else surf)
            cls = ot.get("class") or derive_class(full_pos, seq, entry_pos.get(str(seq)) if seq is not None else None)
            out.append({"s": s, "e": e, "surface": surf, "lemma": lemma, "reading": reading,
                        "pos": trim_pos(full_pos), "cform": cform, "seq": seq, "class": cls,
                        "override": True})
    out.sort(key=lambda t: t["s"])
    return out


# ---------------------------------------------------------------- checks

FIXTURE_CLASS = {"particle": "particle", "auxiliary": "auxiliary", "name": "name",
                 "number": "other", "numeral": "other", "symbol": "other"}


def check_token_example(validator: Any) -> None:
    ex = json.loads((HERE / "examples/tokens.json").read_text(encoding="utf-8"))
    check_array("examples/tokens.json", ex["text"], ex["tokens"], validator)
    fx = json.loads((ROOT / ex["source"]["file"]).read_text(encoding="utf-8"))
    sent = next(s for s in fx["sentences"] if s["id"] == ex["source"]["id"])
    if sent["text"] != ex["text"]:
        raise Fail("examples/tokens.json: text differs from the fixture sentence")
    words, off = [], 0
    for ft in sent["tokens"]:
        n = len16(ft["surface"])
        if ft["pos"] != "punct":
            words.append((off, off + n, ft))
        off += n
    if len(words) != len(ex["tokens"]):
        raise Fail(f"examples/tokens.json: {len(ex['tokens'])} tokens, fixture has {len(words)} word tokens")
    for (s, e, ft), t in zip(words, ex["tokens"]):
        got = (t["s"], t["e"], t["surface"], t["lemma"], t["reading"], t["seq"])
        want = (s, e, ft["surface"], ft["lemma"], ft["reading"], ft["seq"])
        if got != want:
            raise Fail(f"examples/tokens.json: token {got} != fixture {want}")
        if t["class"] != FIXTURE_CLASS.get(ft["pos"], "content"):
            raise Fail(f"examples/tokens.json: {t['surface']}: class {t['class']} disagrees with fixture pos {ft['pos']}")
        if derive_class(t["pos"], t["seq"], None) != t["class"]:
            raise Fail(f"examples/tokens.json: {t['surface']}: class {t['class']} is not what PARSER.md 2 gives")
    print(f"  tokens.json: {len(ex['tokens'])} tokens OK (fixture {ex['source']['id']})")


def check_cases(validator: Any) -> None:
    cases = json.loads((HERE / "examples/parser-cases.json").read_text(encoding="utf-8"))
    for c in cases["class_cases"]:
        got = derive_class(c["pos"], c["seq"], c["entry_pos"])
        if got != c["expected"]:
            raise Fail(f"class case {c['name']!r}: got {got}, expected {c['expected']}")
    for c in cases["pos_class_cases"]:
        got = pos_class(c["classes"])
        if got != c["expected"]:
            raise Fail(f"pos_class case {c['name']!r}: got {got}, expected {c['expected']}")
    for c in cases["override_cases"]:
        check_array(f"override case {c['name']!r} base", c["text"], c["base"], validator)
        check_array(f"override case {c['name']!r} expected", c["text"], c["expected"], validator)
        got = apply_overrides(c)
        if got != c["expected"]:
            for k, (g, w) in enumerate(zip(got, c["expected"])):
                if g != w:
                    raise Fail(f"override case {c['name']!r}: token {k}: got {g}, expected {w}")
            raise Fail(f"override case {c['name']!r}: got {len(got)} tokens, expected {len(c['expected'])}")
    print(f"  parser-cases.json: {len(cases['class_cases'])} class, {len(cases['pos_class_cases'])} pos_class,"
          f" {len(cases['override_cases'])} override cases OK")


def check_snapshot_examples(validator: Any) -> None:
    n_files = n_sent = 0
    for path in sorted((ROOT / "contracts/db/media/examples").glob("*.content.v1.jsonl")):
        recs = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        sentences = [r for r in recs if r["record"] == "sentence"]
        by_seg: dict[tuple[str, int], list[str]] = {}
        for r in sentences:
            check_array(f"{path.name} sentence {r['id']}", r["text"], r["tokens"], validator)
            if r["origin"] == "media":
                for t in r["tokens"]:
                    if t["seq"] is not None:
                        by_seg.setdefault((r["segment_id"], t["seq"]), []).append(t["class"])
        for r in recs:
            if r["record"] == "occurrence" and (r["segment_id"], r["entry_seq"]) in by_seg:
                want = pos_class(by_seg[(r["segment_id"], r["entry_seq"])])
                if r["pos_class"] != want:
                    raise Fail(f"{path.name}: occurrence {r['entry_seq']}: pos_class {r['pos_class']}, PARSER.md 2.1 gives {want}")
        n_files += 1
        n_sent += len(sentences)
    print(f"  snapshot examples: {n_sent} sentences in {n_files} archives OK")


def check_openapi(bundle: pathlib.Path, schema: dict[str, Any]) -> None:
    spec = json.loads(bundle.read_text(encoding="utf-8"))
    st = spec["components"]["schemas"]["SentenceToken"]
    if set(st["properties"]) != set(schema["properties"]):
        raise Fail(f"openapi SentenceToken properties {sorted(st['properties'])} != token.schema.json {sorted(schema['properties'])}")
    if sorted(st["required"]) != sorted(schema["required"]):
        raise Fail("openapi SentenceToken required list differs from token.schema.json")
    if st["properties"]["class"]["enum"] != schema["properties"]["class"]["enum"]:
        raise Fail("openapi SentenceToken class values differ from token.schema.json")
    ot = spec["components"]["schemas"]["OverrideToken"]["properties"]
    if not {"surface", "lemma", "seq", "reading", "class"} <= set(ot):
        raise Fail("openapi OverrideToken lacks a PARSER.md 4.4 member")
    if ot["class"]["enum"] != schema["properties"]["class"]["enum"]:
        raise Fail("openapi OverrideToken class values differ from token.schema.json")
    print("  openapi SentenceToken/OverrideToken aligned with token.schema.json")


def main(argv: list[str]) -> int:
    schema = json.loads((HERE / "token.schema.json").read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    try:
        check_token_example(validator)
        check_cases(validator)
        check_snapshot_examples(validator)
        if len(argv) > 1:
            check_openapi(pathlib.Path(argv[1]), schema)
        # negative self-tests: the array checker must catch these
        ex = json.loads((HERE / "examples/tokens.json").read_text(encoding="utf-8"))
        bad_cases = [
            ("overlap", lambda ts: ts[1].__setitem__("s", 1)),
            ("surface mismatch", lambda ts: ts[0].__setitem__("surface", "今日")),
            ("katakana reading", lambda ts: ts[0].__setitem__("reading", "キノウ")),
            ("star in pos", lambda ts: ts[0]["pos"].append("*")),
            ("missing member", lambda ts: ts[0].pop("cform")),
            ("past end", lambda ts: ts[7].__setitem__("e", 15)),
        ]
        for name, mutate in bad_cases:
            ts = json.loads(json.dumps(ex["tokens"]))
            mutate(ts)
            try:
                check_array("negative", ex["text"], ts, validator)
            except Fail:
                continue
            raise Fail(f"negative self-test {name!r} was not detected")
        if not is_low_surrogate_at("𠮷野", 1) or is_low_surrogate_at("𠮷野", 2):
            raise Fail("negative self-test 'split surrogate' was not detected")
    except Fail as e:
        print(f"check_parser: FAIL: {e}", file=sys.stderr)
        return 1
    print("check_parser: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
