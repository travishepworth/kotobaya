from __future__ import annotations

import itertools
import json
import logging
import urllib.error
from pathlib import Path
from types import TracebackType
from typing import Any, Self

import pytest

from kotobaya_worker.importers.jpdb_deck.client import (
    ApiDeckSource,
    CachedFetcher,
    PagesDeckSource,
    map_api_response,
    parse_vocab_list_html,
)
from kotobaya_worker.importers.jpdb_deck.client.api import ApiSchemaMismatch
from kotobaya_worker.importers.jpdb_deck.client.pages import normalize_deck_id

FIX = Path(__file__).resolve().parents[5] / "fixtures" / "jpdb" / "deck"
BASE = "https://jpdb.io"
DECK = "anime/9999"
LIST = f"{BASE}/{DECK}/test-deck/vocabulary-list"  # real list URLs carry the title slug
ROUTES = {
    f"{LIST}?sort_by=by-frequency-local": "vocabulary-list-1.html",
    f"{LIST}?sort_by=by-frequency-local&offset=50": "vocabulary-list-2.html",
    f"{LIST}?sort_by=by-frequency-local&offset=100": "vocabulary-list-3.html",
}
SECRET = "sekrit-api-key-123"


def fx(name: str) -> str:
    return (FIX / name).read_text(encoding="utf-8")


class FakeResp:
    def __init__(self, body: str) -> None:
        self._b = body.encode()

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self, et: type[BaseException] | None, e: BaseException | None, tb: TracebackType | None
    ) -> None:
        return None

    def read(self) -> bytes:
        return self._b


class FakeOpener:
    def __init__(self, routes: dict[str, str]) -> None:
        self.routes = routes
        self.urls: list[str] = []

    def __call__(self, req: Any, timeout: float = 0) -> FakeResp:
        self.urls.append(req.full_url)
        if req.full_url not in self.routes:
            raise urllib.error.HTTPError(req.full_url, 404, "Not Found", None, None)  # type: ignore[arg-type]
        return FakeResp(self.routes[req.full_url])


class FakeClock:
    def __init__(self) -> None:
        self.t = 100.0
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self.t

    def sleep(self, s: float) -> None:
        self.sleeps.append(s)
        self.t += s


def overview(path: str, slug: str) -> str:
    """The real jpdb overview page fixture, retargeted at another deck path/slug."""
    return fx("deck-overview.html").replace("visual-novel/3913/fatal-twelve", f"{path}/{slug}")


def page_routes() -> dict[str, str]:
    routes = {u: fx(f) for u, f in ROUTES.items()}
    # slugless /vocabulary-list answers 200 with the 0-row overview page (live jpdb behaviour)
    routes[f"{BASE}/{DECK}/vocabulary-list"] = overview(DECK, "test-deck")
    return routes


def make(
    tmp_path: Path, routes: dict[str, str], clock: FakeClock | None = None, **kw: Any
) -> tuple[CachedFetcher, FakeOpener]:
    op = FakeOpener(routes)
    c = clock or FakeClock()
    return CachedFetcher(tmp_path, opener=op, clock=c.now, sleeper=c.sleep, **kw), op


def test_pages_parser_on_fixtures() -> None:
    w1, s1, raw1 = parse_vocab_list_html(fx("vocabulary-list-1.html"))
    assert raw1 == 4 and not s1
    assert [
        (w.vid, w.spelling, w.reading, w.occurrence_count, w.global_rank_bucket) for w in w1
    ] == [
        (1000001, "番組", "ばんぐみ", 11, 5300),
        (1000002, "この", "この", 197, None),
        (1000003, "食べる", "たべる", 42, 120),
        (1000004, "ラーメン", "ラーメン", 3, 8800),
    ]
    w2, s2, raw2 = parse_vocab_list_html(fx("vocabulary-list-2.html"))
    assert raw2 == 3 and len(w2) + len(s2) == raw2
    assert [w.vid for w in w2] == [1000005, 1000006]
    assert len(s2) == 1 and "broken" in s2[0].raw
    assert w2[0].reading == "がっこう"
    w3, s3, raw3 = parse_vocab_list_html(fx("vocabulary-list-3.html"))
    assert raw3 == 2 and not s3 and w3[1].global_rank_bucket is None


