"""Sentence text normalisation for segmentation (SPEC Parsing step 1).

Unlike :func:`kotobaya_worker.dict.normalize.normalize_form` (the ``norm`` lookup key: NFKC
plus katakana folded to hiragana), this keeps the script of the text, because Sudachi needs
katakana as katakana. It only undoes surface variation that hurts segmentation:

- width: full-width ASCII letters/digits/symbols to ASCII, half-width katakana (with their
  separate voicing marks) to full-width;
- kana iteration marks ``ゝゞヽヾ`` expanded to the repeated kana (``々`` is kept: JMdict keys
  contain it, e.g. 人々);
- long-vowel look-alikes after kana (``－ ― ‐ − ─ ｰ``) to ``ー``; wave-dash variants to ``〜``.

The result carries an offset map so every span in the normalised text maps back to UTF-16
code-unit offsets in the ORIGINAL text (``app.sentence.tokens`` ``s``/``e``).
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass

_HIRA_ITER = "ゝ"  # ゝ
_HIRA_ITER_V = "ゞ"  # ゞ
_KATA_ITER = "ヽ"  # ヽ
_KATA_ITER_V = "ヾ"  # ヾ
_ITER_MARKS = frozenset((_HIRA_ITER, _HIRA_ITER_V, _KATA_ITER, _KATA_ITER_V))
_VOICED_ITER = frozenset((_HIRA_ITER_V, _KATA_ITER_V))
_HW_VOICING = frozenset(("ﾞ", "ﾟ"))  # ﾞ ﾟ
_LONG_VOWEL_LOOKALIKES = frozenset(("－", "―", "‐", "−", "─", "ｰ"))
_WAVE_DASHES = frozenset(("～", "∼", "⁓", "〜"))
_CHOONPU = "ー"
_WAVE = "〜"
_KATA_SHIFT = 0x60


@dataclass(frozen=True)
class Normalized:
    """Normalised text plus, per normalised code point, its ORIGINAL UTF-16 span.

    ``spans[i] = (s, e)`` is the half-open UTF-16 range of the original text that produced
    ``text[i]``. Several normalised code points may share one original span (never the
    reverse: a half-width kana with its voicing mark becomes ONE code point spanning both).
    """

    original: str
    text: str
    spans: tuple[tuple[int, int], ...]

    def to_original(self, start: int, end: int) -> tuple[int, int]:
        """Map a half-open code-point range of ``text`` to a UTF-16 range of ``original``."""
        if not 0 <= start < end <= len(self.text):
            raise ValueError(f"bad range [{start}, {end}) for text of length {len(self.text)}")
        return self.spans[start][0], self.spans[end - 1][1]


def _utf16_len(ch: str) -> int:
    return 2 if ord(ch) > 0xFFFF else 1


def is_kana(ch: str) -> bool:
    cp = ord(ch)
    return 0x3041 <= cp <= 0x309F or 0x30A0 <= cp <= 0x30FF


def _is_katakana(ch: str) -> bool:
    return 0x30A1 <= ord(ch) <= 0x30FA


def _is_hiragana(ch: str) -> bool:
    return 0x3041 <= ord(ch) <= 0x3096


def _voiced(ch: str) -> str:
    """か -> が; characters without a voiced form are returned unchanged."""
    composed = unicodedata.normalize("NFC", ch + "゙")
    return composed if len(composed) == 1 else ch


def _unvoiced(ch: str) -> str:
    decomposed = unicodedata.normalize("NFD", ch)
    return decomposed[0]


def _expand_iteration(mark: str, prev: str | None) -> str:
    """ゝ/ゞ/ヽ/ヾ -> the previous kana (unvoiced / voiced), in the mark's script."""
    if prev is None or not (_is_hiragana(prev) or _is_katakana(prev)):
        return mark
    base = _unvoiced(prev)
    if mark in (_HIRA_ITER, _HIRA_ITER_V) and _is_katakana(base):
        base = chr(ord(base) - _KATA_SHIFT)
    elif mark in (_KATA_ITER, _KATA_ITER_V) and _is_hiragana(base):
        base = chr(ord(base) + _KATA_SHIFT)
    return _voiced(base) if mark in _VOICED_ITER else base


def _fold_width(ch: str, nxt: str | None) -> tuple[str, int]:
    """Fold one character (or a half-width kana + voicing mark). Returns (text, chars used)."""
    cp = ord(ch)
    if 0xFF01 <= cp <= 0xFF5D:  # full-width ASCII block (FF5E is a wave dash, see above)
        return chr(cp - 0xFEE0), 1
    if 0xFF61 <= cp <= 0xFF9F:  # half-width katakana and punctuation
        if nxt is not None and nxt in _HW_VOICING:
            pair = unicodedata.normalize("NFKC", ch + nxt)
            if len(pair) == 1:
                return pair, 2
        if ch == "ﾞ":
            return "゛", 1  # spacing ゛, not the combining mark NFKC would give
        if ch == "ﾟ":
            return "゜", 1
        return unicodedata.normalize("NFKC", ch), 1
    return ch, 1


def normalize_text(original: str) -> Normalized:
    """Normalise ``original`` for segmentation; see the module docstring for the rules."""
    out: list[str] = []
    spans: list[tuple[int, int]] = []
    # UTF-16 start offset of every original code point, plus the end.
    starts = [0]
    for ch in original:
        starts.append(starts[-1] + _utf16_len(ch))

    i = 0
    n = len(original)
    while i < n:
        ch = original[i]
        nxt = original[i + 1] if i + 1 < n else None
        prev = out[-1] if out else None
        used = 1
        if ch in _ITER_MARKS:
            piece = _expand_iteration(ch, prev)
        elif ch in _LONG_VOWEL_LOOKALIKES and prev is not None and is_kana(prev):
            piece = _CHOONPU
        elif ch in _WAVE_DASHES:
            piece = _WAVE
        else:
            piece, used = _fold_width(ch, nxt)
        span = (starts[i], starts[i + used])
        for p in piece:
            out.append(p)
            spans.append(span)
        i += used
    return Normalized(original=original, text="".join(out), spans=tuple(spans))
