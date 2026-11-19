#!/usr/bin/env python3
"""Checks contracts/api/shell (SHELL-C1). Run through ../check.sh.

usage: check_shell.py <bundled openapi.json> <bundled shell.json>

Named checks (each prints one line; the run fails if any fails):
  bridge_schema_valid          bridge.schema.json and parity.schema.json are valid JSON Schema 2020-12
  bridge_examples_valid        every examples/bridge/*.json validates (root schema, and the direction its
                               file-name prefix names: web. / native.; any. = root only)
  bridge_examples_invalid      every examples/bridge-invalid/*.json is REJECTED by the same schema
  ops_map_complete             ops.json has every operationId of openapi.yaml exactly once, invents none,
                               and uses only local | local_or_proxy | proxy | hidden
  ops_map_scopes               an operation a device token cannot call (needs import/admin only, or is
                               cookie-only) is `hidden`; `local` / `local_or_proxy` ones need read/write/review or none
  shell_operations             shell.yaml has exactly the 18 operationIds (ADR-036 + display prefs, ADR-037), each `security: []`,
                               each JSON body with an example, Problem referenced from ../openapi.yaml (not copied)
  shell_schema_docs            every schema property in shell.yaml has a description, and an example unless
                               it is a $ref / object
  shell_md_cross_check         SHELL.md names every operationId of ops.json and shell.yaml, and its cross-check
                               table has a row for every learning-data table with an existing op and operation
  parity_schema                every parity/*.json validates against parity.schema.json; id = file name
  parity_seed_ops              every seed / apply op validates against contracts/sync/op.schema.json (envelope
                               and payload), is client-emittable, pushed form, minted by seed.device_id with a
                               consistent, strictly increasing hlc and a derived entity_id; clocks respect clock_ms
  parity_seed_accepted         the reference server of contracts/sync/tools/model.py, with its clock at clock_ms
                               (at_ms for apply steps), accepts every seed / apply op and emits no op of its
                               own (no merge), so SHELL-P1's push cannot be rejected and the shell needs no
                               server verdict to reach the same state
  parity_steps                 at_ms order; each request resolves to an operation of openapi.yaml whose class is
                               local / local_or_proxy; `operations`, `settings` and `dict_entries` are what the
                               scenario really uses (dict_missing seqs are absent from the fixture dictionary);
                               expectations and invariants are well formed
  parity_mandatory_covered     every mandatory coverage tag of SHELL.md 8.4 is covered by a scenario (incl. the five
                               queue.loop_* tags of ADR-037); a scenario covering a queue.loop_* tag expects
                               /loop/phase (or a whole /loop with phase) in at least one step; a retired tag
                               is used by none
  selftest_*                   negative cases: the checks above really fail on a removed operationId, an
                               invented one, a bad class, a broken seed op, a seed the server would reject
                               and a missing mandatory scenario
"""
from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent
SYNC = ROOT / "contracts" / "sync"
sys.path.insert(0, str(SYNC / "tools"))
from model import Server as ModelServer  # noqa: E402  (the sync contract's reference model)