def test_paging_follows_next_links_and_cache_hit_makes_no_network_call(tmp_path: Path) -> None:
    f, op = make(tmp_path, page_routes())
    deck = PagesDeckSource(f).fetch_deck(DECK)
    assert deck.title == DECK  # fixture list page has no title: falls back to the id
    assert deck.raw_rows == 9 and len(deck.words) == 8 and len(deck.skipped) == 1
    assert len(op.urls) == 4  # overview + 3 list pages
    # second run, fresh fetcher, same cache dir: zero network calls
    f2, op2 = make(tmp_path, {})
    deck2 = PagesDeckSource(f2).fetch_deck(DECK)
    assert op2.urls == [] and deck2 == deck
    # refresh=True refetches
    f3, op3 = make(tmp_path, page_routes(), refresh=True)
    PagesDeckSource(f3).fetch_deck(DECK)
    assert len(op3.urls) == 4


def test_max_age_expires_cache(tmp_path: Path) -> None:
    now = [1000.0]
    op = FakeOpener(page_routes())
    f = CachedFetcher(
        tmp_path, opener=op, max_age=60, min_interval=0, wall=lambda: now[0], sleeper=lambda s: None
    )
    f.get(f"{LIST}?sort_by=by-frequency-local")
    now[0] += 30
    f.get(f"{LIST}?sort_by=by-frequency-local")
    assert len(op.urls) == 1
    now[0] += 60
    f.get(f"{LIST}?sort_by=by-frequency-local")
    assert len(op.urls) == 2


def test_rate_limiter_spaces_calls(tmp_path: Path) -> None:
    clock = FakeClock()
    f, op = make(tmp_path, page_routes(), clock)
    times: list[float] = []
    orig = op.__call__

    def stamped(req: Any, timeout: float = 0) -> FakeResp:
        times.append(clock.now())
        return orig(req, timeout=timeout)

    f._opener = stamped
    PagesDeckSource(f).fetch_deck(DECK)
    assert len(times) == 4
    assert all(b - a >= 2.0 for a, b in itertools.pairwise(times))


def test_api_key_never_in_cache(tmp_path: Path) -> None:
    routes = {"https://jpdb.io/api/v1/deck/list-vocabulary": fx("api-response.json")}
    f, _op = make(tmp_path, routes)
    src = ApiDeckSource(f, SECRET, PagesDeckSource(f))
    src.fetch_deck("42")
    files = list(tmp_path.rglob("*"))
    assert [p for p in files if p.is_file()]
    for p in files:
        assert SECRET not in p.name
        if p.is_file():
            assert SECRET not in p.read_text(encoding="utf-8")


def test_api_source_maps_mocked_response(tmp_path: Path) -> None:
    routes = {"https://jpdb.io/api/v1/deck/list-vocabulary": fx("api-response.json")}
    f, op = make(tmp_path, routes)
    deck = ApiDeckSource(f, SECRET, PagesDeckSource(f)).fetch_deck("42")
    assert deck.title == "Sample Anime Deck (API)"
    assert [
        (w.vid, w.spelling, w.reading, w.occurrence_count, w.global_rank_bucket) for w in deck.words
    ] == [
        (1000001, "番組", "ばんぐみ", 11, 5300),
        (1000002, "この", "この", 197, None),
        (1000003, "食べる", "たべる", 42, 120),
    ]
    assert deck.raw_rows == 3 and not deck.skipped
    assert len(op.urls) == 1


def test_api_bad_row_is_skipped_not_dropped() -> None:
    d = map_api_response({"vocabulary": [[1, "a", "a", None], ["x"]], "occurences": [1, 2]})
    assert len(d.words) == 1 and len(d.skipped) == 1 and d.raw_rows == 2
    with pytest.raises(ApiSchemaMismatch):
        map_api_response({"nope": 1})


