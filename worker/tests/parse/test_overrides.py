"""Parse overrides (T403b; PARSER.md 4): golden vectors, survival across re-parses, scope."""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any

import jsonschema  # type: ignore[import-untyped]
import pytest

from kotobaya_worker.parse.match import Token
from kotobaya_worker.parse.overrides import OverrideRow, apply_overrides
from kotobaya_worker.parse.pipeline import parse_sentences
from kotobaya_worker.parse.sudachi import Morph, installed_dictionaries

from .match_support import (
    TOKEN_SCHEMA,
    assert_array_invariants,
    fixture_lookup,
    load_json,
    parser_cases,
    token_from_json,
)

needs_sudachi = pytest.mark.skipif(
    not installed_dictionaries(), reason="no Sudachi dictionary installed"
)

MEDIA = "0193a1b2-0c12-7000-8000-0000000000e1"
OTHER_MEDIA = "0193a1b2-0c12-7000-8000-0000000000e9"
SENT = "0193a1b2-0c12-7000-8000-0000000000f1"
HLC = "0193a1b2c3d4-0000-0193a1b2-0c12-7000-8000-0000000000d1"


def _hlc(n: int) -> str:
    return f"0193a1b2c3d{n:x}-0000-0193a1b2-0c12-7000-8000-0000000000d1"


def _row(i: int, match: str, tokens: list[dict[str, Any]], *, media: str | None = None,
         sentence: str | None = None, hlc: str = HLC, deleted: bool = False) -> OverrideRow:  # fmt: skip
    return OverrideRow(
        id=f"0193a1b2-0c12-7000-8000-{i:012d}",
        media_id=media,
        sentence_id=sentence,
        match_text=match,
        tokens=tuple(tokens),
        hlc=hlc,
        deleted=deleted,
    )


def _morph(d: dict[str, Any]) -> Morph:
    p = d["pos"]
    return Morph(0, len(d["surface"]), d["surface"], d["surface"], d["surface"],
                 d["reading_form"], (p[0], p[1], p[2], p[3], p[4], p[5]))  # fmt: skip


# --- golden vectors -------------------------------------------------------------------------


@pytest.mark.parametrize(
    "case", parser_cases()["override_cases"], ids=lambda c: str(c["name"])[:60]
)
def test_override_golden_vectors(case: dict[str, Any]) -> None:
    analysis: dict[str, list[dict[str, Any]]] = case["analysis"]

    def analyse(surface: str) -> Sequence[Morph]:
        return [_morph(m) for m in analysis[surface]]

    rows = [
        OverrideRow(
            o["id"],
            o["media_id"],
            o["sentence_id"],
            o["match_text"],
            tuple(o["tokens"]),
            o["hlc"],
            o["deleted"],
        )
        for o in case["overrides"]
    ]
    entry_pos = {int(k): frozenset(v) for k, v in case["entry_pos"].items()}
    out = apply_overrides(
        case["text"],
        [token_from_json(t) for t in case["base"]],
        rows,
        media_id=case["media_id"],
        sentence_id=case["sentence_id"],
        entry_pos=entry_pos.get,
        analyse=analyse,
    )
    assert [t.to_json() for t in out] == case["expected"]


# --- overrides survive re-parse (acceptance) ------------------------------------------------


def _parse(text: str, rows: Sequence[OverrideRow], *, media: str | None = MEDIA,
           sentence: str | None = SENT) -> list[Token]:  # fmt: skip
    return parse_sentences(
        [text], media, [sentence] if sentence else None, lookup=fixture_lookup(), overrides=rows
    )[0]


def _spans(tokens: list[Token]) -> list[tuple[str, int | None, bool]]:
    return [(t.surface, t.seq, t.override) for t in tokens]


@needs_sudachi
def test_overrides_survive_reparse() -> None:
    text = "食べかけのパンだ。"
    stored = [t.to_json() for t in _parse(text, [])]  # parse, store tokens
    assert not any(t["override"] for t in stored)
    override = _row(1, "パン", [{"surface": "パン", "lemma": "パン", "seq": None}], media=MEDIA)
    reparsed = _parse(text, [override])  # add a media-scoped override, re-parse
    pan = [t for t in reparsed if t.surface == "パン"]
    assert len(pan) == 1 and pan[0].override and pan[0].seq is None
    # untouched tokens are byte-identical to the stored ones
    assert [t.to_json() for t in reparsed if not t.override] == [
        t for t in stored if t["surface"] != "パン"
    ]
    # and the override keeps applying on every later re-parse
    assert _parse(text, [override]) == reparsed


