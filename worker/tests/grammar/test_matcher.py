"""T506a: matcher semantics on hand-built tokens, and test_rules (the rule-tester entry point)."""

from __future__ import annotations

from typing import Any

from kotobaya_worker.grammar import TestSentence, find_matches, parse_rule, test_rules, to_mtokens
from kotobaya_worker.grammar.matcher import STEP_BUDGET


def tok(
    text: str,
    at: int,
    surface: str,
    lemma: str | None = None,
    pos: tuple[str, ...] = ("名詞",),
    cform: str | None = None,
    reading: str = "",
) -> dict[str, Any]:
    """A C-12 token for ``surface`` at code-point index ``at`` of ``text`` (UTF-16 offsets)."""
    s = len(text[:at].encode("utf-16-le")) // 2
    e = s + len(surface.encode("utf-16-le")) // 2
    assert text[at : at + len(surface)] == surface
    return {
        "s": s, "e": e, "surface": surface, "lemma": lemma or surface, "reading": reading,
        "pos": list(pos), "cform": cform,
    }  # fmt: skip


def spans(rule: str, toks: list[dict[str, Any]]) -> list[tuple[int, int]]:
    return [(m.start, m.end) for m in find_matches(parse_rule(rule), to_mtokens(toks))]


# 行かざるを得ない (tokens as Sudachi gives them)
T1 = "行かざるを得ない"
TOKS1 = [
    tok(T1, 0, "行か", "行く", ("動詞", "非自立可能"), "未然形-一般"),
    tok(T1, 2, "ざる", "ぬ", ("助動詞",), "連体形-補助"),
    tok(T1, 4, "を", pos=("助詞", "格助詞")),
    tok(T1, 5, "得", "得る", ("動詞", "非自立可能"), "未然形-一般"),
    tok(T1, 6, "ない", pos=("助動詞",), cform="終止形-一般"),
]


def test_spec_example_matches_with_utf16_span() -> None:
    assert spans("[verb:未然形] ざる を 得 ない", TOKS1) == [(0, 8)]
    assert spans("[verb:連用形] ざる を 得 ない", TOKS1) == []
    assert spans("[adj:未然形] ざる を 得 ない", TOKS1) == []


def test_bare_word_matches_surface_or_lemma_and_keys_restrict() -> None:
    assert spans("[verb] ざる", TOKS1) == [(0, 4)]
    assert spans("行く ざる", TOKS1) == [(0, 4)]  # lemma
    assert spans("[surface=行く] ざる", TOKS1) == []
    assert spans("[lemma=行く] ざる", TOKS1) == [(0, 4)]
    assert spans("[verb, lemma!=行く] ざる", TOKS1) == []
    assert spans("[verb, lemma!=来る] ざる", TOKS1) == [(0, 4)]


def test_pos_levels_and_cform_prefixes() -> None:
    assert spans("[助詞-格助詞] 得", TOKS1) == [(4, 6)]
    assert spans("[助詞-係助詞] 得", TOKS1) == []
    assert spans("[*:未然形-一般] ざる", TOKS1) == [(0, 4)]
    assert spans("[*:未然形-撥音便] ざる", TOKS1) == []
    assert spans("を [cform=連用形]", TOKS1) == []  # を has no cform; 得 is 未然形


def test_surrogate_pairs_give_utf16_offsets() -> None:
    text = "𠮷野家に行かざるを得ない"  # 𠮷 is two UTF-16 units
    toks = [
        tok(text, 0, "𠮷野家"),
        tok(text, 3, "に", pos=("助詞",)),
        tok(text, 4, "行か", "行く", ("動詞",), "未然形-一般"),
        tok(text, 6, "ざる", "ぬ", ("助動詞",)),
        tok(text, 8, "を", pos=("助詞",)),
        tok(text, 9, "得", "得る", ("動詞",), "未然形-一般"),
        tok(text, 10, "ない", pos=("助動詞",)),
    ]
    res = test_rules(["[verb:未然形] ざる を 得 ない"], [TestSentence(text, toks)])
    (m,) = res.matches
    assert (m.start_offset, m.end_offset, m.text) == (5, 13, "行かざるを得ない")