CLASSES = {"local", "local_or_proxy", "proxy", "hidden"}
DEVICE_SCOPES = {"read", "write", "review"}
SHELL_OPS = {
    "getShellInfo", "pairShell", "unpairShell", "getSyncStatus", "syncNow", "getDictInstall", "startDictInstall",
    "getStorage", "updateStorage", "getNotificationState", "requestNotificationPermission", "updateNotificationPrefs",
    "getPushState", "updatePushState", "getAnkiConnectState", "updateAnkiConnectState",
    "getDisplayPrefs", "updateDisplayPrefs",
}
# SHELL.md 8.4. Design section 6 (ADR-036) plus the edge cases of ticket SHELL-C1; ADR-037 (LOOP-C) retired
# queue.session_cap_more_available and added the five queue.loop_* tags.
MANDATORY = [
    "queue.counts_keep_going", "queue.learning_wait", "queue.kinds_counts",
    "queue.loop_never_waits", "queue.loop_done_backlog_first", "queue.loop_goal_reached", "queue.loop_waiting",
    "queue.loop_empty",
    "queue.reviews_unlimited", "cards.reading_retired", "cards.reading_reenabled", "notes.create_no_reading_card",
    "queue.materialize_count", "queue.day_rollover_timezone", "answer.retry_same_id", "answer.suspended_409",
    "answer.deleted_410", "answer.reviewed_at_in_future", "answer.reviewed_at_before_floor",
    "answer.reviewed_at_before_card", "answer.button_rating_mismatch", "undo.twice", "undo.unknown_review",
    "notes.create_duplicate", "notes.create_retry_same_id", "replica.empty", "sync.parent_deleted_by_pull",
    "dict.entry_missing",
    # C-28: ADR-038 (until_stop), ADR-040 (kanji first), ADR-042 (kanji exemptions)
    "queue.until_stop", "queue.kanji_first_deckless", "queue.kanji_disabled_home_deck",
    "queue.kanji_group_not_split", "queue.kanji_backfill", "queue.kanji_partial_unit", "queue.kanji_exemptions_on", "queue.kanji_exemptions_off",
    # C-28b: ADR-044 (backfill half share)
    "queue.kanji_backfill_share",
]
RETIRED = ["queue.session_cap_more_available"]  # ADR-037: no session cap
# contracts/db/SCHEMA.md 4.1 learning-data tables (+ media, parse_override), as contracts/api/check_contract.py.
TABLES = ["note_type", "note", "card", "review_log", "word_status", "deck", "deck_item", "confusable", "grammar_point",
          "sentence_grammar_span", "settings", "fsrs_params", "media", "parse_override"]
FLOOR_MS = 1577836800000
METHODS = ("get", "put", "post", "delete", "patch")


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def operations(spec: dict) -> dict[str, dict]:
    """operationId -> {method, path, security scopes, cookie_only}."""
    out: dict[str, dict] = {}
    for path, item in spec["paths"].items():
        for method in METHODS:
            op = item.get(method)
            if not op:
                continue
            alts = op.get("security", [])
            scopes = {s for alt in alts for v in alt.values() for s in v}
            schemes = {k for alt in alts for k in alt}
            out[op["operationId"]] = {"method": method.upper(), "path": path, "scopes": scopes,
                                      "cookie_only": bool(schemes) and "bearerAuth" not in schemes, "op": op}
    return out


# ---------------------------------------------------------------------------------------------- ops.json
def check_ops_map(ops_map: dict, api_ops: dict[str, dict]) -> list[str]:
    errs = []
    for oid in api_ops:
        if oid not in ops_map:
            errs.append(f"ops.json: operationId {oid} of openapi.yaml is missing")
    for oid, cls in ops_map.items():
        if oid not in api_ops:
            errs.append(f"ops.json: {oid} is not an operationId of openapi.yaml")
        if cls not in CLASSES:
            errs.append(f"ops.json: {oid} has class {cls!r}; allowed: {sorted(CLASSES)}")
    return errs


def check_ops_scopes(ops_map: dict, api_ops: dict[str, dict]) -> list[str]:
    errs = []
    for oid, info in api_ops.items():
        cls = ops_map.get(oid)
        if cls is None:
            continue
        callable_by_device = not info["cookie_only"] and (not info["scopes"] or bool(info["scopes"] & DEVICE_SCOPES))
        if not callable_by_device and cls != "hidden":
            errs.append(f"ops.json: {oid} cannot be called with a device token (needs {sorted(info['scopes'])}) but is {cls}")
    return errs


