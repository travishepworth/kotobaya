"""Argument parsing, dispatch and error mapping for the `kotobaya` command."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import TextIO

import urllib3
from kotobaya_client.exceptions import ApiException, OpenApiException

from kotobaya_cli import commands
from kotobaya_cli.api import TOKEN_REJECTED, Api, CliError, load_settings, make_api

MEDIA_KINDS = ("anime", "novel", "vn", "manga", "web", "other")


def _ranged(low: int, high: int, what: str) -> Callable[[str], int]:
    def parse(text: str) -> int:
        try:
            value = int(text)
        except ValueError as exc:
            raise argparse.ArgumentTypeError(f"{what} must be an integer") from exc
        if not low <= value <= high:
            raise argparse.ArgumentTypeError(f"{what} must be between {low} and {high}")
        return value

    return parse


def _positive_float(text: str) -> float:
    try:
        value = float(text)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("interval must be a number") from exc
    if value <= 0:
        raise argparse.ArgumentTypeError("interval must be greater than 0")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="kotobaya",
        description=(
            "Kotobaya command-line client. Reads KOTOBAYA_URL (server origin or API base) "
            "and KOTOBAYA_TOKEN (personal access token) from the environment."
        ),
    )
    parser.add_argument("--server", metavar="URL", help="server URL; overrides KOTOBAYA_URL")
    commands_sub = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")

    commands_sub.add_parser("health", help="check that the server is up")

    cards = commands_sub.add_parser("cards", help="work with cards")
    cards_sub = cards.add_subparsers(dest="cards_command", required=True, metavar="ACTION")
    find = cards_sub.add_parser("find", help="search cards with the card search language")
    find.add_argument("query", help="card search string, e.g. 'is:due' (quote it)")
    find.add_argument("--json", action="store_true", help="print the raw API JSON")
    find.add_argument(
        "--limit", type=_ranged(1, 500, "limit"), metavar="N", help="page size, 1-500"
    )

    media = commands_sub.add_parser("media", help="work with media")
    media_sub = media.add_subparsers(dest="media_command", required=True, metavar="ACTION")
    imp = media_sub.add_parser("import", help="upload a file and start a media-import job")
    imp.add_argument("file", type=Path, help="subtitle or text file to import")
    imp.add_argument("--title", help="media title (default: file name without extension)")
    imp.add_argument(
        "--kind", choices=MEDIA_KINDS, default="other", help="media kind (default: other)"
    )
    imp.add_argument("--no-deck", action="store_true", help="the media deck supplies no new cards")
    imp.add_argument(
        "--new-per-day",
        type=_ranged(0, 9999, "new-per-day"),
        metavar="N",
        help="per-deck new-card limit per day, 0-9999",
    )

    jobs = commands_sub.add_parser("jobs", help="inspect background jobs")
    jobs_sub = jobs.add_subparsers(dest="jobs_command", required=True, metavar="ACTION")
    jobs_list = jobs_sub.add_parser("list", help="list recent jobs")
    jobs_list.add_argument("--json", action="store_true", help="print the raw API JSON")
    watch = jobs_sub.add_parser("watch", help="poll a job until it finishes")
    watch.add_argument("job_id", help="job id")
    watch.add_argument(
        "--interval", type=_positive_float, default=2.0, metavar="SECONDS", help="poll interval"
    )
    return parser


def dispatch(
    args: argparse.Namespace,
    api: Api,
    out: TextIO,
    err: TextIO,
    sleep: Callable[[float], None],
) -> int:
    if args.command == "health":
        return commands.health(api, out)
    if args.command == "cards":
        return commands.cards_find(api, args.query, args.limit, args.json, out)
    if args.command == "media":
        return commands.media_import(
            api, args.file, args.title, args.kind, args.no_deck, args.new_per_day, out
        )
    if args.command == "jobs" and args.jobs_command == "list":
        return commands.jobs_list(api, args.json, out)
    if args.command == "jobs":
        return commands.jobs_watch(api, args.job_id, args.interval, out, err, sleep)
    raise CliError(f"unknown command {args.command!r}")


def _api_error_text(exc: ApiException) -> str:
    if exc.status == 401:
        return TOKEN_REJECTED
    parts: list[str] = []
    try:
        problem = json.loads(exc.body or "")
    except (TypeError, ValueError):
        problem = None
    if isinstance(problem, dict):
        for key in ("title", "detail"):
            value = problem.get(key)
            if isinstance(value, str) and value:
                parts.append(value)
    reason = " - ".join(parts) or (exc.reason or "request failed")
    return f"HTTP {exc.status}: {reason}"


def main(
    argv: Sequence[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> int:
    """Run the CLI and return the process exit code (0 ok, 1 failure; argparse exits 2)."""
    args = build_parser().parse_args(argv)
    environ = os.environ if env is None else env
    out = sys.stdout if stdout is None else stdout
    err = sys.stderr if stderr is None else stderr
    try:
        settings = load_settings(args.server, environ)
        api = make_api(settings)
        return dispatch(args, api, out, err, sleep)
    except CliError as exc:
        print(f"error: {exc}", file=err)
    except ApiException as exc:
        print(f"error: {_api_error_text(exc)}", file=err)
    except OpenApiException as exc:
        print(f"error: {exc}", file=err)
    except (urllib3.exceptions.HTTPError, OSError) as exc:
        print(f"error: cannot reach server ({type(exc).__name__})", file=err)
    except KeyboardInterrupt:
        return 130
    return 1
