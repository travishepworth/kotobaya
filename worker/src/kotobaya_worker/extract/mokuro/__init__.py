"""Mokuro extractor: one `volume` segment, pages in order, sentences per text block.

Location is a JSON string ``{"page": <1-based>, "box": [x, y, w, h]}`` (the DB comment's shape).
Mokuro stores `box` as [x1, y1, x2, y2]; it is converted to x/y/w/h. Unknown keys are ignored.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence

from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.split import split_sentences


def _num(v: object) -> float | None:
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def _fmt(x: float) -> int | float:
    return int(x) if x == int(x) else x


def _box(raw: object) -> list[int | float] | None:
    if not isinstance(raw, Sequence) or isinstance(raw, str) or len(raw) != 4:
        return None
    n = [_num(v) for v in raw]
    if any(v is None for v in n):
        return None
    x1, y1, x2, y2 = (float(v) for v in n if v is not None)
    return [_fmt(x1), _fmt(y1), _fmt(x2 - x1), _fmt(y2 - y1)]


def _first_point(c: object) -> tuple[float, float] | None:
    # lines_coords entry: 4 [x, y] points; use the top-left-most corner's x and y.
    if not isinstance(c, Sequence) or isinstance(c, str):
        return None
    xs: list[float] = []
    ys: list[float] = []
    for p in c:
        if isinstance(p, Sequence) and not isinstance(p, str) and len(p) == 2:
            x, y = _num(p[0]), _num(p[1])
            if x is not None and y is not None:
                xs.append(x)
                ys.append(y)
    return (min(xs), min(ys)) if xs else None


def _ordered_lines(block: Mapping[str, object]) -> list[str]:
    raw = block.get("lines")
    lines = [str(x) for x in raw] if isinstance(raw, list) else []
    coords = block.get("lines_coords")
    if isinstance(coords, list) and len(coords) == len(lines) and lines:
        pts = [_first_point(c) for c in coords]
        if all(p is not None for p in pts):
            idx = list(range(len(lines)))
            if block.get("vertical") is True:
                # Vertical Japanese reads right to left, then top to bottom.
                idx.sort(key=lambda i: (-(pts[i] or (0, 0))[0], (pts[i] or (0, 0))[1]))
            else:
                idx.sort(key=lambda i: ((pts[i] or (0, 0))[1], (pts[i] or (0, 0))[0]))
            lines = [lines[i] for i in idx]
    return lines


def extract_mokuro(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    doc = json.loads(data.decode("utf-8-sig"))
    if not isinstance(doc, dict):
        raise ValueError("mokuro file must be a JSON object")  # noqa: TRY004
    sentences: list[ExtractedSentence] = []
    pages = doc.get("pages")
    for pno, page in enumerate(pages if isinstance(pages, list) else [], start=1):
        blocks = page.get("blocks") if isinstance(page, dict) else None
        for block in blocks if isinstance(blocks, list) else []:
            if not isinstance(block, dict):
                continue
            text = "".join(ln.strip() for ln in _ordered_lines(block))
            box = _box(block.get("box"))
            loc = json.dumps({"page": pno, "box": box}, separators=(",", ":"))
            for s in split_sentences(text):
                sentences.append(ExtractedSentence(s, location=loc))
    title = next(
        (str(doc[k]) for k in ("volume", "title") if isinstance(doc.get(k), str) and doc[k]),
        default_title(filename),
    )
    return [ExtractedSegment("volume", title, tuple(sentences))]