# ---------------------------------------------------------------------------------------------- bridge
def check_bridge(schema: dict) -> tuple[list[str], list[str]]:
    root = Draft202012Validator(schema)
    directional = {"web": root.evolve(schema=schema["$defs"]["WebToNative"]),
                   "native": root.evolve(schema=schema["$defs"]["NativeToWeb"]), "any": root}
    ok_errs, bad_errs = [], []
    valid = sorted((HERE / "examples" / "bridge").glob("*.json"))
    invalid = sorted((HERE / "examples" / "bridge-invalid").glob("*.json"))
    if len(valid) < 10 or len(invalid) < 10:
        ok_errs.append("examples/bridge and examples/bridge-invalid need at least 10 files each")
    seen_valid = set()
    for group, files, errs in (("valid", valid, ok_errs), ("invalid", invalid, bad_errs)):
        for f in files:
            prefix = f.name.split(".", 1)[0]
            if prefix not in directional:
                errs.append(f"{f.name}: file name must start with web. / native. / any.")
                continue
            msg = load(f)
            problems = list(root.iter_errors(msg)) + list(directional[prefix].iter_errors(msg))
            if group == "valid":
                seen_valid.add((prefix, msg.get("t")))
                for e in problems[:1]:
                    errs.append(f"examples/bridge/{f.name}: {e.message[:200]}")
            elif not problems:
                errs.append(f"examples/bridge-invalid/{f.name}: accepted, must be rejected")
    for direction, key in (("web", "WebToNative"), ("native", "NativeToWeb")):
        for t in schema["$defs"][key]["allOf"][1]["properties"]["t"]["enum"]:
            if (direction, t) not in seen_valid:
                ok_errs.append(f"examples/bridge: no valid example for {direction} message {t!r}")
    return ok_errs, bad_errs


# ---------------------------------------------------------------------------------------------- shell.yaml
def has_example(media: dict) -> bool:
    return "example" in media or bool(media.get("examples"))


def check_shell_operations(shell: dict, raw_yaml: str) -> list[str]:
    errs = []
    ops = operations(shell)
    for oid in sorted(SHELL_OPS - set(ops)):
        errs.append(f"shell.yaml: operationId {oid} is missing")
    for oid in sorted(set(ops) - SHELL_OPS):
        errs.append(f"shell.yaml: unexpected operationId {oid}")
    for oid, info in ops.items():
        op = info["op"]
        where = f"shell.yaml {info['method']} {info['path']}"
        if op.get("security") != []:
            errs.append(f"{where}: must declare security: []")
        if "x-emits-ops" in op:
            errs.append(f"{where}: shell operations never emit sync ops")
        for ctype, media in op.get("requestBody", {}).get("content", {}).items():
            if not has_example(media):
                errs.append(f"{where}: request body {ctype} has no example")
        if "default" not in op["responses"]:
            errs.append(f"{where}: no default (Problem) response")
        for status, resp in op["responses"].items():
            for ctype, media in resp.get("content", {}).items():
                if not has_example(media):
                    errs.append(f"{where}: response {status} {ctype} has no example")
    if not re.search(r"\$ref: '\.\./openapi\.yaml#/components/schemas/Problem'", raw_yaml):
        errs.append("shell.yaml: Problem must be referenced as ../openapi.yaml#/components/schemas/Problem")
    if re.search(r"^    (Problem|FieldError):", raw_yaml, re.M):
        errs.append("shell.yaml: Problem / FieldError must not be redefined")
    if shell.get("servers", [{}])[0].get("url") != "/shell/v1":
        errs.append("shell.yaml: servers[0].url must be /shell/v1")
    return errs


def check_shell_schema_docs(raw: dict) -> list[str]:
    errs = []

    def walk(name: str, schema: dict) -> None:
        for prop, sub in schema.get("properties", {}).items():
            where = f"shell.yaml {name}.{prop}"
            if not sub.get("description"):
                errs.append(f"{where}: no description")
            is_ref = "$ref" in sub or any("$ref" in alt for alt in sub.get("oneOf", []))
            types = sub.get("type")
            types = types if isinstance(types, list) else [types]
            if not is_ref and "object" not in types and "examples" not in sub and "example" not in sub:
                errs.append(f"{where}: no example")
            if "object" in types:
                walk(f"{name}.{prop}", sub)

    for name, schema in raw["components"]["schemas"].items():
        if not name.startswith("Shell"):
            continue  # pulled in from ../openapi.yaml by the bundler (Problem, Uuid, ...)
        if not schema.get("description"):
            errs.append(f"shell.yaml {name}: no description")
        walk(name, schema)
    return errs


