"""HTML / URL extractor: stdlib readability heuristic, one `whole` segment."""

from __future__ import annotations

import re
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from urllib.parse import urlsplit

from kotobaya_worker.extract.html.tree import (
    DROP,
    HEADINGS,
    Node,
    find_all,
    paragraphs,
    parse_html,
    sentences_of,
)
from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.text.txt import decode_japanese

MAX_BYTES = 5 * 1024 * 1024
TIMEOUT_S = 15.0
MAX_REDIRECTS = 5
_NOISE_TAGS = frozenset(["nav", "aside", "header", "footer", "form"])
_NOISE_ATTR = re.compile(
    r"sidebar|side-bar|menu|nav|footer|header|comment|advert|\bads?\b|share|social|related|breadcrumb",
    re.IGNORECASE,
)

# (url, max_bytes, timeout_s) -> body. Must raise if the body exceeds max_bytes.
Fetcher = Callable[[str, int, float], bytes]


class FetchError(Exception):
    pass


def ruby_option(options: Mapping[str, object]) -> str:
    v = options.get("ruby", "hint")
    if v not in ("strip", "hint"):
        raise ValueError(f"options.ruby must be 'strip' or 'hint', got {v!r}")
    return str(v)


def _is_noise(n: Node) -> bool:
    if n.tag in _NOISE_TAGS:
        return True
    if n.attrs.get("role") in ("navigation", "banner", "complementary", "contentinfo"):
        return True
    return bool(_NOISE_ATTR.search(n.attrs.get("class", "") + " " + n.attrs.get("id", "")))


def _noise_ids(root: Node) -> frozenset[int]:
    return frozenset(id(n) for n in root.iter() if _is_noise(n))


def _link_len(n: Node) -> int:
    return sum(len("".join(a.text().split())) for a in find_all(n, "a"))


def _score(n: Node, noise: frozenset[int]) -> int:
    """Text in the paragraphs directly under `n`, minus link text; noise subtrees score 0."""
    total = 0
    for c in n.children:
        if isinstance(c, str):
            total += len("".join(c.split()))
        elif id(c) in noise or c.tag in DROP:
            continue
        elif c.tag in ("p", "blockquote", "pre", "li"):
            total += max(len("".join(c.text().split())) - 2 * _link_len(c), 0)
    return total


def select_content(root: Node) -> tuple[Node, frozenset[int]]:
    """The article node and the ids of noise nodes to skip inside it."""
    noise = _noise_ids(root)
    for tag in ("article", "main"):
        found = [n for n in find_all(root, tag) if id(n) not in noise or tag == "article"]
        if found:
            best = max(found, key=lambda n: len("".join(n.text().split())))
            return best, noise - {id(best)}
    cands = [
        n for n in root.iter() if n.tag in ("div", "section", "td", "body") and id(n) not in noise
    ]
    if not cands:
        return root, noise
    best = max(cands, key=lambda n: _score(n, noise))
    return best, noise


def _title(root: Node, content: Node, fallback: str) -> str:
    for scope, tags in ((content, HEADINGS), (root, {"title"})):
        for n in scope.iter():
            if n.tag in tags:
                t = " ".join(n.text().split()) if n.tag != "title" else _title_text(n)
                if t:
                    return t
    return fallback


def _title_text(n: Node) -> str:
    return " ".join("".join(c for c in n.children if isinstance(c, str)).split())


def extract_html(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    ruby = ruby_option(options)
    root = parse_html(decode_japanese(data))
    content, noise = select_content(root)
    sentences: list[ExtractedSentence] = []
    for text, hints in paragraphs(content, ruby, noise):
        sentences.extend(sentences_of(text, hints))
    title = _title(root, content, default_title(filename))
    # The heading is the segment title, not a sentence.
    if (
        sentences
        and sentences[0].text == title
        and any(n.tag in HEADINGS and " ".join(n.text().split()) == title for n in content.iter())
    ):
        sentences = sentences[1:]
    return [ExtractedSegment("whole", title, tuple(sentences))]


class _SafeRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self) -> None:
        self.count = 0

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: object,
        code: int,
        msg: str,
        headers: object,
        newurl: str,
    ) -> urllib.request.Request | None:
        check_url(newurl)
        self.count += 1
        if self.count > MAX_REDIRECTS:
            raise FetchError("too many redirects")
        return super().redirect_request(req, fp, code, msg, headers, newurl)  # type: ignore[arg-type]


def check_url(url: str) -> None:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise FetchError(f"only http/https URLs are allowed: {url!r}")


def default_fetcher(url: str, max_bytes: int, timeout: float) -> bytes:
    opener = urllib.request.build_opener(_SafeRedirect())
    req = urllib.request.Request(url, headers={"User-Agent": "kotobaya-worker"})
    try:
        with opener.open(req, timeout=timeout) as resp:
            body: bytes = resp.read(max_bytes + 1)
    except urllib.error.URLError as e:
        raise FetchError(str(e)) from e
    if len(body) > max_bytes:
        raise FetchError(f"response larger than {max_bytes} bytes")
    return body


def extract_url(
    url: str,
    options: Mapping[str, object] | None = None,
    fetch: Fetcher = default_fetcher,
    max_bytes: int = MAX_BYTES,
    timeout: float = TIMEOUT_S,
) -> list[ExtractedSegment]:
    """Fetch `url` (http/https only, size-capped) and extract its readable text."""
    check_url(url)
    body = fetch(url, max_bytes, timeout)
    if len(body) > max_bytes:
        raise FetchError(f"response larger than {max_bytes} bytes")
    return extract_html(body, urlsplit(url).path or "page", options or {})


__all__ = [
    "FetchError",
    "Fetcher",
    "default_fetcher",
    "extract_html",
    "extract_url",
    "ruby_option",
]
