from __future__ import annotations

import itertools
import json
import logging
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

FIX = Path(__file__).resolve().parents[5] / "fixtures" / "jpdb" / "deck"
BASE = "https://jpdb.io"
DECK = "anime/9999"
LIST = f"{BASE}/{DECK}/vocabulary-list"
ROUTES = {
    f"{BASE}/{DECK}": "deck.html",
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


def page_routes() -> dict[str, str]:
    return {u: fx(f) for u, f in ROUTES.items()}


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
    assert deck.title == "Sample Anime Deck"
    assert deck.raw_rows == 9 and len(deck.words) == 8 and len(deck.skipped) == 1
    assert len(op.urls) == 4
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
    f.get(f"{BASE}/{DECK}")
    now[0] += 30
    f.get(f"{BASE}/{DECK}")
    assert len(op.urls) == 1
    now[0] += 60
    f.get(f"{BASE}/{DECK}")
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
    routes = {
        "https://jpdb.io/api/v1/deck/list-vocabulary": fx("api-response.json"),
        f"{BASE}/deck/42": "<h1>unused</h1>",
    }
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
    routes[f"{BASE}/deck/42/vocabulary-list?sort_by=by-frequency-local"] = fx(
        "vocabulary-list-3.html"
    )
    routes[f"{BASE}/deck/42"] = fx("deck.html")
    f, _ = make(tmp_path, routes)
    with caplog.at_level(logging.WARNING):
        deck = ApiDeckSource(f, SECRET, PagesDeckSource(f)).fetch_deck("42")
    assert [w.vid for w in deck.words] == [1000007, 1000008]
    assert "falling back" in caplog.text and "vocabulary" in caplog.text
    # non-numeric id (cannot use API) also falls back
    f2, _ = make(tmp_path / "b", routes)
    deck2 = ApiDeckSource(f2, SECRET, PagesDeckSource(f2)).fetch_deck(DECK)
    assert len(deck2.words) == 8