# ---------------------------------------------------------------------------------------------- SHELL.md
def check_shell_md(md: str, ops_map: dict, api_ops: dict, sync_ops: set[str]) -> list[str]:
    errs = []
    for oid in list(ops_map) + sorted(SHELL_OPS):
        if not re.search(r"`%s`" % re.escape(oid), md):
            errs.append(f"SHELL.md: operationId {oid} is not mentioned")
    section = re.search(r"^## 11\. Cross-check.*?(?=^## )", md, re.S | re.M)
    if not section:
        return errs + ["SHELL.md: section '## 11. Cross-check' not found"]
    rows = [line for line in section.group(0).splitlines() if line.startswith("|")]
    for table in TABLES:
        mine = [r for r in rows if re.match(r"\|\s*`%s`\s*\|" % re.escape(table), r)]
        if not mine:
            errs.append(f"SHELL.md cross-check: no row for table {table}")
            continue
        row = mine[0]
        names = re.findall(r"`([A-Za-z_.]+)`", row)
        if not any(n in sync_ops for n in names):
            errs.append(f"SHELL.md cross-check: row for {table} names no sync op")
        named_ops = [n for n in names if n in api_ops]
        if not named_ops:
            errs.append(f"SHELL.md cross-check: row for {table} names no operationId")
        for n in names:
            if re.fullmatch(r"[a-z]+[A-Z][A-Za-z]*", n) and n not in api_ops and n not in SHELL_OPS:
                errs.append(f"SHELL.md cross-check: {n} is not an operationId")
    for code in ("shell_offline", "shell_dict_missing", "shell_rebuilding", "shell_unsupported", "shell_pending_ops"):
        if f"`{code}`" not in md:
            errs.append(f"SHELL.md: problem code {code} is not documented")
    for tag in MANDATORY:
        if f"`{tag}`" not in md:
            errs.append(f"SHELL.md 8.4: mandatory coverage tag {tag} is not listed")
    return errs


# ---------------------------------------------------------------------------------------------- parity
class Sync:
    """contracts/sync schemas, for validating seed ops exactly as contracts/sync/tools/validate.py does."""

    def __init__(self) -> None:
        schemas = {p: load(p) for p in sorted(SYNC.rglob("*.schema.json"))}
        registry = Registry().with_resources(
            (p.as_uri(), Resource.from_contents(s, default_specification=DRAFT202012)) for p, s in schemas.items())
        op_path = SYNC / "op.schema.json"
        self.op = Draft202012Validator({"$ref": op_path.as_uri()}, registry=registry)
        self.index = {o["op_type"]: o for o in load(SYNC / "ops" / "index.json")["ops"]}

    def entity_id(self, op: dict) -> str:
        parts = []
        for part in self.index[op["op_type"]]["entity_id"]:
            if len(part) >= 2 and part[0] == part[-1] == "'":
                parts.append(part[1:-1])
            else:
                value = op["payload"].get(part)
                parts.append(str(value))
        return "|".join(parts)


def wall(hlc: str) -> int:
    return int(hlc[:12], 16)


def check_seed_op(sync: Sync, op: dict, where: str, device: str, not_after: int, last_hlc: str | None) -> list[str]:
    errs = [f"{where}: {e.message[:200]} (at /{'/'.join(map(str, e.absolute_path))})" for e in sync.op.iter_errors(op)]
    if errs:
        return errs[:3]
    meta = sync.index[op["op_type"]]
    if meta["emitter"] != "client":
        errs.append(f"{where}: {op['op_type']} is server-emitted; a seed is pushed by a device")
    if "seq" in op or "caused_by_op_id" in op:
        errs.append(f"{where}: pushed form only (no seq, no caused_by_op_id)")
    if op["device_id"] != device or op["hlc"][-36:] != device:
        errs.append(f"{where}: device_id and the hlc's device part must be seed.device_id")
    if op["entity_type"] != meta["entity_type"] or op["entity_id"] != sync.entity_id(op):
        errs.append(f"{where}: entity_type / entity_id not derived as ops/index.json says")
    if not FLOOR_MS <= wall(op["hlc"]) <= not_after:
        errs.append(f"{where}: wall(hlc) must be within [FLOOR, {not_after}]")
    if last_hlc is not None and op["hlc"] <= last_hlc:
        errs.append(f"{where}: hlc must be above the previous op's")
    if op["op_type"] == "review.logged" and not FLOOR_MS <= op["payload"]["reviewed_at"] <= not_after:
        errs.append(f"{where}: reviewed_at must be within [FLOOR, {not_after}]")
    return errs


