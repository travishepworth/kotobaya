"""EPUB extractor: spine order, one `chapter` segment per spine document.

Location is a JSON string ``{"cfi": "/6/<2*(spine_index+1)>!/p[<n>]"}``: the CFI-like spine step
(1-based spine position doubled, as in real CFIs) followed by the 1-based paragraph number within
that chapter. It is a locator for this app's own reader, not a standards-complete EPUB CFI.
"""

from __future__ import annotations

import io
import json
import posixpath
import zipfile
from collections.abc import Mapping
from urllib.parse import unquote

from kotobaya_worker.extract.html import ruby_option
from kotobaya_worker.extract.html.tree import (
    HEADINGS,
    Node,
    find_all,
    paragraphs,
    parse_html,
    sentences_of,
)
from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title

_MAX_ENTRY = 50 * 1024 * 1024


def _read(z: zipfile.ZipFile, name: str) -> str:
    info = z.getinfo(name)
    if info.file_size > _MAX_ENTRY:
        raise ValueError(f"EPUB entry {name!r} is too large")
    return z.read(name).decode("utf-8-sig", errors="replace")


def _resolve(base_dir: str, href: str) -> str:
    return posixpath.normpath(posixpath.join(base_dir, unquote(href.split("#")[0])))


def _toc_titles(z: zipfile.ZipFile, items: dict[str, Node], opf_dir: str) -> dict[str, str]:
    """Chapter file -> title from the EPUB 3 nav document or EPUB 2 NCX."""
    titles: dict[str, str] = {}
    for item in items.values():
        is_nav = "nav" in item.attrs.get("properties", "").split()
        is_ncx = item.attrs.get("media-type") == "application/x-dtbncx+xml"
        if not (is_nav or is_ncx):
            continue
        path = _resolve(opf_dir, item.attrs.get("href", ""))
        if path not in z.namelist():
            continue
        doc = parse_html(_read(z, path))
        base = posixpath.dirname(path)
        if is_nav:
            for a in find_all(doc, "a"):
                href = a.attrs.get("href", "")
                label = " ".join(a.text().split())
                if href and label:
                    titles.setdefault(_resolve(base, href), label)
        else:
            for np in find_all(doc, "navpoint"):
                labels = find_all(np, "text")
                contents = find_all(np, "content")
                if labels and contents:
                    label = " ".join(labels[0].text().split())
                    src = contents[0].attrs.get("src", "")
                    if label and src:
                        titles.setdefault(_resolve(base, src), label)
    return titles


def extract_epub(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    ruby = ruby_option(options)
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:
        raise ValueError("not a valid EPUB (bad zip)") from e
    with z:
        container = parse_html(_read(z, "META-INF/container.xml"))
        roots = find_all(container, "rootfile")
        if not roots:
            raise ValueError("EPUB has no rootfile")
        opf_path = roots[0].attrs.get("full-path", "")
        opf = parse_html(_read(z, opf_path))
        opf_dir = posixpath.dirname(opf_path)
        items = {n.attrs.get("id", ""): n for n in find_all(opf, "item")}
        toc = _toc_titles(z, items, opf_dir)
        out: list[ExtractedSegment] = []
        spine = find_all(opf, "itemref")
        for idx, ref in enumerate(spine):
            item = items.get(ref.attrs.get("idref", ""))
            if item is None or "nav" in item.attrs.get("properties", "").split():
                continue
            path = _resolve(opf_dir, item.attrs.get("href", ""))
            if path not in z.namelist():
                continue
            doc = parse_html(_read(z, path))
            headings = [n for n in doc.iter() if n.tag in HEADINGS]
            heading_text = " ".join(headings[0].text().split()) if headings else ""
            title = toc.get(path) or heading_text or _doc_title(doc) or default_title(path)
            skip = frozenset(id(h) for h in headings[:1]) if heading_text else frozenset()
            sentences: list[ExtractedSentence] = []
            step = 2 * (idx + 1)
            for pno, (text, hints) in enumerate(paragraphs(doc, ruby, skip), start=1):
                loc = json.dumps({"cfi": f"/6/{step}!/p[{pno}]"}, separators=(",", ":"))
                sentences.extend(sentences_of(text, hints, loc))
            if sentences:
                out.append(ExtractedSegment("chapter", title, tuple(sentences)))
    return out


def _doc_title(doc: Node) -> str:
    for n in find_all(doc, "title"):
        t = " ".join("".join(c for c in n.children if isinstance(c, str)).split())
        if t:
            return t
    return ""
