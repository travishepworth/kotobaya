"""Command implementations. Each takes the API handle and writes to the given stream."""

from __future__ import annotations

import hashlib
import re
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import TextIO

from kotobaya_client.models.card import Card
from kotobaya_client.models.job import Job
from kotobaya_client.models.job_status import JobStatus
from kotobaya_client.models.media_deck_options import MediaDeckOptions
from kotobaya_client.models.media_import_file import MediaImportFile
from kotobaya_client.models.media_import_request import MediaImportRequest
from kotobaya_client.models.media_kind import MediaKind
from pydantic import ValidationError

from kotobaya_cli.api import Api, CliError, uuid7

TERMINAL_STATES = frozenset({JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED})
_TAG = re.compile(r"<[^>]*>")


def table(headers: Sequence[str], rows: Sequence[Sequence[str]]) -> str:
    """Left-aligned, space-separated columns; cell newlines flattened."""
    clean = [[cell.replace("\n", " ").replace("\r", " ") for cell in row] for row in rows]
    widths = [len(h) for h in headers]
    for row in clean:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(cell))

    def fmt(cells: Sequence[str]) -> str:
        return "  ".join(c.ljust(w) for c, w in zip(cells, widths, strict=True)).rstrip()

    return "\n".join([fmt(headers), *(fmt(r) for r in clean)])


def clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def health(api: Api, out: TextIO) -> int:
    h = api.meta.get_health()
    print(f"{h.status} version={h.version} time={h.time.isoformat()}", file=out)
    return 0


def _card_note_text(card: Card) -> str:
    if card.note is None or not card.note.fields:
        return ""
    first = next(iter(card.note.fields.values()))
    return clip(_TAG.sub("", first).strip(), 40)


def cards_find(api: Api, query: str, limit: int | None, as_json: bool, out: TextIO) -> int:
    page = api.cards.list_cards(query=query, limit=limit)
    if as_json:
        print(page.to_json(), file=out)
        return 0
    rows = [
        [
            card.id,
            card.state.value,
            card.due_at.isoformat() if card.due_at is not None else "-",
            str(card.lapses),
            _card_note_text(card),
        ]
        for card in page.items
    ]
    if rows:
        print(table(["ID", "STATE", "DUE", "LAPSES", "NOTE"], rows), file=out)
    else:
        print("no cards", file=out)
    return 0


def jobs_list(api: Api, as_json: bool, out: TextIO) -> int:
    page = api.jobs.list_jobs()
    if as_json:
        print(page.to_json(), file=out)
        return 0
    rows = [
        [
            job.id,
            job.kind.value,
            job.status.value,
            f"{float(job.progress):.0%}",
            job.progress_message or "",
        ]
        for job in page.items
    ]
    if rows:
        print(table(["ID", "KIND", "STATUS", "PROGRESS", "MESSAGE"], rows), file=out)
    else:
        print("no jobs", file=out)
    return 0


def jobs_watch(
    api: Api,
    job_id: str,
    interval: float,
    out: TextIO,
    err: TextIO,
    sleep: Callable[[float], None],
) -> int:
    """Poll the job until it is terminal. Exit 0 only when it succeeded."""
    last: str | None = None
    while True:
        job: Job = api.jobs.get_job(job_id)
        line = f"{job.status.value} {float(job.progress):.0%} {job.progress_message or ''}"
        line = line.rstrip()
        if line != last:
            print(line, file=out)
            last = line
        if job.status in TERMINAL_STATES:
            if job.status == JobStatus.SUCCEEDED:
                return 0
            print(f"error: job {job.status.value}: {job.error or 'no error text'}", file=err)
            return 1
        sleep(interval)


def media_import(
    api: Api,
    path: Path,
    title: str | None,
    kind: str,
    no_deck: bool,
    new_per_day: int | None,
    out: TextIO,
) -> int:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise CliError(f"cannot read {path}: {exc.strerror or exc}") from exc
    if title is not None and not title.strip():
        raise CliError("--title must not be empty")

    digest = hashlib.sha256(data).hexdigest()
    api.blobs.put_blob(kind="upload", hash=digest, body=data)

    deck: MediaDeckOptions | None = None
    if no_deck or new_per_day is not None:
        deck = MediaDeckOptions(
            enabled=not no_deck,
            new_cards_per_day=new_per_day,
        )
    try:
        request = MediaImportRequest(
            source="file",
            files=[MediaImportFile(blob_hash=digest, filename=path.name)],
            title=title if title is not None else path.stem,
            kind=MediaKind(kind),
            deck=deck,
            import_id=uuid7(),
        )
    except ValidationError as exc:
        first = exc.errors()[0]
        loc = ".".join(str(p) for p in first["loc"])
        raise CliError(f"invalid import request: {loc}: {first['msg']}") from exc

    job = api.media.import_media(request)
    print(
        f"queued job {job.id} ({job.status.value}); follow with: kotobaya jobs watch {job.id}",
        file=out,
    )
    return 0