def check_seed_accepted(sync: Sync, name: str, sc: dict) -> list[str]:
    """Push the seed, then every apply step, to the reference server. Request steps are not modelled."""
    errs = []
    server = ModelServer(schema_problems=lambda op: [e.message for e in sync.op.iter_errors(op)])
    batches = [("seed.log", sc["clock_ms"], sc["seed"]["log"])]
    batches += [(f"steps[{i}].apply", st["at_ms"], st["apply"]) for i, st in enumerate(sc["steps"]) if "apply" in st]
    pushed = 0
    for label, clock, ops in batches:
        if not ops:
            continue
        server.pt = clock
        for op, result in zip(ops, server.push(copy.deepcopy(ops))["results"]):
            if result["status"] != "accepted":
                code = result.get("error", {}).get("code", result["status"])
                errs.append(f"parity/{name}.json {label}: op {op['op_id']} ({op['op_type']}) is not accepted: {code}")
        pushed += len(ops)
    if not errs and len(server.log) != pushed:
        extra = sorted({o["op_type"] for o in server.log[pushed:]} | {o["op_type"] for o in server.log if o.get("caused_by_op_id")})
        errs.append(f"parity/{name}.json: the server emits ops of its own for this seed ({extra}); a scenario must not need a merge")
    return errs


def match_operation(api_ops: dict, method: str, path: str) -> str | None:
    assert path.startswith("/api/v1")
    rel = path[len("/api/v1"):]
    best = None
    for oid, info in api_ops.items():
        if info["method"] != method:
            continue
        pattern = "^" + re.sub(r"\\\{[^/]+?\\\}", "[^/]+", re.escape(info["path"])) + "$"
        if re.match(pattern, rel) and (best is None or info["path"].count("{") < api_ops[best]["path"].count("{")):
            best = oid
    return best


def query_names(op: dict, spec: dict) -> set[str]:
    names = set()
    for p in op.get("parameters", []):
        if "$ref" in p:
            p = spec["components"]["parameters"][p["$ref"].rsplit("/", 1)[1]]
        if p.get("in") == "query":
            names.add(p["name"])
    return names


def entry_seqs(value) -> set[int]:
    found: set[int] = set()
    if isinstance(value, dict):
        for k, v in value.items():
            if k == "entry_seq" and isinstance(v, int):
                found.add(v)
            elif k == "item_key" and isinstance(v, str) and v.startswith("e:"):
                found.add(int(v[2:]))
            else:
                found |= entry_seqs(v)
    elif isinstance(value, list):
        for v in value:
            found |= entry_seqs(v)
    return found


def pins_phase(body: dict) -> bool:
    """An expect.body that fixes loop.phase: by /loop/phase, or by a whole /loop object holding phase."""
    whole = body.get("/loop")
    return "/loop/phase" in body or (isinstance(whole, dict) and "phase" in whole)


