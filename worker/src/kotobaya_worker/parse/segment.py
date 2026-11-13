"""Sentence -> raw tokens: normalise, segment (C with B/A fallback), deinflect.

Output offsets are UTF-16 code units into the ORIGINAL sentence text, half-open, ascending
and non-overlapping (``app.sentence.tokens``). Punctuation and whitespace are not emitted:
the gaps between tokens are untokenised text, and ``len(tokens)`` is the segment's
``token_count`` contribution. JMdict matching, ``seq`` and ``class`` are T403b's job.
"""

from __future__ import annotations

from dataclasses import dataclass

from kotobaya_worker.dict.normalize import normalize_form
from kotobaya_worker.parse.deinflect import KeyPredicate, lemma_of, reading_of, rewrite
from kotobaya_worker.parse.normalize import normalize_text
from kotobaya_worker.parse.sudachi import Analyzer, Morph, get_analyzer

# Sudachi top-level POS that are not word tokens.
NON_WORD_POS = frozenset({"補助記号", "空白"})


@dataclass(frozen=True)
class RawToken:
    s: int  # UTF-16 code-unit offset into the original text
    e: int
    surface: str  # == original_text[s:e] (in UTF-16 terms)
    lemma: str
    reading: str  # hiragana reading of the surface
    pos: tuple[str, ...]  # Sudachi POS levels 1-4, '*' dropped: ("動詞", "一般")
    cform: str | None  # Sudachi conjugation form ("連用形-一般"), None if it does not inflect


def _never(_norm: str) -> bool:
    return False


def _is_word(m: Morph) -> bool:
    return m.pos[0] not in NON_WORD_POS and not m.surface.isspace()


def utf16_slice(text: str, s: int, e: int) -> str:
    """``text[s:e]`` with UTF-16 code-unit offsets (as the database and JS clients use)."""
    return text.encode("utf-16-le")[2 * s : 2 * e].decode("utf-16-le")


def segment(
    sentence_text: str,
    is_jmdict_key: KeyPredicate | None = None,
    analyzer: Analyzer | None = None,
) -> list[RawToken]:
    """Segment one sentence.

    ``is_jmdict_key(norm)`` answers whether ``norm`` (normalised like
    ``ref.dict_entry_form.norm``) is a JMdict kanji or kana form. Mode C tokens whose lemma
    and surface are both unknown are re-split with mode B, then A; prefix compounds are
    joined only when the joined form is known. ``None`` means plain mode C, no joins.
    ``analyzer`` defaults to the process-wide one for the best installed dictionary.
    """
    if not sentence_text:
        return []
    an = analyzer if analyzer is not None else get_analyzer()
    is_key = is_jmdict_key if is_jmdict_key is not None else _never

    def keep(m: Morph) -> bool:
        if not _is_word(m) or m.pos[1] == "数詞":
            return True
        return is_key(normalize_form(lemma_of(m))) or is_key(normalize_form(m.surface))

    norm = normalize_text(sentence_text)
    morphs = an.analyze(norm.text, keep if is_jmdict_key is not None else None)
    morphs = rewrite(morphs, is_key)

    out: list[RawToken] = []
    for m in morphs:
        if not _is_word(m):
            continue
        s, e = norm.to_original(m.begin, m.end)
        out.append(
            RawToken(
                s=s,
                e=e,
                surface=utf16_slice(sentence_text, s, e),
                lemma=lemma_of(m),
                reading=reading_of(m),
                pos=tuple(p for p in m.pos[:4] if p != "*"),
                cform=m.cform if m.cform != "*" else None,
            )
        )
    return out
