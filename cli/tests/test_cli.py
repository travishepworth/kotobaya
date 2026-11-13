"""CLI tests: argument parsing, --json output, HTTP error mapping. HTTP is faked."""

from __future__ import annotations

import hashlib
import io
import json
import re
from pathlib import Path
from typing import Any

import pytest
from conftest import FakeHttp, card_json, job_json

from kotobaya_cli.api import uuid7
from kotobaya_cli.cli import build_parser, main

BASE = "/api/v1"


def NO_SLEEP(_seconds: float) -> None:
    return None


def run(argv: list[str], env: dict[str, str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    code = main(argv, env=env, stdout=out, stderr=err, sleep=NO_SLEEP)
    return code, out.getvalue(), err.getvalue()


# ---- argument parsing -------------------------------------------------------


def test_help_lists_all_commands(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as exc:
        build_parser().parse_args(["--help"])
    assert exc.value.code == 0
    text = capsys.readouterr().out
    for word in ("health", "cards", "media", "jobs"):
        assert word in text
    for sub in (["cards"], ["media"], ["jobs"]):
        with pytest.raises(SystemExit):
            build_parser().parse_args([*sub, "--help"])
        sub_text = capsys.readouterr().out
        assert "find" in sub_text or "import" in sub_text or "list" in sub_text


def test_cards_find_parses_query_json_and_limit() -> None:
    args = build_parser().parse_args(["cards", "find", "is:due", "--json", "--limit", "5"])
    assert (args.command, args.cards_command, args.query) == ("cards", "find", "is:due")
    assert args.json is True
    assert args.limit == 5


def test_cards_find_defaults() -> None:
    args = build_parser().parse_args(["cards", "find", ""])
    assert args.query == ""
    assert args.json is False
    assert args.limit is None


@pytest.mark.parametrize("bad", ["0", "501", "many"])
def test_cards_find_rejects_bad_limit(bad: str) -> None:
    with pytest.raises(SystemExit) as exc:
        build_parser().parse_args(["cards", "find", "x", "--limit", bad])
    assert exc.value.code == 2


def test_media_import_parses_options() -> None:
    args = build_parser().parse_args(
        [
            "media",
            "import",
            "ep01.srt",
            "--title",
            "Frieren 1",
            "--kind",
            "anime",
            "--no-deck",
            "--new-per-day",
            "3",
        ]
    )
    assert args.file == Path("ep01.srt")
    assert args.title == "Frieren 1"
    assert args.kind == "anime"
    assert args.no_deck is True
    assert args.new_per_day == 3


def test_media_import_rejects_unknown_kind_and_range() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["media", "import", "a.srt", "--kind", "podcast"])
    with pytest.raises(SystemExit):
        build_parser().parse_args(["media", "import", "a.srt", "--new-per-day", "10000"])


def test_jobs_watch_requires_id_and_positive_interval() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["jobs", "watch"])
    with pytest.raises(SystemExit):
        build_parser().parse_args(["jobs", "watch", "abc", "--interval", "0"])
    args = build_parser().parse_args(["jobs", "watch", "abc"])
    assert args.job_id == "abc"
    assert args.interval == 2.0


def test_jobs_list_json_flag() -> None:
    args = build_parser().parse_args(["jobs", "list", "--json"])
    assert args.json is True


def test_unknown_command_exits_2() -> None:
    with pytest.raises(SystemExit) as exc:
        build_parser().parse_args(["nope"])
    assert exc.value.code == 2


# ---- configuration ------------------------------------------------------------


def test_missing_server_is_exit_1(fake_http: FakeHttp) -> None:
    code, _out, err = run(["health"], {"KOTOBAYA_TOKEN": "kbp_x"})
    assert code == 1
    assert "no server" in err
    assert fake_http.calls == []


def test_missing_token_is_exit_1_without_request(fake_http: FakeHttp) -> None:
    code, _out, err = run(["health"], {"KOTOBAYA_URL": "https://k.test"})
    assert code == 1
    assert "KOTOBAYA_TOKEN is not set" in err
    assert fake_http.calls == []


def test_server_flag_overrides_env(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add(
        "GET",
        f"{BASE}/health",
        200,
        {"status": "ok", "time": "2026-11-13T00:00:00.000Z", "version": "1"},
    )
    code, _out, _err = run(["--server", "https://other.test/", "health"], env)
    assert code == 0
    assert fake_http.last().url == "https://other.test/api/v1/health"


# ---- commands against a fake server --------------------------------------------


def test_health_sends_bearer_token_and_prints_status(
    fake_http: FakeHttp, env: dict[str, str]
) -> None:
    fake_http.add(
        "GET",
        f"{BASE}/health",
        200,
        {"status": "ok", "time": "2026-11-13T00:00:00.000Z", "version": "1.2.3"},
    )
    code, out, _err = run(["health"], env)
    assert code == 0
    assert out.startswith("ok version=1.2.3")
    assert fake_http.last().url == "https://kotobaya.test/api/v1/health"
    # health is a public operation (security: []), so no credential is sent.
    assert "Authorization" not in fake_http.last().headers


def test_authenticated_call_sends_bearer_token(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add("GET", f"{BASE}/jobs", 200, {"items": [], "next_cursor": None})
    code, _out, _err = run(["jobs", "list"], env)
    assert code == 0
    assert fake_http.last().headers.get("Authorization") == "Bearer kbp_secrettoken"
    assert "kbp_secrettoken" not in fake_http.last().url


def test_cards_find_json_prints_raw_page(fake_http: FakeHttp, env: dict[str, str]) -> None:
    card_id = "01900000-0000-7000-8000-000000000001"
    fake_http.add("GET", f"{BASE}/cards", 200, {"items": [card_json(card_id)], "next_cursor": None})
    code, out, _err = run(["cards", "find", "is:due", "--json", "--limit", "5"], env)
    assert code == 0
    payload = json.loads(out)
    assert payload["next_cursor"] is None
    assert payload["items"][0]["id"] == card_id
    assert payload["items"][0]["note"]["fields"]["front"] == "<b>猫</b>"
    url = fake_http.last().url
    assert "query=is%3Adue" in url or "query=is:due" in url
    assert "limit=5" in url


def test_cards_find_table_strips_html_and_handles_empty(
    fake_http: FakeHttp, env: dict[str, str]
) -> None:
    fake_http.add(
        "GET",
        f"{BASE}/cards",
        200,
        {"items": [card_json("01900000-0000-7000-8000-000000000001")], "next_cursor": None},
    )
    code, out, _err = run(["cards", "find", "x"], env)
    assert code == 0
    assert "ID" in out.splitlines()[0]
    assert "猫" in out
    assert "<b>" not in out


def test_cards_find_empty_page_says_no_cards(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add("GET", f"{BASE}/cards", 200, {"items": [], "next_cursor": None})
    code, out, _err = run(["cards", "find", "x"], env)
    assert code == 0
    assert out.strip() == "no cards"


def test_jobs_list_json(fake_http: FakeHttp, env: dict[str, str]) -> None:
    job_id = "01900000-0000-7000-8000-000000000091"
    fake_http.add(
        "GET", f"{BASE}/jobs", 200, {"items": [job_json(job_id, "queued")], "next_cursor": None}
    )
    code, out, _err = run(["jobs", "list", "--json"], env)
    assert code == 0
    assert json.loads(out)["items"][0]["status"] == "queued"


def test_jobs_watch_polls_until_succeeded(fake_http: FakeHttp, env: dict[str, str]) -> None:
    job_id = "01900000-0000-7000-8000-000000000011"
    path = f"{BASE}/jobs/{job_id}"
    fake_http.add("GET", path, 200, job_json(job_id, "queued", 0.0))
    fake_http.add("GET", path, 200, job_json(job_id, "running", 0.5))
    fake_http.add("GET", path, 200, job_json(job_id, "succeeded", 1.0))
    code, out, _err = run(["jobs", "watch", job_id], env)
    assert code == 0
    lines = out.strip().splitlines()
    assert lines[0].startswith("queued")
    assert lines[-1].startswith("succeeded")
    assert len(fake_http.calls) == 3


def test_jobs_watch_failed_job_is_exit_1(fake_http: FakeHttp, env: dict[str, str]) -> None:
    job_id = "01900000-0000-7000-8000-000000000012"
    fake_http.add(
        "GET", f"{BASE}/jobs/{job_id}", 200, job_json(job_id, "failed", 0.3, error="bad srt")
    )
    code, _out, err = run(["jobs", "watch", job_id], env)
    assert code == 1
    assert "bad srt" in err


def test_media_import_uploads_blob_then_starts_job(
    tmp_path: Path, fake_http: FakeHttp, env: dict[str, str]
) -> None:
    srt = tmp_path / "ep01.srt"
    data = "1\n00:00:01,000 --> 00:00:02,000\n猫\n".encode()
    srt.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    job_id = "01900000-0000-7000-8000-000000000021"
    fake_http.add(
        "PUT",
        f"{BASE}/blobs/{digest}",
        201,
        {
            "content_type": "application/x-subrip",
            "created_at": "2026-11-12T09:30:00.000Z",
            "hash": digest,
            "kind": "upload",
            "origin": "upload",
            "size_bytes": len(data),
        },
    )
    fake_http.add("POST", f"{BASE}/media/import", 202, job_json(job_id, "queued"))

    code, out, _err = run(
        ["media", "import", str(srt), "--kind", "anime", "--no-deck", "--new-per-day", "4"], env
    )
    assert code == 0
    assert job_id in out
    assert "kotobaya jobs watch" in out

    put = fake_http.calls[0]
    assert put.method == "PUT"
    assert put.url.split("?")[0].endswith(f"/blobs/{digest}")
    assert put.body == data

    body = fake_http.calls[1].body
    assert body["source"] == "file"
    assert body["files"] == [{"blob_hash": digest, "filename": "ep01.srt"}]
    assert body["title"] == "ep01"  # default: file name without extension
    assert body["kind"] == "anime"
    assert body["deck"]["enabled"] is False
    assert body["deck"]["new_cards_per_day"] == 4
    assert re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", body["import_id"]
    )


def test_media_import_defaults_omit_deck_and_use_other_kind(
    tmp_path: Path, fake_http: FakeHttp, env: dict[str, str]
) -> None:
    f = tmp_path / "notes.txt"
    f.write_bytes(b"hello")
    digest = hashlib.sha256(b"hello").hexdigest()
    fake_http.add(
        "PUT",
        f"{BASE}/blobs/{digest}",
        201,
        {
            "content_type": "application/octet-stream",
            "created_at": "2026-11-12T09:30:00.000Z",
            "hash": digest,
            "kind": "upload",
            "origin": "upload",
            "size_bytes": 5,
        },
    )
    fake_http.add(
        "POST",
        f"{BASE}/media/import",
        202,
        job_json("01900000-0000-7000-8000-000000000031", "queued"),
    )
    code, _out, _err = run(["media", "import", str(f), "--title", "My text"], env)
    assert code == 0
    body = fake_http.calls[1].body
    assert "deck" not in body
    assert body["kind"] == "other"
    assert body["title"] == "My text"


def test_media_import_missing_file_is_exit_1(
    tmp_path: Path, fake_http: FakeHttp, env: dict[str, str]
) -> None:
    code, _out, err = run(["media", "import", str(tmp_path / "missing.srt")], env)
    assert code == 1
    assert "cannot read" in err
    assert fake_http.calls == []


def test_401_is_exit_1_with_token_message(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add(
        "GET",
        f"{BASE}/health",
        401,
        {
            "code": "unauthenticated",
            "title": "Unauthenticated",
            "status": 401,
            "type": "urn:kotobaya:problem:unauthenticated",
            "request_id": "r1",
        },
    )
    code, out, err = run(["health"], env)
    assert code == 1
    assert err.strip() == "error: token missing or revoked"
    assert out == ""
    assert "kbp_secrettoken" not in err


def test_401_on_cards_find_is_exit_1(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add(
        "GET", f"{BASE}/cards", 401, {"code": "unauthenticated", "title": "Unauthenticated"}
    )
    code, _out, err = run(["cards", "find", "is:due"], env)
    assert code == 1
    assert "token missing or revoked" in err


def test_other_http_error_shows_problem_title(fake_http: FakeHttp, env: dict[str, str]) -> None:
    fake_http.add(
        "GET",
        f"{BASE}/cards",
        422,
        {
            "code": "invalid_query",
            "title": "Invalid query",
            "detail": "unknown term at 3",
        },
    )
    code, _out, err = run(["cards", "find", "is:"], env)
    assert code == 1
    assert err.strip() == "error: HTTP 422: Invalid query - unknown term at 3"


def test_unreachable_server_is_exit_1(monkeypatch: pytest.MonkeyPatch, env: dict[str, str]) -> None:
    from kotobaya_client.rest import RESTClientObject

    def refuse(*_a: Any, **_k: Any) -> None:
        raise ConnectionRefusedError(111, "refused")

    monkeypatch.setattr(RESTClientObject, "request", refuse)
    code, _out, err = run(["health"], env)
    assert code == 1
    assert "cannot reach server" in err


# ---- helpers ------------------------------------------------------------------


def test_uuid7_shape_and_order() -> None:
    a = uuid7(now_ms=1_700_000_000_000)
    b = uuid7(now_ms=1_700_000_000_001)
    assert a[14] == "7"
    assert a[19] in "89ab"
    assert a < b
    assert len(a) == 36