def check_parity(scenarios: dict[str, dict], schema: dict, sync: Sync, api_spec: dict, ops_map: dict,
                 dict_seqs: set[int]) -> dict[str, list[str]]:
    res: dict[str, list[str]] = {"parity_schema": [], "parity_seed_ops": [], "parity_seed_accepted": [], "parity_steps": [],
                                 "parity_mandatory_covered": []}
    api_ops = operations(api_spec)
    validator = Draft202012Validator(schema)
    covered: set[str] = set()
    if not scenarios:
        res["parity_schema"].append("parity/: no scenario")
    for name, sc in scenarios.items():
        errs = [f"parity/{name}.json: {e.message[:200]} (at /{'/'.join(map(str, e.absolute_path))})" for e in validator.iter_errors(sc)]
        if errs:
            res["parity_schema"] += errs[:5]
            continue
        if sc["id"] != name:
            res["parity_schema"].append(f"parity/{name}.json: id must equal the file name")
        covered |= set(sc["covers"])
        seed_dev, dev, clock = sc["seed"]["device_id"], sc["device_id"], sc["clock_ms"]
        st, se = res["parity_steps"], res["parity_seed_ops"]
        if seed_dev == dev:
            st.append(f"parity/{name}.json: device_id must differ from seed.device_id")
        # seed
        last, settings, used_seqs, known_ids = None, {}, set(), set()
        for i, op in enumerate(sc["seed"]["log"]):
            e = check_seed_op(sync, op, f"parity/{name}.json seed.log[{i}]", seed_dev, clock, last)
            se += e
            if e:
                continue
            last = op["hlc"]
            if op["op_type"] == "settings.set":
                settings[op["payload"]["key"]] = op["payload"]["value"]  # log order = hlc order: the last one wins
            used_seqs |= entry_seqs(op["payload"])
        if settings != sc["settings"]:
            st.append(f"parity/{name}.json: settings {sc['settings']} is not the fold of the seed log {settings}")
        # steps
        prev_at, used_ops, request_steps = clock, set(), set()
        for i, step in enumerate(sc["steps"]):
            where = f"parity/{name}.json steps[{i}]"
            if step["at_ms"] <= clock or step["at_ms"] < prev_at:
                st.append(f"{where}: at_ms must be after clock_ms and never decrease")
            prev_at = step["at_ms"]
            if "apply" in step:
                for j, op in enumerate(step["apply"]):
                    e = check_seed_op(sync, op, f"{where}.apply[{j}]", seed_dev, step["at_ms"], last)
                    se += e
                    if not e:
                        last = op["hlc"]
                        used_seqs |= entry_seqs(op["payload"])
                continue
            request_steps.add(i)
            req = step["request"]
            oid = match_operation(api_ops, req["method"], req["path"])
            if oid is None:
                st.append(f"{where}: {req['method']} {req['path']} is no operation of openapi.yaml")
                continue
            used_ops.add(oid)
            if ops_map.get(oid) not in ("local", "local_or_proxy"):
                st.append(f"{where}: {oid} is {ops_map.get(oid)}; parity only covers local operations")
            if (req["body"] is not None) != ("requestBody" in api_ops[oid]["op"]):
                st.append(f"{where}: {oid} body presence does not match the operation")
            allowed = query_names(api_ops[oid]["op"], api_spec)
            for pair in filter(None, req["query"].split("&")):
                if pair.split("=", 1)[0] not in allowed:
                    st.append(f"{where}: query parameter {pair.split('=', 1)[0]!r} is not one of {oid}")
            used_seqs |= entry_seqs(req["body"])
            exp = step.get("expect")
            if exp is None:
                st.append(f"{where}: every request step states at least expect.status")
            elif exp["status"] < 400 and ("problem_code" in exp or "field_error" in exp):
                st.append(f"{where}: problem expectations on a 2xx status")
            elif exp["status"] >= 400 and "problem_code" not in exp:
                st.append(f"{where}: a non-2xx expectation names problem_code")
        if not any(e.startswith(f"parity/{name}.json") for e in se):
            res["parity_seed_accepted"] += check_seed_accepted(sync, name, sc)
        if sorted(used_ops) != sc["operations"]:
            st.append(f"parity/{name}.json: operations must be {sorted(used_ops)}")
        missing = sc.get("dict_missing", [])
        if missing != sorted(missing) or set(missing) & set(sc["dict_entries"]):
            st.append(f"parity/{name}.json: dict_missing must be ascending and disjoint from dict_entries")
        if sorted(used_seqs - set(missing)) != sc["dict_entries"] or not set(missing) <= used_seqs:
            st.append(f"parity/{name}.json: dict_entries must be {sorted(used_seqs - set(missing))} and every dict_missing seq must be used")
        for seq in sorted(set(sc["dict_entries"]) - dict_seqs):
            st.append(f"parity/{name}.json: entry {seq} is not in fixtures/dict/JMdict_subset.xml")
        for seq in sorted(set(missing) & dict_seqs):
            st.append(f"parity/{name}.json: dict_missing entry {seq} exists in fixtures/dict/JMdict_subset.xml")
        for inv in sc.get("invariants", []):
            for ref in inv["equal"]:
                if ref["step"] not in request_steps:
                    st.append(f"parity/{name}.json: invariant names step {ref['step']}, which is not a request step")
    for tag in MANDATORY:
        if tag not in covered:
            res["parity_mandatory_covered"].append(f"parity/: mandatory coverage tag {tag} is covered by no scenario")
    # ADR-037: a scenario covering a queue.loop_* tag must pin the phase, so a golden cannot be recorded without it.
    for name, sc in scenarios.items():
        if not any(t.startswith("queue.loop_") for t in sc.get("covers", [])):
            continue
        pinned = [st for st in sc.get("steps", []) if pins_phase(st.get("expect", {}).get("body", {}))]
        if not pinned:
            res["parity_mandatory_covered"].append(f"parity/{name}.json: covers a queue.loop_* tag but no step expects /loop/phase")
    for tag in RETIRED:
        if tag in covered:
            res["parity_mandatory_covered"].append(f"parity/: retired coverage tag {tag} is still used by a scenario")
    return res


