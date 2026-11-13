"""Dictionary form (lemma) and reading per token, plus token-sequence fixes (SPEC Parsing step 3).

Sudachi already deinflects: every morpheme carries its dictionary form and conjugation info.
This module picks the lemma JMdict keys the word under, and applies the few sequence rules in
:mod:`.rules` (volitional split, ので / かも / prefix joins). Auxiliary chains stay separate
tokens: 食べ|させ|られ|た, each with its own lemma.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import replace

from kotobaya_worker.dict.normalize import fold_katakana, is_kanji, normalize_form
from kotobaya_worker.parse.deinflect.rules import (
    DICTIONARY_FORM_POS,
    FUSING_PREFIXES,
    KANA_LEMMAS,
    SURFACE_LEMMAS,
    VOLITIONAL_CFORM,
    YOU_CONJ_PREFIXES,
)
from kotobaya_worker.parse.sudachi import Morph

# Takes a lookup key already normalised like ``ref.dict_entry_form.norm``.
KeyPredicate = Callable[[str], bool]

_INFLECTING = frozenset({"動詞", "形容詞"})


def _has_kanji(text: str) -> bool:
    return any(is_kanji(ch) for ch in text)


def lemma_of(m: Morph) -> str:
    """The lemma (dictionary form as JMdict keys it) for one morpheme.

    Sudachi's dictionary form keeps the spelling of the text (たべる for たべ), while its
    normalized form maps kana spellings to the kanji one (食べる) but also collapses variants
    (いける -> 行く) and over-kanjifies kana words (する -> 為る). So: dictionary form, except
    a kana-only dictionary form takes the normalized kanji form when that is the same word.
    """
    override = SURFACE_LEMMAS.get((m.surface, m.dictionary_form))
    if override is not None:
        return override
    d, n = m.dictionary_form, m.normalized_form
    # Voiced after a nasal-sound stem (読んで, 読んだ): JMdict has only て and た.
    if m.surface == "で" and m.pos[:2] == ("助詞", "接続助詞"):
        return "て"
    if m.conj_type == "助動詞-タ":
        return "た"
    if m.pos[0] in DICTIONARY_FORM_POS or m.pos[1] == "数詞":
        return d
    if _has_kanji(d) or not _has_kanji(n) or d in KANA_LEMMAS:
        return d
    if m.pos[0] in _INFLECTING and d[-1:] != n[-1:]:
        return d  # different okurigana: a different word (いける vs 行く)
    return n


def reading_of(m: Morph) -> str:
    """Reading of the surface, katakana folded to hiragana (ー kept)."""
    return fold_katakana(m.reading)


def _split_volitional(m: Morph) -> list[Morph]:
    """行こう -> 行こ + う, 食べよう -> 食べ + よう, ましょう -> ましょ + う."""
    if m.cform != VOLITIONAL_CFORM or not m.surface.endswith("う"):
        return [m]
    tail = (
        "よう" if m.conj_type.startswith(YOU_CONJ_PREFIXES) and m.surface.endswith("よう") else "う"
    )
    k = len(tail)
    if len(m.surface) <= k or len(m.reading) <= k:
        return [m]
    stem = replace(
        m,
        end=m.end - k,
        surface=m.surface[:-k],
        reading=m.reading[:-k],
        pos=(m.pos[0], m.pos[1], m.pos[2], m.pos[3], m.pos[4], "未然形-一般"),
    )
    aux = Morph(
        begin=m.end - k,
        end=m.end,
        surface=tail,
        dictionary_form=tail,
        normalized_form=tail,
        reading="ウ" if tail == "う" else "ヨウ",
        pos=(
            "助動詞",
            "*",
            "*",
            "*",
            "助動詞-ウ" if tail == "う" else "助動詞-ヨウ",
            "終止形-一般",
        ),
    )
    return [stem, aux]


def _join(a: Morph, b: Morph, pos: tuple[str, str, str, str, str, str]) -> Morph:
    surface = a.surface + b.surface
    return Morph(
        begin=a.begin,
        end=b.end,
        surface=surface,
        dictionary_form=surface,
        normalized_form=surface,
        reading=a.reading + b.reading,
        pos=pos,
    )


_PARTICLE_CONJ = ("助詞", "接続助詞", "*", "*", "*", "*")
_PARTICLE_ADV = ("助詞", "副助詞", "*", "*", "*", "*")
_NOUN = ("名詞", "普通名詞", "一般", "*", "*", "*")


def _try_join(ms: Sequence[Morph], i: int, is_key: KeyPredicate) -> Morph | None:
    """A joined token for ms[i], ms[i+1], or None."""
    a, b = ms[i], ms[i + 1]
    after = ms[i + 2] if i + 2 < len(ms) else None
    if b.begin != a.end:
        return None
    # の + で(だ): the conjunctive particle ので, except in のである.
    if (
        a.surface == "の"
        and a.pos[0] == "助詞"
        and b.surface == "で"
        and b.dictionary_form == "だ"
        and not (after is not None and after.dictionary_form == "ある")
    ):
        return _join(a, b, _PARTICLE_CONJ)
    # か + も before しれる: かもしれない.
    if (
        a.surface == "か"
        and b.surface == "も"
        and after is not None
        and lemma_of(after) in ("しれる", "知れる")
    ):
        return _join(a, b, _PARTICLE_ADV)
    # お/ご + noun (or verb stem) when the whole is a JMdict word: お茶, お願い.
    if (
        a.pos[0] == "接頭辞"
        and a.surface in FUSING_PREFIXES
        and (b.pos[0] == "名詞" or (b.pos[0] == "動詞" and b.cform.startswith("連用形")))
        and is_key(normalize_form(a.surface + b.surface))
    ):
        return _join(a, b, _NOUN)
    return None


def rewrite(ms: Sequence[Morph], is_key: KeyPredicate) -> list[Morph]:
    """Apply the sequence rules: volitional split, then left-to-right pairwise joins."""
    split: list[Morph] = []
    for m in ms:
        split.extend(_split_volitional(m))
    out: list[Morph] = []
    i = 0
    while i < len(split):
        joined = _try_join(split, i, is_key) if i + 1 < len(split) else None
        if joined is not None:
            out.append(joined)
            i += 2
        else:
            out.append(split[i])
            i += 1
    return out
