#!/usr/bin/env python3
"""Checks contracts/api/shell (SHELL-C1). Run through ../check.sh.

usage: check_shell.py <bundled openapi.json> <bundled shell.json>

Named checks (each prints one line; the run fails if any fails):
  bridge_schema_valid          bridge.schema.json and parity.schema.json are valid JSON Schema 2020-12
  bridge_examples_valid        every examples/bridge/*.json validates (root schema, and the direction its
                               file-name prefix names: web. / native.; any. = root only)
  bridge_examples_invalid      every examples/bridge-invalid/*.json is REJECTED by the same schema
  ops_map_complete             ops.json has every operationId of openapi.yaml exactly once, invents none,
                               and uses only local | local_or_proxy | cached | proxy | hidden
  ops_map_scopes               an operation a device token cannot call (needs import/admin only, or is
                               cookie-only) is `hidden`; C-31: a `local` / `local_or_proxy` / `cached` operation has
                               no security or a bearer alternative whose scopes are ALL held by a device token
                               (read, write, review), so no such operation needs a scope a device token lacks;
                               a `cached` operation is a GET (a cached write would be a lost write)
  ops_map_local_writes         C-31 (SHELL.md rule L-10): an operation whose x-emits-ops are all client-emittable and
                               applied by devices (contracts/sync/ops/index.json) is `local` (or `hidden`), so
                               no write a device can make is left behind the network
  ops_map_counts               C-31: the Count column of the class table in SHELL.md 3.2 equals the number of
                               operations of each class in ops.json, and every operation is named in the bold
                               list of its own class there
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
  parity_until_stop            C-34 (ADR-054): in every expect.body that pins both `until_stop` and `until_stop_parts`
                               (whole /loop or pointers), until_stop = parts.learning + parts.review + parts.new
                               (`later` is not summed); one that pins both phase and until_stop has until_stop 0
                               when the phase is waiting / goal_reached / done_for_today / empty
  selftest_*                   negative cases: the checks above really fail on a removed operationId, an
                               invented one, a bad class, a broken seed op, a seed the server would reject
                               and a missing mandatory scenario; C-31: on a `cached` / `local` operation that
                               needs a scope a device token lacks, on a `cached` operation that is not a GET,
                               on a class count in SHELL.md that is off by one, and on a device-mintable write
                               classified `proxy`
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

CLASSES = {"local", "local_or_proxy", "cached", "proxy", "hidden"}
# C-31: answered on the device without the server (SHELL.md rule L-9); they must not need a scope a device token lacks.
ON_DEVICE = ("local", "local_or_proxy", "cached")
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
    # C-28c: draw order is serve order (rebuild = tail), per-deck limit of a unit
    "queue.kanji_order_stable", "queue.kanji_deck_limit", "queue.kanji_turn_order",
    # C-28d: rebuild = tail without exclusions (shared kanji, keep going with an open unit, capped lead)
    "queue.kanji_shared_kanji", "queue.kanji_keep_going_open_unit", "queue.kanji_lead_deck_limit",
    # C-28f: ADR-046 (kana words by headword; tagged words require jōyō kanji only)
    "queue.kanji_kana_word", "queue.kanji_tagged_joyo_only",
    # C-30: ADR-049 (waiting before the stop screen while learning cards are due today)
    "queue.loop_waits_before_stop", "queue.loop_learning_past_day_end",
    # C-31 (ADR-050..052): the newly local operations and the keep-going target
    "stats.summary_local", "stats.heatmap_local", "stats.retention_local", "cards.bulk_all_actions_local",
    "cards.bulk_schedule_local", "speech.resolve_local", "queue.keep_going_target", "queue.keep_going_batch_not_owed",
    # C-31 (rule L-10): every write a device can mint and apply is local
    "cards.single_writes_local", "presets.writes_local", "note_types.writes_local", "confusables.writes_local",
    "grammar.writes_local",
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
            # bearer_alts: the scope sets of the bearerAuth alternatives (each alternative needs ALL its scopes)
            bearer_alts = [set(alt["bearerAuth"]) for alt in alts if "bearerAuth" in alt]
            out[op["operationId"]] = {"method": method.upper(), "path": path, "scopes": scopes,
                                      "cookie_only": bool(schemes) and "bearerAuth" not in schemes, "op": op,
                                      "open": not alts, "bearer_alts": bearer_alts}
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
        if cls in ON_DEVICE:
            # C-31: stricter than "some scope overlaps": one bearer alternative must be satisfied completely.
            if not info["open"] and not any(alt <= DEVICE_SCOPES for alt in info["bearer_alts"]):
                errs.append(f"ops.json: {oid} is {cls} but needs a scope a device token lacks "
                            f"({[sorted(a) for a in info['bearer_alts']]}; a device token holds {sorted(DEVICE_SCOPES)})")
        if cls == "cached" and info["method"] != "GET":
            errs.append(f"ops.json: {oid} is cached but is a {info['method']}; only GET operations may be cached")
    return errs


def check_ops_local_writes(ops_map: dict, api_ops: dict[str, dict], op_index: dict[str, dict]) -> list[str]:
    """SHELL.md rule L-10: a write whose ops a device may mint and apply must not need the network."""
    errs = []
    for oid, info in api_ops.items():
        emits = info["op"].get("x-emits-ops") or []
        if not emits or ops_map.get(oid) in ("local", "hidden", None):
            continue
        unknown = [e for e in emits if e not in op_index]
        if unknown:
            errs.append(f"openapi.yaml: {oid} x-emits-ops names unknown op(s) {unknown}")
            continue
        if all(op_index[e]["emitter"] == "client" and op_index[e]["device_applies"] for e in emits):
            errs.append(f"ops.json: {oid} is {ops_map[oid]} but every op it emits ({emits}) can be minted and applied "
                        f"by a device; rule L-10 requires local")
    return errs


def check_ops_counts(md: str, ops_map: dict) -> list[str]:
    """SHELL.md 3.2: the class table's counts and the per-class bold lists agree with ops.json."""
    errs = []
    section = re.search(r"^### 3\.2 Classes.*?(?=^### )", md, re.S | re.M)
    if not section:
        return ["SHELL.md: section '### 3.2 Classes' not found"]
    text = section.group(0)
    for cls in sorted(CLASSES):
        want = sum(1 for c in ops_map.values() if c == cls)
        m = re.search(r"^\|\s*`%s`\s*\|\s*(\d+)\s*\|" % re.escape(cls), text, re.M)
        if not m:
            errs.append(f"SHELL.md 3.2: no table row for class {cls}")
        elif int(m.group(1)) != want:
            errs.append(f"SHELL.md 3.2: class {cls} count is {m.group(1)}, ops.json has {want}")
        block = re.search(r"^\*\*`%s`(?::\*\*|\*\*)(.*?)(?=^\*\*`|^`check\.sh`)" % re.escape(cls), text, re.S | re.M)
        if not block:
            errs.append(f"SHELL.md 3.2: no '**`{cls}`**' list")
            continue
        named = set(re.findall(r"`([a-z][A-Za-z0-9]+)`", block.group(1)))
        for oid, c in ops_map.items():
            if c == cls and oid not in named:
                errs.append(f"SHELL.md 3.2: {oid} is {cls} in ops.json but is not in the {cls} list")
            if c != cls and oid in named:
                errs.append(f"SHELL.md 3.2: {oid} is listed under {cls} but is {c} in ops.json")
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
    for code in ("shell_offline", "shell_dict_missing", "shell_rebuilding", "shell_unsupported", "shell_pending_ops",
                 "shell_uncached"):
        if f"`{code}`" not in md:
            errs.append(f"SHELL.md: problem code {code} is not documented")
    for tag in MANDATORY:
        if f"`{tag}`" not in md:
            errs.append(f"SHELL.md 8.4: mandatory coverage tag {tag} is not listed")
    # C-31: the rules the local-first tickets cite by name must exist under these names.
    for rule in ("**L-9 ", "**P-2 ", "**P-3 ", "**K-1 ", "**K-2 ", "**K-3 ", "**K-4 "):
        if rule not in md:
            errs.append(f"SHELL.md: rule {rule.strip('* ')} is not defined")
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