@needs_sudachi
def test_sentence_beats_media_beats_global() -> None:
    text = "パンを食べる。"
    g = _row(1, "パン", [{"surface": "パン", "seq": 9100001}])
    m = _row(2, "パン", [{"surface": "パン", "seq": 9100002}], media=MEDIA)
    s = _row(3, "パン", [{"surface": "パン", "seq": 9100003}], media=MEDIA, sentence=SENT)

    def pan(rows: list[OverrideRow], **kw: Any) -> int | None:
        return next(t.seq for t in _parse(text, rows, **kw) if t.surface == "パン")

    assert pan([g, m, s]) == 9100003
    assert pan([g, m]) == 9100002
    assert pan([g]) == 9100001
    # a sentence-scoped row of another sentence and a row of another media do not apply
    other = "0193a1b2-0c12-7000-8000-0000000000f9"
    assert pan([g, m, s], sentence=other) == 9100002
    assert pan([g, m, s], media=OTHER_MEDIA, sentence=other) == 9100001
    # POST /parse (no media): global only
    assert pan([g, m, s], media=None, sentence=None) == 9100001


@needs_sudachi
def test_sentence_scope_beats_longer_global() -> None:
    text = "パンを食べる。"
    g = _row(1, "パンを", [{"surface": "パン", "seq": 9100001}, {"surface": "を", "seq": None}])
    s = _row(2, "パン", [{"surface": "パン", "seq": 9100003}], media=MEDIA, sentence=SENT)
    toks = _parse(text, [g, s])
    assert [(t.surface, t.seq) for t in toks if t.override] == [("パン", 9100003)]


@needs_sudachi
def test_longest_match_wins_within_scope() -> None:
    text = "パンを食べる。"
    short = _row(1, "パン", [{"surface": "パン", "seq": 9100001}], hlc=_hlc(9))
    long = _row(2, "パンを", [{"surface": "パンを", "seq": 9100002}], hlc=_hlc(1))
    toks = _parse(text, [short, long])
    assert [(t.surface, t.seq) for t in toks if t.override] == [("パンを", 9100002)]


@needs_sudachi
def test_newest_hlc_wins_for_equal_length() -> None:
    text = "パンを食べる。"
    old = _row(1, "パン", [{"surface": "パン", "seq": 9100001}], hlc=_hlc(1))
    new = _row(2, "パン", [{"surface": "パン", "seq": 9100002}], hlc=_hlc(2))
    assert [t.seq for t in _parse(text, [new, old]) if t.override] == [9100002]
    assert [t.seq for t in _parse(text, [old, new]) if t.override] == [9100002]


@needs_sudachi
def test_deleted_overrides_are_ignored() -> None:
    text = "パンを食べる。"
    live = _row(1, "パン", [{"surface": "パン", "seq": 9100001}])
    dead = _row(2, "パン", [{"surface": "パン", "seq": 9100002}], media=MEDIA, deleted=True)
    assert [t.seq for t in _parse(text, [live, dead]) if t.override] == [9100001]
    assert not any(t.override for t in _parse(text, [dead]))


@needs_sudachi
def test_misaligned_override_does_not_apply() -> None:
    text = "パンを食べる。"
    toks = _parse(text, [_row(1, "ンを", [{"surface": "ンを", "seq": None}])])
    assert not any(t.override for t in toks)


@needs_sudachi
def test_override_on_repeated_text_applies_to_every_occurrence() -> None:
    text = "パンとパン。"
    toks = _parse(text, [_row(1, "パン", [{"surface": "パン", "seq": 9100001}])])
    assert [(t.s, t.seq) for t in toks if t.override] == [(0, 9100001), (3, 9100001)]


