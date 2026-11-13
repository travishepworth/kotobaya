"""JMdict matching and class derivation (T403b; PARSER.md 2)."""

from __future__ import annotations

from typing import Any

import pytest

from kotobaya_worker.parse import RawToken, segment
from kotobaya_worker.parse.match import (
    InMemoryMatchLookup,
    derive_class,
    lemma_reading,
    match_seq,
    to_token,
)
from kotobaya_worker.parse.sudachi import installed_dictionaries

from .conftest import FixtureSentence
from .match_support import fixture_lookup, parser_cases

needs_sudachi = pytest.mark.skipif(
    not installed_dictionaries(), reason="no Sudachi dictionary installed"
)


def _raw(surface: str, lemma: str, reading: str, pos: tuple[str, ...], cform: str | None = None
         ) -> RawToken:  # fmt: skip
    return RawToken(0, len(surface), surface, lemma, reading, pos, cform)


# --- class table (golden vectors) -----------------------------------------------------------


@pytest.mark.parametrize("case", parser_cases()["class_cases"], ids=lambda c: str(c["name"]))
def test_class_golden_vectors(case: dict[str, Any]) -> None:
    entry_pos = frozenset(case["entry_pos"]) if case["entry_pos"] is not None else None
    assert derive_class(case["pos"], case["seq"], entry_pos) == case["expected"]


# --- ranking ------------------------------------------------------------------------------

NOUN = ("名詞", "普通名詞", "一般")
VERB = ("動詞", "一般")


@pytest.mark.parametrize(
    ("tok", "seq"),
    [
        # 外: そと (1203250) vs ほか (1203260); 朝: あさ (1428280) vs ちょう (1428285).
        (_raw("外", "外", "そと", NOUN), 1203250),
        (_raw("外", "外", "ほか", NOUN), 1203260),
        (_raw("朝", "朝", "あさ", NOUN), 1428280),
        (_raw("朝", "朝", "ちょう", NOUN), 1428285),
        # 降る: ふる (1282790) beats the lower-seq くだる (1184450) by reading, through the
        # derived lemma reading of an inflected surface (降っ/ふっ -> ふる).
        (_raw("降っ", "降る", "ふっ", VERB, "連用形-促音便"), 1282790),
        (_raw("降り", "降る", "くだり", VERB, "連用形-一般"), 1184450),
    ],
    ids=["soto", "hoka", "asa", "chou", "furu", "kudaru"],
)
def test_ranking_prefers_reading_matching_homograph(tok: RawToken, seq: int) -> None:
    assert match_seq(tok, fixture_lookup()) == seq


@needs_sudachi
@pytest.mark.parametrize(
    ("sid", "surface", "seq"),
    [("s08", "降っ", 1282790), ("s10", "外", 1203250), ("s11", "朝", 1428280),
     ("s07", "母", 1514990), ("s17", "遊ん", 1542160)],
)  # fmt: skip
def test_ranking_on_fixture_homograph_sentences(
    sentences: tuple[FixtureSentence, ...], sid: str, surface: str, seq: int
) -> None:
    s = next(x for x in sentences if x.id == sid)
    lk = fixture_lookup()
    toks = [to_token(r, lk) for r in segment(s.text, lk.is_key)]
    assert [t.seq for t in toks if t.surface == surface] == [seq]


def test_ranking_pos_breaks_kana_ties() -> None:
    # は: particle 2028920 among many nouns read は.
    assert match_seq(_raw("は", "は", "は", ("助詞", "係助詞")), fixture_lookup()) == 2028920
    assert match_seq(_raw("ます", "ます", "ます", ("助動詞",)), fixture_lookup()) == 2210290


def test_ranking_frequency_then_lowest_seq() -> None:
    lk = InMemoryMatchLookup()
    for seq in (30, 10, 20):
        lk.add(seq, pos=["n"], kanji=[("字", False)], readings=[("じ", False)])
    tok = _raw("字", "字", "じ", NOUN)
    assert match_seq(tok, lk) == 10  # all equal: lowest seq
    lk2 = InMemoryMatchLookup()
    for seq, rank in ((30, 5), (10, None), (20, 9)):
        lk2.add(seq, pos=["n"], kanji=[("字", False)], readings=[("じ", False)], freq_rank=rank)
    assert match_seq(tok, lk2) == 30  # best frequency rank wins over lower seq