def test_literal_needs_adjacent_tokens_and_spans_splits() -> None:
    text = "雨にもかかわらず、出た"
    split = [
        tok(text, 0, "雨"), tok(text, 1, "に"), tok(text, 2, "も"),
        tok(text, 3, "かかわら", "関わる", ("動詞",), "未然形-一般"), tok(text, 7, "ず"),
        tok(text, 9, "出"), tok(text, 10, "た"),
    ]  # fmt: skip
    whole = [tok(text, 0, "雨"), tok(text, 1, "にもかかわらず"), tok(text, 9, "出")]
    assert spans('"にもかかわらず"', split) == [(1, 8)]
    assert spans('"にもかかわらず"', whole) == [(1, 8)]
    # punctuation between tokens: invisible to bare words, blocks quoted text
    assert spans("ず 出", split) == [(7, 10)]
    assert spans('"ず出"', split) == []


def test_gap_is_lazy_and_bounded() -> None:
    text = "読めば読むほど読むほど"
    toks = [
        tok(text, 0, "読め", "読む", ("動詞",), "仮定形-一般"), tok(text, 2, "ば"),
        tok(text, 3, "読む", pos=("動詞",), cform="連体形-一般"), tok(text, 5, "ほど"),
        tok(text, 7, "読む", pos=("動詞",), cform="連体形-一般"), tok(text, 9, "ほど"),
    ]  # fmt: skip
    assert spans("[*:仮定形] ば … ほど", toks) == [(0, 7)]  # lazy: the first ほど
    assert spans("ば *{2} ほど", toks) == []
    assert spans("ば *{1,1} ほど", toks) == [(2, 7)]
    assert spans("ば … [surface=ない]", toks) == []


def test_optional_is_greedy_and_alternatives_ordered() -> None:
    text = "わけにはいかない"
    toks = [
        tok(text, 0, "わけ"), tok(text, 2, "に"), tok(text, 3, "は"),
        tok(text, 4, "いか", "行く", ("動詞",), "未然形-一般"), tok(text, 6, "ない"),
    ]  # fmt: skip
    assert spans("わけ に は いか (ない)?", toks) == [(0, 8)]
    assert spans("(わけ | 訳) に", toks) == [(0, 3)]
    assert spans("わけ (に は | に) [lemma=行く]", toks) == [(0, 6)]


def test_matches_do_not_overlap_and_scan_continues() -> None:
    text = "ねねねね"
    toks = [tok(text, i, "ね") for i in range(4)]
    assert spans("ね ね", toks) == [(0, 2), (2, 4)]
    assert spans("ね{1,3}", toks) == [(0, 3), (3, 4)]


def test_reading_folds_katakana_and_nfkc() -> None:
    text = "ＡＢＣです"
    toks = [tok(text, 0, "ＡＢＣ", reading="エービーシー"), tok(text, 3, "です")]
    assert spans("[reading=えーびーしー] です", toks) == [(0, 5)]
    assert spans("ABC です", toks) == [(0, 5)]


def test_empty_sentence_and_no_tokens() -> None:
    assert spans("ない", []) == []
    res = test_rules(["ない"], [TestSentence("", [])])
    assert res.matches == () and res.errors == ()


def test_pathological_rule_gives_up_instead_of_hanging() -> None:
    text = "ね" * 40
    toks = [tok(text, i, "ね") for i in range(40)]
    rule = "ね{0,10} ね{0,10} ね{0,10} ね{0,10} [surface=x]"
    assert spans(rule, toks) == []
    assert STEP_BUDGET <= 100_000


def test_rules_reports_errors_per_rule_and_still_matches_the_rest() -> None:
    res = test_rules(
        ["[verbb] ない", "を 得 ない", "(わけ"],
        [
            TestSentence(T1, TOKS1),
            TestSentence("得ない", [tok("得ない", 0, "得"), tok("得ない", 1, "ない")]),
        ],
    )
    assert [(e.rule_index, e.column) for e in res.errors] == [(0, 2), (2, 1)]
    assert [(m.rule_index, m.sentence_index, m.text) for m in res.matches] == [(1, 0, "を得ない")]
    js = res.to_json()
    assert js["errors"][0]["message"].startswith("unknown part of speech")
    assert js["matches"][0] == {
        "rule_index": 1, "sentence_index": 0, "start_offset": 4, "end_offset": 8, "text": "を得ない",
    }  # fmt: skip


def test_raw_sudachi_pos_drops_only_trailing_padding() -> None:
    raw = {"s": 0, "e": 1, "surface": "x", "lemma": "x", "pos": ["助詞", "格助詞", "*", "*"]}
    assert to_mtokens([raw])[0].pos == ("助詞", "格助詞")
    assert spans("[助詞-格助詞]", [raw]) == [(0, 1)]
