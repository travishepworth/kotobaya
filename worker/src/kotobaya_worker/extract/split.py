"""Japanese sentence splitter. Keeps the original characters; never yields empty text."""

from __future__ import annotations

TERMINATORS = frozenset("。！？!?…‥．")
OPENERS = frozenset("「『（(【［[〈《〔｛{")
CLOSERS = frozenset("」』）)】］]〉》〕｝}")
_SPACE = " \t　"


def split_sentences(text: str) -> list[str]:
    """Split `text` into sentences.

    Rules: a run of terminators (。！？!?…‥) ends a sentence and absorbs closing brackets that
    directly follow. Nothing splits inside an open bracket pair (「」『』（）...); a pair closing
    right after a terminator (「はい。」) ends the sentence. A newline always ends a sentence and
    resets bracket depth, so an unbalanced opener cannot swallow the rest of the text.
    """
    out: list[str] = []
    buf: list[str] = []
    depth = 0

    def flush() -> None:
        s = "".join(buf).strip(" \t\r　")
        buf.clear()
        if s:
            out.append(s)

    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        i += 1
        if ch == "\n":
            flush()
            depth = 0
            continue
        buf.append(ch)
        if ch in OPENERS:
            depth += 1
        elif ch in CLOSERS:
            depth = max(depth - 1, 0)
            if depth == 0:
                prev = next((c for c in reversed(buf[:-1]) if c not in _SPACE), "")
                if prev in TERMINATORS or prev in CLOSERS:
                    while i < n and (text[i] in CLOSERS or text[i] in TERMINATORS):
                        buf.append(text[i])
                        i += 1
                    # 「はい。」と言った。 continues; 「はい。」「うん。」 or end of line splits.
                    if i >= n or text[i] in OPENERS or text[i] in " \t　\r\n":
                        flush()
        elif ch in TERMINATORS and depth == 0:
            while i < n and (text[i] in TERMINATORS or text[i] in CLOSERS):
                buf.append(text[i])
                i += 1
            flush()
    flush()
    return out