@needs_sudachi
def test_skipped_row_is_logged_once_by_id(caplog: pytest.LogCaptureFixture) -> None:
    bad = _row(7, "パン", [{"surface": "パ"}, {"surface": "ソ"}])
    skipped: set[str] = set()
    with caplog.at_level(logging.WARNING):
        out = parse_sentences(
            ["パンを食べる。", "パンだ。"], None, None,
            lookup=fixture_lookup(), overrides=[bad], skipped=skipped,
        )  # fmt: skip
    assert not any(t.override for toks in out for t in toks)
    assert skipped == {bad.id}
    msgs = [r.getMessage() for r in caplog.records if bad.id in r.getMessage()]
    assert len(msgs) == 1 and "パン" not in msgs[0]


@needs_sudachi
def test_override_builds_members_from_sudachi_and_schema_valid() -> None:
    text = "食べかけのパンだ。"
    g = _row(1, "食べかけ", [{"surface": "食べ", "lemma": "食べる", "seq": 1358280},
                             {"surface": "かけ", "lemma": "かける", "seq": 1207610}])  # fmt: skip
    toks = _parse(text, [g])
    head = [t.to_json() for t in toks[:2]]
    # 4.4 b: each surface analysed alone (かけ alone is whatever Sudachi says, e.g. a noun)
    assert [(t["surface"], t["lemma"], t["seq"], t["override"]) for t in head] == [
        ("食べ", "食べる", 1358280, True),
        ("かけ", "かける", 1207610, True),
    ]
    assert (head[0]["reading"], head[0]["pos"], head[0]["cform"]) == (
        "たべ", ["動詞", "一般"], "連用形-一般",
    )  # fmt: skip
    assert head[1]["reading"] == "かけ" and head[1]["pos"]
    validator = jsonschema.Draft202012Validator(load_json(TOKEN_SCHEMA))
    for t in toks:
        validator.validate(t.to_json())
    assert_array_invariants(text, [t.to_json() for t in toks])


# --- unknown characters (C-12 note: Sudachi may leave 𠮷 untokenised) ----------------------


@needs_sudachi
def test_surrogate_pair_text_satisfies_invariants_with_and_without_override() -> None:
    text = "𠮷野家で食べた。"
    validator = jsonschema.Draft202012Validator(load_json(TOKEN_SCHEMA))
    base = [t.to_json() for t in _parse(text, [])]
    assert_array_invariants(text, base)
    for t in base:
        validator.validate(t)
    # 𠮷 may be a gap (Sudachi tags an OOV 𠮷 as 補助記号): an override starting in that gap
    # is aligned as long as it does not cut a base token, and covers the gap on purpose.
    ovr = _row(1, "𠮷野家", [{"surface": "𠮷野家", "lemma": "吉野家", "reading": "ヨシノヤ",
                               "seq": None, "class": "name"}], media=MEDIA, sentence=SENT)  # fmt: skip
    toks = [t.to_json() for t in _parse(text, [ovr])]
    assert_array_invariants(text, toks)
    first = toks[0]
    covers_cleanly = not any(b["s"] < k < b["e"] for b in base for k in (0, 4))
    if covers_cleanly:
        assert (first["s"], first["e"], first["lemma"], first["reading"], first["class"]) == (
            0, 4, "吉野家", "よしのや", "name",
        )  # fmt: skip
    else:  # Sudachi split 野家 differently: the override is misaligned and must not apply
        assert not any(t["override"] for t in toks)


def test_whitespace_only_override_token_stays_a_gap() -> None:
    base = [
        Token(0, 2, "ねこ", "ねこ", "ねこ", ("名詞",), None, None, "content", False),
        Token(3, 5, "いぬ", "いぬ", "いぬ", ("名詞",), None, None, "content", False),
    ]
    row = _row(1, "ねこ　いぬ", [{"surface": "ねこ"}, {"surface": "　"}, {"surface": "いぬ"}])
    out = apply_overrides("ねこ　いぬ", base, [row], media_id=None, sentence_id=None,
                          entry_pos=lambda s: None, analyse=lambda s: [])  # fmt: skip
    assert [(t.s, t.e, t.override) for t in out] == [(0, 2, True), (3, 5, True)]
