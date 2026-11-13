from __future__ import annotations

import pytest

from kotobaya_worker.parse.normalize import normalize_text


def _orig(original: str, s: int, e: int) -> str:
    return original.encode("utf-16-le")[2 * s : 2 * e].decode("utf-16-le")


@pytest.mark.parametrize(
    ("original", "expected"),
    [
        ("", ""),
        ("ＡＢＣ１２３", "ABC123"),
        ("ｶﾒﾗ", "カメラ"),
        ("ﾃﾞｰﾀﾍﾞｰｽ", "データベース"),
        ("ﾊﾟﾝ", "パン"),
        ("こゝろ", "こころ"),
        ("いすゞ", "いすず"),
        ("バナヽ", "バナナ"),
        ("ハヾ", "ハバ"),
        ("人々", "人々"),  # 々 kept: JMdict keys contain it
        ("ゝ", "ゝ"),  # nothing to repeat: kept
        ("ラ－メン", "ラーメン"),
        ("ラ―メン", "ラーメン"),
        ("A－B", "A-B"),  # not after kana: plain width fold
        ("10～20", "10〜20"),
        ("食べる", "食べる"),
    ],
)
def test_normalize_text(original: str, expected: str) -> None:
    assert normalize_text(original).text == expected


@pytest.mark.parametrize("original", ["ﾃﾞｰﾀを𠮟る。", "こゝろ・ＡＢＣ", "ｶﾞｷﾞｸﾞ", "", "😀ﾊﾟﾝ々ヾ"])
def test_offset_map_points_into_original_utf16(original: str) -> None:
    n = normalize_text(original)
    assert len(n.spans) == len(n.text)
    utf16_len = len(original.encode("utf-16-le")) // 2
    prev_end = 0
    for i in range(len(n.text)):
        s, e = n.to_original(i, i + 1)
        assert 0 <= s < e <= utf16_len
        assert s >= prev_end or (s, e) == n.spans[i - 1]
        prev_end = e
    if n.text:
        assert n.to_original(0, len(n.text)) == (0, utf16_len)


def test_half_width_voiced_kana_maps_to_both_original_units() -> None:
    n = normalize_text("ﾃﾞｰﾀ")
    assert n.text == "データ"
    assert n.to_original(0, 1) == (0, 2)  # デ <- ﾃﾞ
    assert _orig("ﾃﾞｰﾀ", *n.to_original(0, 3)) == "ﾃﾞｰﾀ"


def test_astral_character_spans_two_utf16_units() -> None:
    n = normalize_text("𠮟る")
    assert n.to_original(0, 1) == (0, 2)
    assert n.to_original(1, 2) == (2, 3)


def test_bad_range_rejected() -> None:
    with pytest.raises(ValueError):
        normalize_text("abc").to_original(2, 2)