STOPPED_PHASES = ("waiting", "goal_reached", "done_for_today", "empty")


def loop_numbers(body: dict) -> tuple[object, object, object]:
    """(phase, until_stop, until_stop_parts) pinned by an expect.body, by a whole /loop or by pointers; None if absent."""
    whole = body.get("/loop") if isinstance(body.get("/loop"), dict) else {}
    phase = body.get("/loop/phase", whole.get("phase"))
    until_stop = body.get("/loop/until_stop", whole.get("until_stop"))
    parts = body.get("/loop/until_stop_parts", whole.get("until_stop_parts"))
    return phase, until_stop, parts


def check_until_stop(scenarios: dict[str, dict]) -> list[str]:
    """C-34 (ADR-054): until_stop counts only cards doable now; later is information, never summed."""
    errs: list[str] = []
    for name, sc in scenarios.items():
        for i, step in enumerate(sc.get("steps", [])):
            body = (step.get("expect") or {}).get("body") or {}
            phase, until_stop, parts = loop_numbers(body)
            where = f"parity/{name}.json steps[{i}]"
            if isinstance(until_stop, int) and isinstance(parts, dict):
                want = sum(parts.get(k, 0) for k in ("learning", "review", "new"))
                if until_stop != want:
                    errs.append(f"{where}: until_stop {until_stop} != learning + review + new = {want} (ADR-054: later is not summed)")
            if isinstance(until_stop, int) and phase in STOPPED_PHASES and until_stop != 0:
                errs.append(f"{where}: phase {phase} with until_stop {until_stop}; nothing is doable now, so it must be 0 (ADR-054)")
    return errs


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
    shell_md = (HERE / "SHELL.md").read_text(encoding="utf-8")
    results["ops_map_counts"] = check_ops_counts(shell_md, ops_map)
    op_index = {o["op_type"]: o for o in load(SYNC / "ops" / "index.json")["ops"]}
    results["ops_map_local_writes"] = check_ops_local_writes(ops_map, api_ops, op_index)

    raw_yaml = (HERE / "shell.yaml").read_text(encoding="utf-8")
    results["shell_operations"] = check_shell_operations(shell_spec, raw_yaml)
    results["shell_schema_docs"] = check_shell_schema_docs(shell_spec)

    sync = Sync()
    results["shell_md_cross_check"] = check_shell_md((HERE / "SHELL.md").read_text(encoding="utf-8"), ops_map, api_ops, set(sync.index))

    scenarios = {p.stem: load(p) for p in sorted((HERE / "parity").glob("*.json"))}
    dict_seqs = {int(m) for m in re.findall(r"<ent_seq>(\d+)</ent_seq>", (ROOT / "fixtures/dict/JMdict_subset.xml").read_text(encoding="utf-8"))}
    results.update(check_parity(scenarios, parity_schema, sync, api_spec, ops_map, dict_seqs))
    results["parity_until_stop"] = check_until_stop(scenarios)

    # ---- self-tests: the checks must fail on broken input (ticket acceptance: negative cases)
    def expect_failure(name: str, errs: list[str], needle: str) -> None:
        results[name] = [] if any(needle in e for e in errs) else [f"{name}: the check did not report {needle!r}"]

    victim = next(iter(ops_map))
    removed = {k: v for k, v in ops_map.items() if k != victim}
    expect_failure("selftest_ops_missing_id", check_ops_map(removed, api_ops), f"operationId {victim} of openapi.yaml is missing")
    expect_failure("selftest_ops_invented_id", check_ops_map({**ops_map, "inventedOperation": "local"}, api_ops), "inventedOperation is not an operationId")
    expect_failure("selftest_ops_bad_class", check_ops_map({**ops_map, victim: "remote"}, api_ops), "has class 'remote'")
    expect_failure("selftest_ops_scope", check_ops_scopes({**ops_map, "listTokens": "proxy"}, api_ops), "listTokens cannot be called with a device token")
    # C-31 negative cases
    expect_failure("selftest_ops_cached_scope", check_ops_scopes({**ops_map, "listTokens": "cached"}, api_ops),
                   "listTokens is cached but needs a scope a device token lacks")
    expect_failure("selftest_ops_local_scope", check_ops_scopes({**ops_map, "importMedia": "local"}, api_ops),
                   "importMedia is local but needs a scope a device token lacks")
    expect_failure("selftest_ops_cached_not_get", check_ops_scopes({**ops_map, "setConfusable": "cached"}, api_ops),
                   "setConfusable is cached but is a PUT")
    moved = next(o for o, c in ops_map.items() if c == "cached")
    expect_failure("selftest_ops_counts", check_ops_counts(shell_md, {**ops_map, moved: "proxy"}), "count is")
    expect_failure("selftest_ops_local_writes", check_ops_local_writes({**ops_map, "deleteCard": "proxy"}, api_ops, op_index),
                   "deleteCard is proxy but every op it emits")
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

    # C-34 (ADR-054) negative cases: an expectation that sums `later` into until_stop, and a waiting step with until_stop > 0.
    summed = {"t": {"steps": [{"expect": {"body": {"/loop": {"phase": "reviewing", "until_stop": 3,
                                                               "until_stop_parts": {"learning": 1, "later": 2, "review": 0, "new": 0}}}}}]}}
    expect_failure("selftest_parity_until_stop_sums_later", check_until_stop(summed), "learning + review + new = 1")
    waiting = {"t": {"steps": [{"expect": {"body": {"/loop/phase": "waiting", "/loop/until_stop": 2}}}]}}
    expect_failure("selftest_parity_until_stop_waiting", check_until_stop(waiting), "must be 0")

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
