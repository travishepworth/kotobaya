from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor

import pytest

from kotobaya_worker.parse import RawToken, segment
from kotobaya_worker.parse.deinflect import lemma_of, rewrite
from kotobaya_worker.parse.segment import utf16_slice
from kotobaya_worker.parse.sudachi import Morph, installed_dictionaries

from .conftest import FixtureSentence

pytestmark = pytest.mark.skipif(
    not installed_dictionaries(), reason="no Sudachi dictionary installed"
)

KeyFn = Callable[[str], bool]


def _spans(tokens: list[RawToken]) -> list[tuple[str, str]]:
    return [(t.surface, t.lemma) for t in tokens]


def _assert_offsets(text: str, tokens: list[RawToken]) -> None:
    prev = 0
    n16 = len(text.encode("utf-16-le")) // 2
    for t in tokens:
        assert prev <= t.s < t.e <= n16, (text, t)
        assert utf16_slice(text, t.s, t.e) == t.surface, (text, t)
        prev = t.e


# --- offsets ---------------------------------------------------------------------------


def test_offsets_slice_original_text_over_fixture(
    sentences: tuple[FixtureSentence, ...], is_key: KeyFn
) -> None:
    for s in sentences:
        _assert_offsets(s.text, segment(s.text, is_key))


@pytest.mark.parametrize(
    "text",
    [
        "ｶﾒﾗを持って旅行に行く。",
        "ﾃﾞｰﾀﾍﾞｰｽを使う。",
        "ＡＢＣ会社のメールを読んだ。",
        "２０２６年３月１５日",
        "こゝろの中で、あゝ。",
        "人々が時々来る。",
        "ラ－メンを食べたい～",
        "𠮟られた😀けど、平気。",
        "  空白　と\tタブ  ",
    ],
)
def test_offsets_slice_original_text_width_and_odoriji(text: str, is_key: KeyFn) -> None:
    tokens = segment(text, is_key)
    assert tokens
    _assert_offsets(text, tokens)


def test_width_folded_surface_keeps_original_but_lemma_is_folded(is_key: KeyFn) -> None:
    tokens = segment("ｶﾒﾗと５時", is_key)
    assert ("ｶﾒﾗ", "カメラ") in _spans(tokens)
    assert ("５", "5") in _spans(tokens)


def test_iteration_mark_expanded_for_lemma(is_key: KeyFn) -> None:
    tokens = segment("こゝろ", is_key)
    assert _spans(tokens) == [("こゝろ", "心")]


def test_empty_and_punctuation_only() -> None:
    assert segment("") == []
    assert segment("。、！？　 ") == []


def test_punctuation_and_whitespace_not_emitted(is_key: KeyFn) -> None:
    tokens = segment("はい、 そうです。", is_key)
    assert all(t.surface.strip() and t.surface not in "、。" for t in tokens)


def test_token_fields(is_key: KeyFn) -> None:
    tokens = segment("食べました", is_key)
    first = tokens[0]
    assert (first.surface, first.lemma, first.reading) == ("食べ", "食べる", "たべ")
    assert first.pos[0] == "動詞" and "*" not in first.pos
    assert first.cform is not None and first.cform.startswith("連用形")
    assert [t.lemma for t in tokens] == ["食べる", "ます", "た"]


# --- C -> B fallback -------------------------------------------------------------------


def test_fallback_splits_compound_absent_from_jmdict(is_key: KeyFn) -> None:
    assert not is_key("映画鑑賞")  # fixture s76: split
    text = "映画鑑賞が趣味です。"
    assert ("映画鑑賞", "映画鑑賞") in _spans(segment(text))  # plain mode C keeps it
    assert _spans(segment(text, is_key))[:2] == [("映画", "映画"), ("鑑賞", "鑑賞")]


def test_fallback_keeps_compound_present_in_jmdict(is_key: KeyFn) -> None:
    assert is_key("国際電話")  # fixture s57: kept
    assert _spans(segment("国際電話をかけた。", is_key))[0] == ("国際電話", "国際電話")


def test_fallback_uses_mode_a_when_b_does_not_split(is_key: KeyFn) -> None:
    # 食べすぎ is one token in both C and B; A splits it.
    assert _spans(segment("食べすぎて", is_key))[:2] == [("食べ", "食べる"), ("すぎ", "すぎる")]


# --- deinflection rules ----------------------------------------------------------------


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("勉強している", ["勉強", "する", "て", "いる"]),  # する/いる not 為る/居る
        ("たべた", ["食べる", "た"]),  # kana spelling -> kanji lemma
        ("いいね", ["いい", "ね"]),
        ("静かに", ["静か", "に"]),  # adverbial に is not だ
        ("来たら", ["来る", "たら"]),
        ("行かなきゃ", ["行く", "なきゃ"]),
        ("そうじゃない", ["そう", "じゃ", "ない"]),
        ("いけません", ["いける", "ます", "ぬ"]),  # not 行く: different okurigana
    ],
)
def test_lemmas(text: str, expected: list[str], is_key: KeyFn) -> None:
    assert [t.lemma for t in segment(text, is_key)] == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("行こう", [("行こ", "行く"), ("う", "う")]),
        ("会いましょう", [("会い", "会う"), ("ましょ", "ます"), ("う", "う")]),
        ("食べよう", [("食べ", "食べる"), ("よう", "よう")]),
        ("雨だろう", [("雨", "雨"), ("だろ", "だ"), ("う", "う")]),
    ],
)
def test_volitional_split_into_auxiliary_chain(
    text: str, expected: list[tuple[str, str]], is_key: KeyFn
) -> None:
    tokens = segment(text, is_key)
    assert _spans(tokens) == expected
    _assert_offsets(text, tokens)


def test_node_joins(is_key: KeyFn) -> None:
    assert ("ので", "ので") in _spans(segment("眠いので寝る", is_key))
    assert ("ので", "ので") not in _spans(segment("学生のである", is_key))
    assert ("かも", "かも") in _spans(segment("寝てるかもしれない", is_key))
    assert ("お茶", "お茶") in _spans(segment("お茶を飲む", is_key))
    assert ("お願い", "お願い") in _spans(segment("お願いします", is_key))
    # お + 待ち is not a JMdict word: not joined
    assert ("お", "お") in _spans(segment("お待ちください", is_key))


def test_rewrite_on_plain_morphs() -> None:
    def m(b: int, surface: str, d: str, pos0: str, cform: str = "*") -> Morph:
        return Morph(b, b + len(surface), surface, d, d, surface, (pos0, "*", "*", "*", "*", cform))

    ms = [m(0, "の", "の", "助詞"), m(1, "で", "だ", "助動詞"), m(2, "寝る", "寝る", "動詞")]
    out = rewrite(ms, lambda _k: False)
    assert [x.surface for x in out] == ["ので", "寝る"]
    assert lemma_of(out[0]) == "ので"
    assert rewrite([], lambda _k: False) == []


# --- concurrency -----------------------------------------------------------------------


def test_segment_is_thread_safe(sentences: tuple[FixtureSentence, ...], is_key: KeyFn) -> None:
    texts = [s.text for s in sentences]
    serial = [segment(t, is_key) for t in texts]
    with ThreadPoolExecutor(max_workers=4) as pool:
        parallel = list(pool.map(lambda t: segment(t, is_key), texts))
    assert parallel == serial