def test_api_schema_mismatch_falls_back_to_pages(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    routes = page_routes()
    routes["https://jpdb.io/api/v1/deck/list-vocabulary"] = json.dumps({"unexpected": []})
    routes[f"{BASE}/anime/42/vocabulary-list"] = overview("anime/42", "forty-two")
    routes[f"{BASE}/anime/42/forty-two/vocabulary-list?sort_by=by-frequency-local"] = fx(
        "vocabulary-list-3.html"
    )
    f, _ = make(tmp_path, routes)
    with caplog.at_level(logging.WARNING):
        deck = ApiDeckSource(f, SECRET, PagesDeckSource(f)).fetch_deck("anime/42")
    assert [w.vid for w in deck.words] == [1000007, 1000008]
    assert "falling back" in caplog.text
    # non-numeric id (cannot use API) also falls back
    f2, _ = make(tmp_path / "b", routes)
    deck2 = ApiDeckSource(f2, SECRET, PagesDeckSource(f2)).fetch_deck(DECK)
    assert len(deck2.words) == 8


def test_title_from_list_page_and_no_bare_deck_request(tmp_path: Path) -> None:
    page = "<html><head><title>Steins;Gate - jpdb</title></head><body><h1>Steins;Gate</h1></body></html>"
    list_url = f"{BASE}/visual-novel/3913/steins-gate/vocabulary-list?sort_by=by-frequency-local"
    f, op = make(tmp_path, {list_url: page + fx("vocabulary-list-3.html")})
    deck = PagesDeckSource(f).fetch_deck(
        "https://jpdb.io/visual-novel/3913/steins-gate/vocabulary-list"
    )
    assert deck.title == "Steins;Gate"
    assert op.urls == [list_url]  # slugged input: list fetched directly, no bare/overview request


def test_title_parse_failure_falls_back_to_id(tmp_path: Path) -> None:
    list_url = f"{BASE}/visual-novel/3913/x/vocabulary-list?sort_by=by-frequency-local"
    f, _ = make(tmp_path, {list_url: "<html><title></title>" + fx("vocabulary-list-3.html")})
    assert PagesDeckSource(f).fetch_deck("visual-novel/3913/x").title == "visual-novel/3913/x"
    f2, _ = make(tmp_path / "b", {})  # list page 404s -> clear error naming the URL
    with pytest.raises(RuntimeError, match="vocabulary-list"):
        PagesDeckSource(f2).fetch_deck("visual-novel/3913/x")
    assert PagesDeckSource(f2).fetch_title("visual-novel/3913/x") is None


@pytest.mark.parametrize(
    ("raw", "want"),
    [
        ("https://jpdb.io/visual-novel/3913/vocabulary-list", "visual-novel/3913"),
        ("https://jpdb.io/visual-novel/3913", "visual-novel/3913"),
        ("https://jpdb.io/anime/5370/vocabulary-list?sort_by=x#a", "anime/5370"),
        ("jpdb.io/novel/12/", None),
        ("https://jpdb.io/novel/12/vocabulary-list", "novel/12"),
        ("https://jpdb.io/web-novel/7/vocabulary-list", "web-novel/7"),
        ("anime/5370", "anime/5370"),
        ("/live-action/9/vocabulary-list", "live-action/9"),
        ("28", None),
        ("", None),
        ("https://jpdb.io/vocabulary/1234/x", None),
    ],
)
def test_normalize_deck_id(raw: str, want: str | None) -> None:
    if want is None:
        with pytest.raises(ValueError):
            normalize_deck_id(raw)
    else:
        assert normalize_deck_id(raw) == want


def test_slugless_url_resolves_slug_via_overview_and_maps_every_row(tmp_path: Path) -> None:
    slugless = f"{BASE}/visual-novel/3913/vocabulary-list"

    def retarget(page: str) -> str:
        return page.replace("anime/9999/test-deck", "visual-novel/3913/fatal-twelve")

    list_url = f"{BASE}/visual-novel/3913/fatal-twelve/vocabulary-list?sort_by=by-frequency-local"
    routes = {
        slugless: fx("deck-overview.html"),
        list_url: retarget(fx("vocabulary-list-1.html")),
        f"{list_url}&offset=50": retarget(fx("vocabulary-list-2.html")),
        f"{list_url}&offset=100": fx("vocabulary-list-3.html"),
    }
    f, op = make(tmp_path, routes)
    deck = PagesDeckSource(f).fetch_deck("https://jpdb.io/visual-novel/3913/vocabulary-list")
    assert op.urls[0] == slugless and op.urls[1] == list_url
    # every fixture row is fetched and mapped or reported; none silently dropped
    assert deck.raw_rows == 9
    assert len(deck.words) + len(deck.skipped) == deck.raw_rows == 9
    assert len(deck.words) == 8 and len(deck.skipped) == 1


def test_zero_row_first_page_raises(tmp_path: Path) -> None:
    list_url = f"{BASE}/anime/5/s/vocabulary-list?sort_by=by-frequency-local"
    f, _ = make(tmp_path, {list_url: fx("deck-overview.html")})
    with pytest.raises(RuntimeError, match="0 vocabulary rows"):
        PagesDeckSource(f).fetch_deck("anime/5/s")
    # overview without a list link: also a clear error
    f2, _ = make(tmp_path / "b", {f"{BASE}/anime/5/vocabulary-list": "<html></html>"})
    with pytest.raises(RuntimeError, match="no vocabulary-list link"):
        PagesDeckSource(f2).fetch_deck("anime/5")


def test_zero_row_page_is_never_cached_and_cached_one_is_ignored(tmp_path: Path) -> None:
    list_url = f"{BASE}/anime/5/s/vocabulary-list?sort_by=by-frequency-local"
    # 1) a 0-row response is not written to the cache
    f, _ = make(tmp_path, {list_url: fx("deck-overview.html")})
    with pytest.raises(RuntimeError):
        PagesDeckSource(f).fetch_deck("anime/5/s")
    assert list(tmp_path.glob("*.json")) == []
    # 2) a poisoned cache entry (0 rows) is a miss: the real page is refetched
    key = CachedFetcher.cache_key(list_url, None)
    (tmp_path / f"{key}.json").write_text(
        json.dumps({"url": list_url, "fetched_at": 1.0, "body": fx("deck-overview.html")})
    )
    f2, op2 = make(tmp_path, {list_url: fx("vocabulary-list-3.html")})
    deck = PagesDeckSource(f2).fetch_deck("anime/5/s")
    assert op2.urls == [list_url] and len(deck.words) == 2


def test_overview_without_list_link_is_never_cached(tmp_path: Path) -> None:
    """BUG-11 minor: a challenge page at the overview URL must not poison the cache."""
    overview_url = f"{BASE}/{DECK}/vocabulary-list"
    challenge = "<html><body>Checking your browser...</body></html>"
    f, _ = make(tmp_path, {overview_url: challenge})
    with pytest.raises(RuntimeError, match="no vocabulary-list link"):
        PagesDeckSource(f).fetch_deck(DECK)
    assert list(tmp_path.glob("*.json")) == []
    # a remap-style fetcher (cache at any age) now reaches the network and gets the real page
    f2, op2 = make(tmp_path, page_routes(), max_age=None)
    deck = PagesDeckSource(f2).fetch_deck(DECK)
    assert op2.urls[0] == overview_url and len(deck.words) == 8


@pytest.mark.parametrize(
    ("raw", "want"),
    [
        ("https://jpdb.io/anime/5370/stats", None),
        ("https://jpdb.io/anime/5370/vocabulary-list", None),
        ("https://jpdb.io/anime/5370/kanji-list", None),
        ("https://jpdb.io/anime/5370/some-title/vocabulary-list", "some-title"),
        ("https://jpdb.io/anime/5370/some-title/stats", "some-title"),
        ("https://jpdb.io/anime/5370/some-title", "some-title"),
        ("anime/5370", None),
    ],
)
def test_deck_slug_accepts_only_title_segments(raw: str, want: str | None) -> None:
    from kotobaya_worker.importers.jpdb_deck.client.pages import deck_slug

    assert deck_slug(raw) == want
