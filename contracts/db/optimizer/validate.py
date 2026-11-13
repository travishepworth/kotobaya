#!/usr/bin/env python3
"""Reference model of the C-16 `fsrs-params-publish` hand-off (contracts/db/FSRS_PUBLISH.md).

usage:
  validate.py                                    self-test: every example + built-in negative cases
  validate.py publish <payload.json> <stored>    check a payload against a preset whose stored
                                                 weights_version is <stored>; print the op the
                                                 runner mints and the job result it reports

Exit status 0 = valid. `plan()` is the runner's decision (FSRS_PUBLISH.md 2.2 steps 1-2 and 6-8)
as a pure function; the worker and the server may shell out to this script in tests or port the
rules. Needs python3 with jsonschema >= 4.18.
"""
from __future__ import annotations

import copy
import json
import math
import pathlib
import sys
from typing import Any

import jsonschema
from referencing import Registry, Resource

HERE = pathlib.Path(__file__).resolve().parent
EXAMPLES = HERE / "examples"
SYNC = HERE.parent.parent / "sync"
SERVER_DEVICE_ID = "00000000-0000-7000-8000-000000000001"


class Invalid(Exception):
    """The child ends `failed` with error '<code>: <message>'."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__("%s: %s" % (code, message))
        self.code = code


def _load(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _retrieve(uri: str) -> Resource:
    # The op schemas reference ../common.schema.json relatively and carry no $id.
    return Resource.from_contents(_load(SYNC / pathlib.PurePosixPath(uri).name))


def _validator(schema: dict[str, Any]) -> Any:
    cls = jsonschema.validators.validator_for(schema)
    cls.check_schema(schema)
    return cls(schema, registry=Registry(retrieve=_retrieve))


PUBLISH_SCHEMA = _validator(_load(HERE / "fsrs-params-publish.v1.schema.json"))
OP_PAYLOAD_SCHEMA = _validator(_load(SYNC / "ops" / "fsrs_params.published.schema.json"))


def _first_error(validator: Any, doc: Any) -> str | None:
    errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
    if not errors:
        return None
    e = errors[0]
    return "/%s: %s" % ("/".join(str(p) for p in e.absolute_path), e.message)


def _finite(doc: Any) -> bool:
    if isinstance(doc, float):
        return math.isfinite(doc)
    if isinstance(doc, dict):
        return all(_finite(v) for v in doc.values())
    if isinstance(doc, list):
        return all(_finite(v) for v in doc)
    return True


def plan(payload: Any, stored_weights_version: int) -> tuple[dict[str, Any], dict[str, Any]]:
    """Returns (op without op_id/hlc/seq, JobResultFsrsOptimize) or raises Invalid(code)."""
    # Step 1: the payload on its own.
    if not isinstance(payload, dict):
        raise Invalid("invalid_payload", "payload is not an object")
    if payload.get("format_version") != 1 or isinstance(payload.get("format_version"), bool):
        raise Invalid("unsupported_format", "format_version %r" % (payload.get("format_version"),))
    if not _finite(payload):
        raise Invalid("invalid_payload", "non-finite number")
    error = _first_error(PUBLISH_SCHEMA, payload)
    if error is not None:
        raise Invalid("invalid_payload", error)
    if payload["holdout_review_count"] >= payload["review_count"]:
        raise Invalid("invalid_payload", "holdout_review_count must be < review_count")
    if not payload["log_loss_after"] < payload["log_loss_before"]:
        raise Invalid("not_improved", "log_loss_after %r is not below log_loss_before %r"
                      % (payload["log_loss_after"], payload["log_loss_before"]))

    # Step 2: the op payload, validated against the sync contract (weight bounds, C-1).
    op_payload = {
        "id": payload["preset_id"],
        "weights_version": payload["base_weights_version"] + 1,
        "fsrs_version": payload["fsrs_version"],
        "weights": list(payload["weights"]),
        "optimized_at": payload["optimized_at"],
        "optimizer_stats": {
            "review_count": payload["review_count"],
            "log_loss": payload["log_loss_after"],
            "rmse_bins": payload["rmse_bins"],
            "log_loss_before": payload["log_loss_before"],
            "holdout_review_count": payload["holdout_review_count"],
        },
    }
    error = _first_error(OP_PAYLOAD_SCHEMA, op_payload)
    if error is not None:
        raise Invalid("weights_out_of_bounds", error)

    # Step 6: against the stored preset (under the op-apply lock).
    if stored_weights_version != payload["base_weights_version"]:
        raise Invalid("stale_base", "preset is at weights_version %d, payload was built on %d"
                      % (stored_weights_version, payload["base_weights_version"]))

    op = {
        "op_type": "fsrs_params.published",
        "op_version": 1,
        "entity_type": "fsrs_params",
        "entity_id": payload["preset_id"],
        "device_id": SERVER_DEVICE_ID,
        "caused_by_op_id": None,
        "payload": op_payload,
    }
    result = {
        "preset_id": payload["preset_id"],
        "published": True,
        "weights_version": op_payload["weights_version"],
        "review_count": payload["review_count"],
        "log_loss": payload["log_loss_after"],
        "rmse_bins": payload["rmse_bins"],
        "log_loss_before": payload["log_loss_before"],
        "holdout_review_count": payload["holdout_review_count"],
        "reason": None,
    }
    return op, result


# ------------------------------------------------------------------ self-test
def _negative_cases(base: dict[str, Any]) -> list[tuple[str, Any, int, str]]:
    def mut(**changes: Any) -> dict[str, Any]:
        doc = copy.deepcopy(base)
        doc.update(changes)
        return doc

    def without(key: str) -> dict[str, Any]:
        doc = copy.deepcopy(base)
        del doc[key]
        return doc

    def weight(index: int, value: Any) -> dict[str, Any]:
        doc = copy.deepcopy(base)
        doc["weights"][index] = value
        return doc

    stored = base["base_weights_version"]
    return [
        ("format_version 2", mut(format_version=2), stored, "unsupported_format"),
        ("format_version missing", without("format_version"), stored, "unsupported_format"),
        ("unknown member", mut(note="x"), stored, "invalid_payload"),
        ("parent_job_id missing", without("parent_job_id"), stored, "invalid_payload"),
        ("uppercase preset id", mut(preset_id=base["preset_id"].upper().replace("0", "A")), stored, "invalid_payload"),
        ("fsrs_version 5", mut(fsrs_version="5"), stored, "invalid_payload"),
        ("20 weights", mut(weights=base["weights"][:20]), stored, "invalid_payload"),
        ("22 weights", mut(weights=base["weights"] + [0.5]), stored, "invalid_payload"),
        ("string weight", weight(3, "8.2"), stored, "invalid_payload"),
        ("NaN weight", weight(3, float("nan")), stored, "invalid_payload"),
        ("negative base", mut(base_weights_version=-1), stored, "invalid_payload"),
        ("base at int4 max", mut(base_weights_version=2147483647), 2147483647, "invalid_payload"),
        ("optimized_at after 9999", mut(optimized_at=253402300800000), stored, "invalid_payload"),
        ("holdout = all reviews", mut(holdout_review_count=base["review_count"]), stored, "invalid_payload"),
        ("empty holdout", mut(holdout_review_count=0), stored, "invalid_payload"),
        ("log_loss_before 0", mut(log_loss_before=0, log_loss_after=0), stored, "invalid_payload"),
        ("negative log_loss_after", mut(log_loss_after=-0.1), stored, "invalid_payload"),
        ("equal log loss", mut(log_loss_after=base["log_loss_before"]), stored, "not_improved"),
        ("worse log loss", mut(log_loss_after=base["log_loss_before"] + 0.01), stored, "not_improved"),
        ("w[4] below 1.0", weight(4, 0.99), stored, "weights_out_of_bounds"),
        ("w[20] zero decay", weight(20, 0.0), stored, "weights_out_of_bounds"),
        ("w[7] above 0.75", weight(7, 0.7501), stored, "weights_out_of_bounds"),
        ("preset moved on", base, stored + 1, "stale_base"),
        ("preset behind", mut(base_weights_version=stored + 1), stored, "stale_base"),
    ]


def self_test() -> int:
    failures: list[str] = []
    checks = 0
    stems = sorted(p.name[: -len(".expected.json")] for p in EXAMPLES.glob("*.expected.json"))
    if not stems:
        failures.append("no examples found")
    for stem in stems:
        payload = _load(EXAMPLES / (stem + ".json"))
        expected = _load(EXAMPLES / (stem + ".expected.json"))
        checks += 1
        try:
            op, result = plan(payload, expected["stored_weights_version"])
        except Invalid as e:
            failures.append("%s: expected valid, got %s" % (stem, e))
            continue
        if op != expected["op"]:
            failures.append("%s: op differs from the expected file" % stem)
        if result != expected["result"]:
            failures.append("%s: result differs from the expected file" % stem)
        # Idempotency model: once applied, the same payload can never mint again.
        checks += 1
        try:
            plan(payload, op["payload"]["weights_version"])
            failures.append("%s: re-run after publish must be stale_base" % stem)
        except Invalid as e:
            if e.code != "stale_base":
                failures.append("%s: re-run after publish gave %s" % (stem, e.code))
        for name, doc, stored, code in _negative_cases(payload):
            checks += 1
            try:
                plan(doc, stored)
                failures.append("%s / %s: expected %s, got valid" % (stem, name, code))
            except Invalid as e:
                if e.code != code:
                    failures.append("%s / %s: expected %s, got %s" % (stem, name, code, e))
    for line in failures:
        print("FAIL " + line)
    print("optimizer/validate.py: %d checks, %d failures" % (checks, len(failures)))
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    if len(argv) == 1:
        return self_test()
    if len(argv) == 4 and argv[1] == "publish":
        try:
            op, result = plan(_load(pathlib.Path(argv[2])), int(argv[3]))
        except Invalid as e:
            print(str(e), file=sys.stderr)
            return 1
        print(json.dumps({"op": op, "result": result}, indent=2, ensure_ascii=False))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