def test_ranking_is_deterministic_regardless_of_insertion_order() -> None:
    rows = [(5, ["n"], "じ"), (3, ["v1"], "じ"), (4, ["n"], "し"), (2, ["prt"], "じ")]
    tok = _raw("字", "字", "じ", NOUN)
    results = set()
    for order in (rows, rows[::-1], rows[1:] + rows[:1]):
        lk = InMemoryMatchLookup()
        for seq, pos, rd in order:
            lk.add(seq, pos=pos, kanji=[("字", False)], readings=[(rd, False)])
        results.add(match_seq(tok, lk))
    assert results == {5}  # reading じ + POS n: 5 (3 is a verb, 2 a particle, 4 reads し)


@needs_sudachi
def test_same_input_same_seqs(sentences: tuple[FixtureSentence, ...]) -> None:
    lk = fixture_lookup()
    first = [[to_token(r, lk) for r in segment(s.text, lk.is_key)] for s in sentences[:50]]
    again = [[to_token(r, lk) for r in segment(s.text, lk.is_key)] for s in sentences[:50]]
    assert first == again


# --- fallbacks and unmapped -----------------------------------------------------------------


def test_lookup_tiers_lemma_then_surface_then_reading() -> None:
    lk = InMemoryMatchLookup()
    lk.add(1, pos=["n"], kanji=[("甲", False)], readings=[("こう", False)])
    lk.add(2, pos=["n"], kanji=[("乙", False)], readings=[("おつ", False)])
    lk.add(3, pos=["n"], readings=[("へい", False)])
    assert match_seq(_raw("甲", "甲", "こう", NOUN), lk) == 1
    # lemma unknown, surface known
    assert match_seq(_raw("乙", "乙々", "おつ", NOUN), lk) == 2
    # neither known: the derived reading as a kana form, POS-compatible only
    assert match_seq(_raw("丙", "丙", "へい", NOUN), lk) == 3
    assert match_seq(_raw("丙", "丙", "へい", ("副詞",)), lk) is None
    # never for proper nouns
    assert match_seq(_raw("丙", "丙", "へい", ("名詞", "固有名詞", "人名")), lk) is None


def test_digit_strings_are_unmapped_kanji_numerals_are_not() -> None:
    lk = InMemoryMatchLookup()
    lk.add(10, pos=["num"], readings=[("10", False)])
    lk.add(3, pos=["num"], kanji=[("三", True)], readings=[("さん", True)])
    num = ("名詞", "数詞")
    assert match_seq(_raw("１０", "10", "じゅう", num), lk) is None
    assert match_seq(_raw("三", "三", "さん", num), lk) == 3


def test_unknown_word_is_unmapped_and_classed() -> None:
    tok = to_token(
        _raw("ぴよ", "ぴよ", "ぴよ", ("名詞", "固有名詞", "人名")), InMemoryMatchLookup()
    )
    assert (tok.seq, tok.cls, tok.override) == (None, "name", False)


@pytest.mark.parametrize(
    ("tok", "expected"),
    [
        (_raw("見", "見る", "み", VERB), "みる"),
        (_raw("高かっ", "高い", "たかかっ", ("形容詞", "一般")), "たかい"),
        (_raw("食べ", "食べる", "たべ", VERB), "たべる"),
        (_raw("ネコ", "ネコ", "ねこ", NOUN), "ねこ"),
        (_raw("たべ", "食べる", "たべ", VERB), None),  # kana spelling: not derivable
    ],
)
def test_lemma_reading(tok: RawToken, expected: str | None) -> None:
    assert lemma_reading(tok) == expected


def test_retired_or_missing_entry_is_not_a_candidate() -> None:
    class Partial(InMemoryMatchLookup):
        def entry(self, seq: int) -> Any:
            return None if seq == 1 else super().entry(seq)

    lk = Partial()
    lk.add(1, pos=["n"], kanji=[("字", True)], readings=[("じ", True)])
    lk.add(2, pos=["n"], kanji=[("字", False)], readings=[("あざ", False)])
    assert match_seq(_raw("字", "字", "じ", NOUN), lk) == 2