# ---------------------------------------------------------------------------------------------- main
def main() -> int:
    api_spec = load(Path(sys.argv[1]))
    shell_spec = load(Path(sys.argv[2]))
    api_ops = operations(api_spec)
    results: dict[str, list[str]] = {}

    bridge = load(HERE / "bridge.schema.json")
    parity_schema = load(HERE / "parity.schema.json")
    results["bridge_schema_valid"] = []
    for label, s in (("bridge.schema.json", bridge), ("parity.schema.json", parity_schema)):
        try:
            Draft202012Validator.check_schema(s)
        except Exception as e:  # noqa: BLE001
            results["bridge_schema_valid"].append(f"{label}: not a valid schema: {e}")
    results["bridge_examples_valid"], results["bridge_examples_invalid"] = check_bridge(bridge)

    ops_map = load(HERE / "ops.json")
    results["ops_map_complete"] = check_ops_map(ops_map, api_ops)
    results["ops_map_scopes"] = check_ops_scopes(ops_map, api_ops)

    raw_yaml = (HERE / "shell.yaml").read_text(encoding="utf-8")
    results["shell_operations"] = check_shell_operations(shell_spec, raw_yaml)
    results["shell_schema_docs"] = check_shell_schema_docs(shell_spec)

    sync = Sync()
    results["shell_md_cross_check"] = check_shell_md((HERE / "SHELL.md").read_text(encoding="utf-8"), ops_map, api_ops, set(sync.index))

    scenarios = {p.stem: load(p) for p in sorted((HERE / "parity").glob("*.json"))}
    dict_seqs = {int(m) for m in re.findall(r"<ent_seq>(\d+)</ent_seq>", (ROOT / "fixtures/dict/JMdict_subset.xml").read_text(encoding="utf-8"))}
    results.update(check_parity(scenarios, parity_schema, sync, api_spec, ops_map, dict_seqs))

    # ---- self-tests: the checks must fail on broken input (ticket acceptance: negative cases)
    def expect_failure(name: str, errs: list[str], needle: str) -> None:
        results[name] = [] if any(needle in e for e in errs) else [f"{name}: the check did not report {needle!r}"]

    victim = next(iter(ops_map))
    removed = {k: v for k, v in ops_map.items() if k != victim}
    expect_failure("selftest_ops_missing_id", check_ops_map(removed, api_ops), f"operationId {victim} of openapi.yaml is missing")
    expect_failure("selftest_ops_invented_id", check_ops_map({**ops_map, "inventedOperation": "local"}, api_ops), "inventedOperation is not an operationId")
    expect_failure("selftest_ops_bad_class", check_ops_map({**ops_map, victim: "remote"}, api_ops), "has class 'remote'")
    expect_failure("selftest_ops_scope", check_ops_scopes({**ops_map, "listTokens": "proxy"}, api_ops), "listTokens cannot be called with a device token")
    with_seed = next((n for n, s in scenarios.items() if s["seed"]["log"]), None)
    if with_seed is None:
        results["selftest_parity_bad_seed_op"] = ["no scenario with a seed op to break"]
    else:
        broken = copy.deepcopy(scenarios[with_seed])
        broken["seed"]["log"][0]["payload"]["not_a_property"] = 1
        r = check_parity({with_seed: broken}, parity_schema, sync, api_spec, ops_map, dict_seqs)
        expect_failure("selftest_parity_bad_seed_op", r["parity_seed_ops"], "seed.log[0]")
        broken = copy.deepcopy(scenarios[with_seed])
        broken["seed"]["log"][0]["seq"] = 1
        r = check_parity({with_seed: broken}, parity_schema, sync, api_spec, ops_map, dict_seqs)
        expect_failure("selftest_parity_pulled_form", r["parity_schema"] + r["parity_seed_ops"], with_seed)
        broken = copy.deepcopy(scenarios[with_seed])
        orphan = next((o for o in broken["seed"]["log"] if o["op_type"] == "card.created"), None)
        if orphan is None:
            results["selftest_parity_rejected_seed"] = ["the first scenario with a seed has no card.created to orphan"]
        else:
            broken["seed"]["log"] = [o for o in broken["seed"]["log"] if o["op_type"] != "note.created"]
            r = check_parity({with_seed: broken}, parity_schema, sync, api_spec, ops_map, dict_seqs)
            expect_failure("selftest_parity_rejected_seed", r["parity_seed_accepted"], "is not accepted: unknown_reference")
    fewer = {n: s for n, s in scenarios.items() if "undo.twice" not in s["covers"]}
    r = check_parity(fewer, parity_schema, sync, api_spec, ops_map, dict_seqs)
    expect_failure("selftest_parity_missing_mandatory", r["parity_mandatory_covered"], "undo.twice")
    fewer = {n: s for n, s in scenarios.items() if "queue.loop_goal_reached" not in s["covers"]}
    r = check_parity(fewer, parity_schema, sync, api_spec, ops_map, dict_seqs)
    expect_failure("selftest_parity_missing_loop_tag", r["parity_mandatory_covered"], "queue.loop_goal_reached")
    with_loop = next((n for n, s in scenarios.items() if any(t.startswith("queue.loop_") for t in s["covers"])), None)
    if with_loop is None:
        results["selftest_parity_loop_phase_unpinned"] = ["no scenario covers a queue.loop_* tag"]
    else:
        broken = copy.deepcopy(scenarios[with_loop])
        for st in broken["steps"]:
            st.get("expect", {}).get("body", {}).pop("/loop/phase", None)
            st.get("expect", {}).get("body", {}).pop("/loop", None)
        r = check_parity({with_loop: broken}, parity_schema, sync, api_spec, ops_map, dict_seqs)
        expect_failure("selftest_parity_loop_phase_unpinned", r["parity_mandatory_covered"], "no step expects /loop/phase")

    failed = 0
    for name, errs in results.items():
        print(f"  {name}: {'ok' if not errs else 'FAILED'}")
        for e in errs[:40]:
            print(f"      {e}")
        failed += bool(errs)
    if failed:
        print(f"contracts/api/shell: {failed} check(s) failed", file=sys.stderr)
        return 1
    print(f"contracts/api/shell: OK ({len(ops_map)} operations mapped, {len(SHELL_OPS)} shell operations, "
          f"{len(list((HERE / 'examples' / 'bridge').glob('*.json')))} + {len(list((HERE / 'examples' / 'bridge-invalid').glob('*.json')))} bridge examples, "
          f"{len(scenarios)} parity scenarios)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
