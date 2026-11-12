"""T207c: worker half of the jpdb history hand-off (``apply.py``): step 2, step 4 and their races."""

from __future__ import annotations

import hashlib
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

import pytest

from kotobaya_worker.dict.package import s3
from kotobaya_worker.importers.jpdb_history import ImportOptions, ImportPlan, plan_import
from kotobaya_worker.importers.jpdb_history.apply import (
    INTERNAL_KIND,
    ChildRow,
    adopt_or_create_child,
    make_fetch_blob,
    uuid7,
    wait_for_child,
)
from kotobaya_worker.importers.jpdb_history.ref import InMemoryRefLookup
from kotobaya_worker.runner import API_JOB_KINDS, JobCancelled

PARENT = uuid.uuid4()
RESULT: dict[str, Any] = {"reviews_imported": 3, "reviews_id_collisions": 0}


def empty_plan() -> ImportPlan:
    return plan_import({}, InMemoryRefLookup(), ImportOptions())


@dataclass
class FakeStore:
    succeeded: Mapping[str, Any] | None = None
    active: uuid.UUID | None = None
    rows: dict[uuid.UUID, list[ChildRow]] = field(
        default_factory=dict
    )  # one read pops; last repeats
    inserted: list[uuid.UUID] = field(default_factory=list)
    after_insert: Callable[[FakeStore], None] | None = (
        None  # simulate the other side winning a race
    )

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        return self.succeeded

    def insert(self, child: uuid.UUID, parent: uuid.UUID, plan: dict[str, Any]) -> bool:
        assert plan["version"] == 1
        self.inserted.append(child)
        if self.after_insert:
            self.after_insert(self)
        return self.active is None

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        return self.active

    def get(self, child: uuid.UUID) -> ChildRow | None:
        seq = self.rows.get(child)
        if not seq:
            return None
        return seq.pop(0) if len(seq) > 1 else seq[0]


class FakeCtx:
    """The parent; ``check_cancelled`` raises after ``cancelled_after`` calls."""

    def __init__(self, cancelled_after: int | None = None) -> None:
        self.payload: dict[str, Any] = {}
        self.job_id = PARENT
        self.calls = 0
        self.cancelled_after = cancelled_after

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        self.calls += 1
        if self.cancelled_after is not None and self.calls > self.cancelled_after:
            raise JobCancelled(str(PARENT))


def wait(
    store: FakeStore, ctx: FakeCtx, child: uuid.UUID, queued_timeout: float = 600
) -> Mapping[str, Any]:
    ticks = iter(range(10_000))  # one fake second per clock read
    return wait_for_child(
        store, ctx, child, poll_interval=0, queued_timeout=queued_timeout,
        sleep=lambda _s: None, clock=lambda: float(next(ticks)),
    )  # fmt: skip


# ------------------------------------------------------------------ step 2


def test_a_succeeded_child_of_an_earlier_attempt_is_adopted_without_a_new_child() -> None:
    store = FakeStore(succeeded=RESULT)
    assert adopt_or_create_child(store, PARENT, empty_plan()) == RESULT
    assert store.inserted == []


def test_the_row_holding_the_dedupe_key_is_waited_for() -> None:
    held = uuid.uuid4()
    store = FakeStore(after_insert=lambda s: setattr(s, "active", held))
    assert adopt_or_create_child(store, PARENT, empty_plan()) == held


def test_race_holder_finished_between_insert_and_lookup_adopts_its_result() -> None:
    store = FakeStore(after_insert=lambda s: setattr(s, "succeeded", RESULT))
    assert adopt_or_create_child(store, PARENT, empty_plan()) == RESULT
    assert len(store.inserted) == 1


def test_race_with_nothing_ever_found_fails_instead_of_looping_forever() -> None:
    store = FakeStore()
    with pytest.raises(RuntimeError, match="no queued or running apply job"):
        adopt_or_create_child(store, PARENT, empty_plan())
    assert len(store.inserted) > 1


# ------------------------------------------------------------------ step 4


def test_waits_through_queued_and_running_and_returns_the_result() -> None:
    c = uuid.uuid4()
    rows = [ChildRow("queued", None, None), ChildRow("running", None, None)]
    store = FakeStore(rows={c: [*rows, ChildRow("succeeded", RESULT, None)]})
    assert wait(store, FakeCtx(), c) == RESULT


def test_a_failed_child_fails_the_attempt_with_its_error() -> None:
    c = uuid.uuid4()
    store = FakeStore(rows={c: [ChildRow("failed", None, "plan has bad sums")]})
    with pytest.raises(RuntimeError, match="plan has bad sums"):
        wait(store, FakeCtx(), c)


def test_child_cancelled_while_the_parent_is_cancelled_ends_the_parent_cancelled() -> None:
    c = uuid.uuid4()
    store = FakeStore(rows={c: [ChildRow("cancelled", None, None)]})
    with pytest.raises(JobCancelled):
        wait(store, FakeCtx(cancelled_after=1), c)  # 1st check passes, the re-read fails


def test_child_cancelled_but_the_parent_is_still_running_fails_the_attempt() -> None:
    c = uuid.uuid4()
    store = FakeStore(rows={c: [ChildRow("cancelled", None, None)]})
    with pytest.raises(RuntimeError, match="still running"):
        wait(store, FakeCtx(), c)


def test_parent_cancellation_is_honoured_while_waiting() -> None:
    c = uuid.uuid4()
    store = FakeStore(rows={c: [ChildRow("running", None, None)]})
    with pytest.raises(JobCancelled):
        wait(store, FakeCtx(cancelled_after=3), c)


def test_a_child_that_stays_queued_too_long_fails_with_a_clear_error() -> None:
    c = uuid.uuid4()
    store = FakeStore(rows={c: [ChildRow("queued", None, None)]})
    with pytest.raises(TimeoutError, match="import runner"):
        wait(store, FakeCtx(), c, queued_timeout=5)


def test_a_running_child_resets_the_queued_clock() -> None:
    c = uuid.uuid4()
    queued, running = ChildRow("queued", None, None), ChildRow("running", None, None)
    rows = [queued] * 3 + [running] + [queued] * 3 + [ChildRow("succeeded", RESULT, None)]
    store = FakeStore(rows={c: rows})
    assert wait(store, FakeCtx(), c, queued_timeout=4) == RESULT


# ------------------------------------------------------------------ misc


def test_uuid7_is_version_7_and_time_ordered() -> None:
    a, b = uuid7(), uuid7()
    assert a.version == 7 and b.version == 7 and a.variant == uuid.RFC_4122
    assert a.int >> 80 <= b.int >> 80


def test_internal_kind_is_not_an_api_kind() -> None:
    assert INTERNAL_KIND not in API_JOB_KINDS


def test_fetch_blob_verifies_the_hash(monkeypatch: pytest.MonkeyPatch) -> None:
    data = b'{"a": 1}'
    good = hashlib.sha256(data).hexdigest()
    stored = {good: data, "1" * 64: b"x"}
    monkeypatch.setattr(s3.S3Client, "get_blob", lambda self, h: stored.get(h))
    fetch = make_fetch_blob(s3.S3Config("http://localhost:1", "b"))
    assert fetch(good) == data
    with pytest.raises(ValueError, match="hash mismatch"):
        fetch("1" * 64)
    with pytest.raises(FileNotFoundError):
        fetch("2" * 64)
