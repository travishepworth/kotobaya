"""T404b: tokens -> occurrences, segment counts, deck items (SNAPSHOT.md 3.3.6-7, 4.2; PARSER.md 2.1)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.coverage.aggregate import (
    MAX_SENTENCE_IDS,
    SentenceTokens,
    aggregate_segment,
    deck_items,
    job_result,
    pos_class,
)
from kotobaya_worker.media.publish.snapshot import DeckItemRec, OccurrenceRec

ROOT = Path(__file__).resolve().parents[3]
PARSER_CASES = ROOT / "contracts" / "api" / "examples" / "parser-cases.json"


def tok(s: int, seq: int | None, cls: str = "content") -> dict[str, Any]:
    return {"s": s, "e": s + 1, "surface": "x", "seq": seq, "class": cls}


def test_pos_class_golden_vectors_from_parser_cases() -> None:
    cases = json.loads(PARSER_CASES.read_text(encoding="utf-8"))["pos_class_cases"]
    assert cases
    for c in cases:
        assert pos_class(c["classes"]) == c["expected"], c["name"]


def test_pos_class_rejects_empty_and_unknown() -> None:
    with pytest.raises(ValueError):
        pos_class([])
    with pytest.raises(ValueError):
        pos_class(["noun"])


def test_aggregate_counts_unmapped_and_token_count_identity() -> None:
    sents = [
        SentenceTokens("a", 0, [tok(0, 10), tok(1, 20, "particle"), tok(2, None)]),
        SentenceTokens("b", 1, []),
        SentenceTokens("c", 2, [tok(0, 10), tok(1, 10, "other"), tok(2, None, "name")]),
    ]
    seg = aggregate_segment("S", sents)
    assert seg.sentence_count == 3
    assert seg.token_count == 6 and seg.unmapped_token_count == 2
    assert seg.occurrences == (
        OccurrenceRec("S", 10, 3, "content", ("a", "c")),
        OccurrenceRec("S", 20, 1, "particle", ("a",)),
    )
    assert seg.token_count == sum(o.count for o in seg.occurrences) + seg.unmapped_token_count


def test_aggregate_sentence_ids_capped_at_20_in_ordinal_order_whatever_the_input_order() -> None:
    sents = [SentenceTokens(f"s{i:02}", i, [tok(0, 7), tok(1, 7)]) for i in range(25)]
    seg = aggregate_segment("S", list(reversed(sents)))  # out of order on purpose
    (occ,) = seg.occurrences
    assert occ.count == 50
    assert len(occ.sentence_ids) == MAX_SENTENCE_IDS == 20
    assert occ.sentence_ids == tuple(f"s{i:02}" for i in range(20))  # each sentence once


def test_generated_sentences_never_count() -> None:
    sents = [
        SentenceTokens("m", 0, [tok(0, 5)]),
        SentenceTokens("g", 1, [tok(0, 5), tok(1, 6), tok(2, None)], origin="generated"),
    ]
    seg = aggregate_segment("S", sents)
    assert seg.sentence_count == 2  # the row exists
    assert (seg.token_count, seg.unmapped_token_count) == (1, 0)
    assert seg.occurrences == (OccurrenceRec("S", 5, 1, "content", ("m",)),)


def test_empty_segment() -> None:
    seg = aggregate_segment("S", [])
    assert (seg.sentence_count, seg.token_count, seg.unmapped_token_count) == (0, 0, 0)
    assert seg.occurrences == ()


def test_duplicate_sentence_ordinal_is_rejected() -> None:
    with pytest.raises(ValueError):
        aggregate_segment("S", [SentenceTokens("a", 0, []), SentenceTokens("b", 0, [])])


def test_deck_items_sum_over_segments_rank_by_frequency_tie_by_seq() -> None:
    s0 = aggregate_segment("A", [SentenceTokens("a", 0, [tok(0, 30), tok(1, 10), tok(2, 20)])])
    s1 = aggregate_segment("B", [SentenceTokens("b", 0, [tok(0, 20), tok(1, 40), tok(2, 30)])])
    assert deck_items([s0, s1]) == [
        DeckItemRec(20, 1, 2),
        DeckItemRec(30, 2, 2),
        DeckItemRec(10, 3, 1),
        DeckItemRec(40, 4, 1),
    ]


def test_job_result_numbers() -> None:
    s0 = aggregate_segment("A", [SentenceTokens("a", 0, [tok(0, 1), tok(1, None)])])
    s1 = aggregate_segment(
        "B", [SentenceTokens("b", 0, [tok(0, 1)]), SentenceTokens("c", 1, [tok(0, 2)])]
    )
    r = job_result("M", 3, [s0, s1])
    assert (r.media_id, r.content_version, r.segments, r.sentences) == ("M", 3, 2, 3)
    assert (r.tokens, r.unmapped_tokens, r.unique_entries, r.unmapped) == (4, 1, 2, [])
