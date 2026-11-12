#!/usr/bin/env python3
"""Checks the sync contract. Run through ../check.sh.

1. Every *.schema.json is a valid JSON Schema 2020-12 document.
2. Every op type in ops/index.json has a payload schema, at least one example,
   a branch in op.schema.json and a section in OPS.md; nothing extra exists.
3. Every example validates: examples/ops (op.schema.json), examples/batches
   (batch.schema.json and the specific shape named by the file), examples/scenarios
   (scenario.schema.json). Every examples/invalid instance is REJECTED. Every
   examples/raw-push body gets the verdict of SYNC.md 5.2 "Parsing the body" (C-2).
4. Example ops are well formed beyond what JSON Schema can say (entity_id
   derivation, hlc/device agreement) and form one applicable story.
5. Every scenario is self-consistent under the reference model (tools/model.py):
   the log gives the expected state on a server replica and on a device replica,
   replaying the log twice changes nothing, and - where order_free is claimed -
   random interleavings with random re-deliveries converge.
6. The server's verdicts in every scenario log (note.merged, card.merged and the
   card.created it makes) are DERIVED: the model's Server is given only the
   device ops and must emit exactly the logged ones (SYNC.md 8.1). Every rejected
   op whose code the model decides gets that code.
7. The backup-restore story (SYNC.md 12.5) loses data under the attempt-1 rules
   and converges without loss under log_epoch / durable_seq. A restore below a
   device's pull_seq (SYNC.md 12.5.1) livelocks under the pre-C-10 log reset and
   converges when the reset sets pull_seq = 0.
"""
from __future__ import annotations

import copy
import json
import random
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator, ValidationError, validators
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

sys.path.insert(0, str(Path(__file__).parent))
from model import (CHECKED_CODES, DAY_MS, FLOOR_MS, SERVER, VERDICT_OPS, Device, Livelock, MissingRow,  # noqa: E402
                   Replica, Server, remint_select, remint_times, wall)

VOCAB = "00000000-0000-7000-8000-000000000101"

ROOT = Path(__file__).resolve().parent.parent
PERMUTATIONS = 300
errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


# ------------------------------------------------------------------ schemas
schemas = {p: load(p) for p in sorted(ROOT.rglob("*.schema.json"))}
registry = Registry().with_resources(
    (p.as_uri(), Resource.from_contents(s, default_specification=DRAFT202012)) for p, s in schemas.items())
for p, s in schemas.items():
    try:
        Draft202012Validator.check_schema(s)
    except Exception as e:  # noqa: BLE001
        fail(f"{p.relative_to(ROOT)}: not a valid schema: {e}")


# ------------------------------------------------------------------ ECMA-262 patterns (SYNC.md 2.1, C-5)
# JSON Schema `pattern` is ECMA-262. Python `re` differs: `$` also matches before a final "\n", `\s`
# is a different whitespace set, `\d`/`\w` are Unicode-aware, `.` matches U+000D/U+2028/U+2029.
# ecma_to_python translates the constructs whose meaning SYNC.md 2.1 fixes and raises on anything else.
ECMA_WS = "\t\n\x0b\x0c\r \xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
_IN_CLASS = {"s": ECMA_WS, "d": "0-9", "w": "A-Za-z0-9_"}
_OUTSIDE = {"s": f"[{ECMA_WS}]", "S": f"[^{ECMA_WS}]", "d": "[0-9]", "D": "[^0-9]",
            "w": "[A-Za-z0-9_]", "W": "[^A-Za-z0-9_]"}
_PASS = set(".\\^$|?*+()[]{}/-") | {"n", "r", "t", "f", "v"}
_QUANTIFIER = re.compile(r"\{[0-9]+(,[0-9]*)?\}")


def ecma_to_python(pattern: str) -> str:
    out: list[str] = []
    i, in_class = 0, False
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            if i + 1 >= len(pattern):
                raise ValueError(f"pattern {pattern!r}: trailing backslash")
            e = pattern[i + 1]
            i += 2
            if in_class and e in _IN_CLASS and ("-" in (pattern[i - 3], pattern[i:i + 1])):
                # [\s-z] / [a-\s]: ECMA without u reads '-' as a literal, with u it is a syntax error, and
                # Python would read the expanded set's last character as a range start (C-6)
                raise ValueError(f"pattern {pattern!r}: class escape \\{e} next to '-' (SYNC.md 2.1)")
            if e == "-" and not in_class:
                raise ValueError(f"pattern {pattern!r}: \\- outside a class is a syntax error with the u flag")
            if in_class and e in _IN_CLASS:
                out.append(_IN_CLASS[e])
            elif not in_class and e in _OUTSIDE:
                out.append(_OUTSIDE[e])
            elif e == "u" and len(pattern) >= i + 4 and all(h in "0123456789abcdefABCDEF" for h in pattern[i:i + 4]):
                out.append("\\u" + pattern[i:i + 4])
                i += 4
            elif e in _PASS:
                out.append("\\" + e if e != "v" else "\\x0b")
            else:
                raise ValueError(f"pattern {pattern!r}: escape \\{e} has no agreed meaning (SYNC.md 2.1)")
            continue
        if in_class:
            if c == "[":
                out.append("\\[")  # literal in ECMA; a nested set in Python
            else:
                if c == "]":
                    in_class = False
                out.append(c)
        elif c == "[":
            rest = pattern[i + 1:]
            if rest.startswith("]") or rest.startswith("^]"):
                raise ValueError(f"pattern {pattern!r}: [] / [^] differ between ECMA and Python")
            in_class = True
            out.append(c)
        elif c in "{}":
            # `{,5}`, `{x}`, a lone `}`: literals in ECMA without u (Annex B), syntax errors with u, and `{,5}`
            # is a quantifier (0 to 5) in Python (C-6)
            q = _QUANTIFIER.match(pattern, i) if c == "{" else None
            if q is None:
                raise ValueError(f"pattern {pattern!r}: '{c}' outside a {{n}}, {{n,}} or {{n,m}} quantifier")
            out.append(q.group())
            i = q.end()
            continue
        elif c == "$":
            out.append("\\Z")  # end of input only
        elif c == ".":
            out.append("[^\\n\\r\\u2028\\u2029]")
        elif c == "(" and pattern[i + 1:i + 2] == "?" and pattern[i + 2:i + 3] not in (":", "=", "!"):
            raise ValueError(f"pattern {pattern!r}: group syntax not supported")
        else:
            out.append(c)
        i += 1
    if in_class:
        raise ValueError(f"pattern {pattern!r}: unterminated class")
    return "".join(out)


_compiled: dict[str, re.Pattern[str]] = {}


def ecma_search(pattern: str, text: str) -> bool:
    if pattern not in _compiled:
        _compiled[pattern] = re.compile(ecma_to_python(pattern))
    return _compiled[pattern].search(text) is not None


def _ecma_pattern(validator, pattern, instance, schema):  # noqa: ANN001, ANN202
    if validator.is_type(instance, "string") and not ecma_search(pattern, instance):
        yield ValidationError(f"{instance!r} does not match {pattern!r} (ECMA-262)")


# `version=` registers the class for the 2020-12 meta-schema URI: without it, a $ref into a resource that
# declares "$schema" (all of ours) is validated by the stock Draft202012Validator, i.e. with Python `re`.
EcmaValidator = validators.extend(Draft202012Validator, {"pattern": _ecma_pattern}, version="draft2020-12-ecma262")

for pat, text, want in [
    ("^[0-9a-f]{4}$", "abcd", True), ("^[0-9a-f]{4}$", "abcd\n", False), ("^[0-9a-f]{4}$", "abcd\r", False),
    ("^\\S+$", "a\u3000b", False), ("^\\S+$", "a\u00a0b", False), ("^\\S+$", "a\ufeff", False),
    ("^\\S+$", "a\u2028", False), ("^\\S+$", "a\x1cb", True), ("^\\S+$", "a\x85b", True), ("^\\S+$", "食べる", True),
    ("^\\d+$", "\u0663", False), ("^\\d+$", "12", True), ("^.$", "\r", False), ("^.$", "\x85", True),
    ("^[\\s]$", "\ufeff", True), ("^[\\s]$", "\x1c", False), ("^a\\.b$", "a.b", True), ("^a\\.b$", "axb", False),
]:
    if ecma_search(pat, text) != want:
        fail(f"ecma_to_python: {pat!r} on {text!r} should give {want}")
for pat in ["^[\\s-z]$", "^[a-\\s]$", "^[\\d-]$", "^a{,5}$", "^a{x}$", "^a}$", "^a\\-b$"]:  # C-6: ambiguous across u / Python
    try:
        ecma_to_python(pat)
        fail(f"ecma_to_python: {pat!r} must be refused")
    except ValueError:
        pass
for pat, text, want in [("^[^\\u0000]*$", "a\x00b", False), ("^[^\\u0000]*$", "食べ\U0001F600", True),
                        ("^a{2,}$", "aaa", True), ("^a{2,3}$", "aaaa", False)]:
    if ecma_search(pat, text) != want:
        fail(f"ecma_to_python: {pat!r} on {text!r} should give {want}")


def _patterns(node, under_properties: bool = False):  # noqa: ANN001, ANN201
    """Every `pattern` keyword value in a schema tree (not a property that happens to be named so)."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "pattern" and isinstance(v, str) and not under_properties:
                yield v
            yield from _patterns(v, k in ("properties", "$defs", "patternProperties"))
    elif isinstance(node, list):
        for v in node:
            yield from _patterns(v)


SCHEMA_PATTERNS = {(p_, pat) for p_, s_ in schemas.items() for pat in _patterns(s_)}
for p_, pat in sorted(SCHEMA_PATTERNS):
    try:
        re.compile(ecma_to_python(pat))
    except (ValueError, re.error) as e:
        fail(f"{p_.relative_to(ROOT)}: {e}")


def validator(ref: str) -> Draft202012Validator:
    """ref is relative to the contract root, e.g. 'batch.schema.json#/$defs/PushRequest'."""
    return EcmaValidator({"$ref": (ROOT / "x").as_uri().rsplit("/", 1)[0] + "/" + ref}, registry=registry)


def problems(ref: str, instance) -> list[str]:
    return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message[:200]}" for e in validator(ref).iter_errors(instance)]


def must_validate(ref: str, instance, where: str) -> None:
    for p in problems(ref, instance):
        fail(f"{where}: does not match {ref}: {p}")


# ------------------------------------------------------------------ registry of ops
index = {m["op_type"]: m for m in load(ROOT / "ops/index.json")["ops"]}
op_schema = load(ROOT / "op.schema.json")
if op_schema["properties"]["op_type"]["enum"] != list(index):
    fail("op.schema.json op_type enum differs from ops/index.json")
branches = {b["if"]["properties"]["op_type"]["const"] for b in op_schema["allOf"]}
if branches != set(index):
    fail("op.schema.json allOf branches differ from ops/index.json")
on_disk = {p.name[: -len(".schema.json")] for p in (ROOT / "ops").glob("*.schema.json")}
if on_disk != set(index):
    fail(f"ops/*.schema.json differ from ops/index.json: {sorted(on_disk ^ set(index))}")
ops_md = (ROOT / "OPS.md").read_text(encoding="utf-8") if (ROOT / "OPS.md").exists() else ""
headings = set(re.findall(r"^### `([a-z_.]+)`", ops_md, flags=re.M))
if headings != set(index):
    fail(f"OPS.md sections differ from ops/index.json: {sorted(headings ^ set(index))}")
for t in index:
    sect = ops_md.split(f"### `{t}`", 1)[-1].split("\n### ", 1)[0]
    if "Conflict rule" not in sect:
        fail(f"OPS.md: {t} has no conflict rule")


def check_op_form(op: dict, where: str) -> None:
    meta = index.get(op.get("op_type"))
    if not meta:
        return
    want = "|".join(part[1:-1] if part.startswith("'") else str(op["payload"][part]) for part in meta["entity_id"])
    if op["entity_id"] != want:
        fail(f"{where}: entity_id {op['entity_id']!r} should be {want!r}")
    if op["hlc"][18:] != op["device_id"]:
        fail(f"{where}: device_id is not the device part of hlc")
    if meta["emitter"] == "server" and op["device_id"] != SERVER:
        fail(f"{where}: {op['op_type']} may only be emitted by the server")


# ------------------------------------------------------------------ examples/ops
example_ops = {p: load(p) for p in sorted((ROOT / "examples/ops").glob("*.json"))}
covered = set()
for p, op in example_ops.items():
    where = str(p.relative_to(ROOT))
    must_validate("op.schema.json", op, where)
    check_op_form(op, where)
    if not p.name.startswith(op["op_type"] + "."):
        fail(f"{where}: file name must start with the op type")
    covered.add(op["op_type"])
for t in sorted(set(index) - covered):
    fail(f"examples/ops: no example for {t}")
story = Replica(device=False)
for p, op in sorted(example_ops.items(), key=lambda kv: kv[1]["hlc"]):
    try:
        story.apply(op)
    except MissingRow as e:
        fail(f"{p.relative_to(ROOT)}: references a row no earlier example creates ({e})")

# ------------------------------------------------------------------ examples/batches
SHAPES = {"push-request": "PushRequest", "push-response": "PushResponse", "pull-response": "PullResponse"}
LENIENT = {"PushRequest": "PushRequestLenient", "PullResponse": "PullResponseLenient"}
for p in sorted((ROOT / "examples/batches").glob("*.json")):
    where, doc = str(p.relative_to(ROOT)), load(p)
    shape = SHAPES.get(p.name.split(".")[0])
    if not shape:
        fail(f"{where}: file name must start with one of {sorted(SHAPES)}")
        continue
    must_validate("batch.schema.json", doc, where)
    must_validate(f"batch.schema.json#/$defs/{shape}", doc, where)
    if shape in LENIENT:
        must_validate(f"batch.schema.json#/$defs/{LENIENT[shape]}", doc, where)
    if shape == "PushRequest":
        hlcs = [o["hlc"] for o in doc["ops"]]
        if hlcs != sorted(set(hlcs)) or len({o["device_id"] for o in doc["ops"]}) != 1:
            fail(f"{where}: ops must come from one device in strictly ascending hlc")
    for o in doc.get("ops", []):
        check_op_form(o, where)

# ------------------------------------------------------------------ examples/invalid
for p in sorted((ROOT / "examples/invalid").glob("*.json")):
    doc = load(p)
    if not problems(doc["schema"], doc["instance"]):
        fail(f"{p.relative_to(ROOT)}: expected to be rejected by {doc['schema']} ({doc['why']})")


# ------------------------------------------------------------------ op-level rules beyond JSON Schema (C-6)
def nul_pointer(v) -> str | None:  # noqa: ANN001
    """Some JSON Pointer to a string or member name containing U+0000 (diagnostic only), else None.
    Iterative, like the depth limit: an op may be 500 deep (SYNC.md 2.1)."""
    stack = [(v, "")]
    while stack:
        node, ptr = stack.pop()
        if isinstance(node, str):
            if "\x00" in node:
                return ptr
        elif isinstance(node, dict):
            for k, x in reversed(list(node.items())):
                kp = ptr + "/" + k.replace("~", "~0").replace("/", "~1")
                if "\x00" in k:
                    return kp
                stack.append((x, kp))
        elif isinstance(node, list):
            stack.extend((x, f"{ptr}/{i}") for i, x in reversed(list(enumerate(node))))
    return None


# examples/invalid-codec: schema-VALID ops that an op-level rule of SYNC.md 2.1 rejects ("rule": "nul").
for p in sorted((ROOT / "examples/invalid-codec").glob("*.json")):
    doc, where = load(p), str(p.relative_to(ROOT))
    if doc.get("rule") != "nul":
        fail(f"{where}: unknown rule {doc.get('rule')!r}")
    for prob in problems(doc["schema"], doc["instance"]):
        fail(f"{where}: must be schema-valid (it shows a rule beyond the schema): {prob}")
    if nul_pointer(doc["instance"]) is None:
        fail(f"{where}: expected U+0000 somewhere ({doc['why']})")
for p, op in example_ops.items():
    if nul_pointer(op) is not None:
        fail(f"{p.relative_to(ROOT)}: contains U+0000")

# ------------------------------------------------------------------ duplicate keys (C-2)
# examples/raw-push/<name>.body.txt is a raw push body (may repeat keys, which a JSON file loaded with
# json.load would silently collapse); <name>.expected.json says what SYNC.md 5.2 "Parsing the body"
# decides: {"http_status": 400} or {"http_status": 200, "per_op": ["schema_invalid" | "checked", ...]}
# where "checked" means the element goes on to the ordinary per-op checks of 5.3.
class _Obj(list):
    """An object as its member list, in document order, duplicates kept."""


def _pairs(pairs):
    return _Obj(pairs)


def _has_dup(v) -> bool:
    if isinstance(v, _Obj):
        keys = [k for k, _ in v]
        return len(keys) != len(set(keys)) or any(_has_dup(x) for _, x in v)
    if isinstance(v, list):
        return any(_has_dup(x) for x in v)
    return False


def _plain(v):
    if isinstance(v, _Obj):
        return {k: _plain(x) for k, x in v}
    if isinstance(v, list):
        return [_plain(x) for x in v]
    return v


MAX_OP_DEPTH, MAX_DOCUMENT_DEPTH = 500, 512  # SYNC.md 2.1 (C-5)
sys.setrecursionlimit(max(sys.getrecursionlimit(), 20 * MAX_DOCUMENT_DEPTH))  # _has_dup / _plain recurse per level


def depth(v) -> int:
    """Arrays and objects on the deepest path: scalar 0, [] 1, {"a":[1]} 2 (SYNC.md 2.1). Iterative."""
    best, stack = 0, [(v, 0)]
    while stack:
        x, d = stack.pop()
        if isinstance(x, (_Obj, list, dict)):
            d += 1
            best = max(best, d)
            kids = [c for _, c in x] if isinstance(x, _Obj) else list(x.values()) if isinstance(x, dict) else x
            stack.extend((c, d) for c in kids)
    return best


def push_body_verdict(text: str) -> dict:
    try:
        body = json.loads(text, object_pairs_hook=_pairs)
    except ValueError:
        return {"http_status": 400}
    if depth(body) > MAX_DOCUMENT_DEPTH:
        return {"http_status": 400}
    if not isinstance(body, _Obj) or _has_dup(_Obj([(k, None) for k, _ in body])):
        return {"http_status": 400}
    if problems("batch.schema.json#/$defs/PushRequestLenient", _plain(body)):
        return {"http_status": 400}
    ops = dict(body)["ops"]
    seen: dict[str, object] = {}
    per_op = []
    for el in ops:
        ids = [x for k, x in el if k == "op_id"]
        if len(ids) != 1:
            return {"http_status": 400}
        dup = _has_dup(el) or depth(el) > MAX_OP_DEPTH  # both: schema_invalid, before op_too_large
        key = json.dumps(el) if dup else json.dumps(_plain(el), sort_keys=True)
        if ids[0] in seen and seen[ids[0]] != key:
            return {"http_status": 400}
        seen[ids[0]] = key
        per_op.append("schema_invalid" if dup else "checked")
    return {"http_status": 200, "per_op": per_op}


for p in sorted((ROOT / "examples/raw-push").glob("*.body.txt")):
    exp_path = p.with_name(p.name.removesuffix(".body.txt") + ".expected.json")
    if not exp_path.exists():
        fail(f"{p.relative_to(ROOT)}: missing {exp_path.name}")
        continue
    got = push_body_verdict(p.read_text(encoding="utf-8"))
    if got != load(exp_path):
        fail(f"{p.relative_to(ROOT)}: SYNC.md 5.2 duplicate-key rules give {got}, expected {load(exp_path)}")


# ------------------------------------------------------------------ scenarios
def run(ops, device: bool) -> Replica:
    r = Replica(device=device)
    for op in ops:
        r.apply(op)
    return r


def interleave(log, rng: random.Random) -> list:
    """A random order that keeps each device's ops in log order, applies an op only when the rows it
    references exist, and re-delivers some ops (idempotency)."""
    streams: dict[str, list] = {}
    for op in log:
        streams.setdefault(op["device_id"], []).append(op)
    probe, order, done = Replica(device=True), [], []
    while any(streams.values()):
        dev = rng.choice([d for d, s in streams.items() if s])
        op = streams[dev][0]
        trial = copy.deepcopy(probe)
        try:
            trial.apply(op)
        except MissingRow:
            continue
        probe = trial
        order.append(streams[dev].pop(0))
        done.append(op)
        if rng.random() < 0.3:
            again = rng.choice(done)
            if again["op_type"] != "media.content_published":  # relies on seq order (SYNC.md 7.6)
                order.append(again)
                probe.apply(again)
    return order


def check_server(sc: dict, where: str) -> None:
    """Derive the server's verdicts from the device ops (SYNC.md 8.1) and check the rejection codes."""
    log = sc["log"]
    derived = [o for o in log if o["device_id"] == SERVER and o["caused_by_op_id"] and o["op_type"] in VERDICT_OPS]
    hlcs = iter([o["hlc"] for o in derived])
    card_ids = iter([o["payload"]["id"] for o in derived if o["op_type"] == "card.created"])
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op),
                 mint=lambda: next(hlcs, "ffffffffffff-ffff-" + SERVER), new_card_id=lambda: next(card_ids, "?"))
    for op in log:
        if op["device_id"] == SERVER and op["caused_by_op_id"] and op["op_type"] in VERDICT_OPS:
            continue  # must be produced by the server below
        clean = {k: v for k, v in op.items() if k not in ("seq", "caused_by_op_id")}
        if op["device_id"] == SERVER:
            srv.accept(clean)  # e.g. confusable.suggested from a job: minted by the server, not pushed
            continue
        srv.pt = wall(op["hlc"])
        res = srv.push([clean])["results"][0]
        if res["status"] != "accepted":
            fail(f"{where}: the model server does not accept seq {op['seq']} ({res})")
            return
    for want, got in zip(log, srv.log):
        keys = [k for k in want if not (k == "op_id" and want["device_id"] == SERVER)]
        if any(want[k] != got[k] for k in keys):
            fail(f"{where}: seq {want['seq']}: the server's derived verdict differs: expected "
                 f"{want['op_type']} {want['payload']}, model emits {got['op_type']} {got['payload']}")
            return
    if len(srv.log) != len(log):
        fail(f"{where}: the model server emits {len(srv.log)} log entries, the scenario has {len(log)}")
    now = wall(log[-1]["hlc"])
    for r in sc.get("rejected", []):
        if r["code"] in CHECKED_CODES:
            judge = srv
            if "after_seq" in r:  # the server state at the time of the push (scenario.schema.json)
                judge = Server(schema_problems=srv.schema_problems)
                judge.r = run(log[: r["after_seq"]], False)
            got = judge.validate(r["op"], r.get("received_at", now))
            if got != r["code"]:
                fail(f"{where}: rejected op {r['op']['op_id']}: expected {r['code']}, model says {got}")


SCENARIO_FILES = sorted((ROOT / "examples/scenarios").glob("*.json")) + \
    sorted((ROOT / "examples/remint-scenarios").glob("*.json"))
for p in SCENARIO_FILES:
    where, sc = str(p.relative_to(ROOT)), load(p)
    must_validate("scenario.schema.json", sc, where)
    if sc["id"] != p.stem:
        fail(f"{where}: id must equal the file name")
    log = sc["log"]
    seen: dict[str, int] = {}
    last_hlc: dict[str, str] = {}
    for n, op in enumerate(log, start=1):
        check_op_form(op, f"{where} seq {n}")
        if op["seq"] != n:
            fail(f"{where}: seq must run 1..n without gaps (got {op['seq']} at position {n})")
        if op["op_id"] in seen:
            fail(f"{where}: duplicate op_id at seq {n}")
        if op["caused_by_op_id"] is not None and op["caused_by_op_id"] not in seen:
            fail(f"{where} seq {n}: caused_by_op_id must name an earlier op")
        if op["caused_by_op_id"] is not None and op["device_id"] != SERVER:
            fail(f"{where} seq {n}: only server-emitted ops have a cause")
        if op["hlc"] <= last_hlc.get(op["device_id"], ""):
            fail(f"{where} seq {n}: a device's ops must reach the log in ascending hlc")
        if op["device_id"] == SERVER and any(op["hlc"] <= o["hlc"] for o in log[: n - 1]):
            fail(f"{where} seq {n}: a server-minted hlc must exceed every hlc the server has already seen")
        seen[op["op_id"]], last_hlc[op["device_id"]] = n, op["hlc"]
    for r in sc.get("rejected", []):
        if r["code"] == "schema_invalid":
            bad = problems("op.schema.json", r["op"]) or r["op"]["hlc"][18:] != r["op"]["device_id"]
            if not bad:
                fail(f"{where}: rejected op {r['op']['op_id']} is said to be schema_invalid but is valid")
        else:
            must_validate("op.schema.json", r["op"], f"{where} rejected op")
    try:
        server, device = run(log, False), run(log, True)
    except MissingRow as e:
        fail(f"{where}: log is not applicable in seq order: {e}")
        continue
    exp = sc["expected"]
    for table, rows in exp.get("rows", {}).items():
        for partial in rows:
            row = server.find(table, partial)
            if row is None:
                fail(f"{where}: expected a {table} row {partial}")
                continue
            for col, want in partial.items():
                if row.get(col, "<missing column>") != want:
                    fail(f"{where}: {table} {col}: expected {want!r}, model has {row.get(col)!r}")
    for table, keys in exp.get("absent", {}).items():
        for key in keys:
            if any(all(r.get(k) == v for k, v in key.items()) for r in server.t[table].values()):
                fail(f"{where}: {table} row {key} must not exist")
    for cid, want in exp.get("replay_sets", {}).items():
        if server.replay_set(cid) != want:
            fail(f"{where}: replay set of {cid}: expected {want}, model has {server.replay_set(cid)}")
    canon = device.projection()
    sp = server.projection()
    sp["parse_override"] = {}
    if sp != canon:
        fail(f"{where}: server and device replicas differ")
    if run(log + log, True).t != device.t:
        fail(f"{where}: replaying the log a second time changed the state")
    if sc["order_free"]:
        rng = random.Random(sc["id"])
        for k in range(PERMUTATIONS):
            order = interleave(log, rng)
            got = run(order, True).projection()
            if got != canon:
                diff = [(t, key) for t in canon for key in set(canon[t]) | set(got[t]) if canon[t].get(key) != got[t].get(key)]
                fail(f"{where}: interleaving {k} does not converge; differs at {diff[:3]}; order = {[o['seq'] for o in order]}")
                break

    check_server(sc, where)
    if "remint" in sc:  # SYNC.md 10.4: the selection and the re-minted times follow from the originals
        rm = sc["remint"]
        rej = {r["op"]["op_id"]: r for r in sc.get("rejected", [])}
        logged = {o["op_id"]: o for o in log}
        E: list = []
        W: set = set()
        flat: list = []  # (E op id, minted payload) in mint order: a re-keyed removal mints two (6a)
        if "creations_from" in rm:  # step 1, recomputed from the rejected rows and their codes
            C, E, R, U, D, W, payloads = remint_select([{"op": r["op"], "last_error": r["code"]} for r in rej.values()])
            ids = lambda rows: [r["op"]["op_id"] for r in rows]  # noqa: E731
            if ids(E) != rm["creations_from"]:
                fail(f"{where}: re-mint E is {ids(E)}, the scenario says {rm['creations_from']}")
            if not set(rm["from"]) <= set(ids(R)):
                fail(f"{where}: remint.from names an op that step 1 does not put into R")
            deleted = sorted(C + D + U + E + R, key=lambda r: r["op"]["hlc"])
            gone = [i for i in dict.fromkeys(ids(deleted)) if i not in set(ids(E)) | set(rm["from"])]
            if gone != rm["discarded"]:
                fail(f"{where}: Re-mint discards {gone}, the scenario says {rm['discarded']}")
            flat = [(i, q) for i in rm["creations_from"] for q in payloads.get(i, [])]
            if len(rm["creations_to"]) != len(flat):
                fail(f"{where}: creations_to must list the {len(flat)} ops step 6a mints for creations_from")
                E, flat = [], []
            for (i, q), j in zip(flat, rm["creations_to"]):
                if logged[j]["op_type"] != rej[i]["op"]["op_type"] or logged[j]["payload"] != q:
                    fail(f"{where}: re-minted {j} must be the step-6a op for {i} (same type; payload as sent, or the "
                         f"e:<seq> item of a window note re-keyed n:<note>, a re-keyed removal also on e:<seq>)")
        old = [rej[i]["op"]["payload"] for i in rm["from"]]
        items = [(wall(r["op"]["hlc"]), FLOOR_MS) for r in E] + \
            [(q["reviewed_at"], FLOOR_MS if q["card_id"] in W else server.t["card"][q["card_id"]]["created_at"])
             for q in old]
        if items:
            _, times = remint_times(items, rm["offset_ms"], rm["now"])
            t_of = dict(zip([r["op"]["op_id"] for r in E], times))
            times = [t_of[i] for i, _ in flat] + times[len(E):]  # one t' per minted op
            minted = [wall(logged[j]["hlc"]) for j in rm.get("creations_to", [])]
            # mint_at(t') (3.2): t', or just above the previous HLC when t' is not above it
            want = [max([t] + minted[:k]) for k, t in enumerate(times[:len(minted)])] + times[len(minted):]
            got = minted + [logged[i]["payload"]["reviewed_at"] for i in rm["to"]]
            if got != want and not (minted and got[1:] == want[1:] and got[0] >= want[0]):
                fail(f"{where}: re-minted times do not follow SYNC.md 10.4 step 5: expected {want}, log has {got}")
        if any(logged[i]["payload"]["id"] == q["id"] for i, q in zip(rm["to"], old)):
            fail(f"{where}: a re-minted review must have a new id")


# ------------------------------------------------------------------ restore story (SYNC.md 12.5)
def restore_story(legacy: bool):
    A, B = "01931f3e-0000-7000-8000-0000000000a1", "01931f3e-0000-7000-8000-0000000000b2"
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op))
    a, b = Device(A, legacy), Device(B, legacy)
    t = [0x0193a1b2c000]

    def tick(*who):
        t[0] += 1000
        for x in who:
            x.pt = t[0]

    tick(srv, a, b)
    note, card = a.new_id(1), a.new_id(2)
    a.make("note.created", {"id": note, "note_type_id": "00000000-0000-7000-8000-000000000101", "entry_seq": 1358280})
    a.make("card.created", {"id": card, "note_id": note, "kind": "meaning"})
    a.sync(srv)
    b.sync(srv)
    snap = srv.backup()  # covers seq 1..2
    reviews = []
    for _ in range(5):  # A's reviews: seq 3..7, acked and pulled
        tick(srv, a, b)
        rid = a.new_id(3)
        reviews.append(rid)
        a.make("review.logged", {"id": rid, "card_id": card, "reviewed_at": a.pt, "utc_offset_min": 540,
                                 "review_type": "scheduled", "rating": 3, "button": "good"})
    a.sync(srv)
    hlc_before = srv.hlc_last
    tick(srv, a, b)
    srv.restore(snap)  # back to seq 2; B's next ops reuse seq 3..
    if srv.hlc_last[:17] < hlc_before[:17]:
        fail("restore story: the server's hlc went backwards")
    tick(srv, a, b)
    b.make("note.updated", {"id": note, "set": {"tags": ["restored"]}})
    b.make("card.suspended", {"id": card})
    b.sync(srv)
    for _ in range(2):
        tick(srv, a, b)
        a.sync(srv)
        b.sync(srv)
    return srv, a, b, reviews


srv, a, b, reviews = restore_story(legacy=True)
if all(r in srv.r.t["review_log"] for r in reviews):
    fail("restore story: under the attempt-1 rules A's reviews should be lost (the story no longer shows the bug)")
srv, a, b, reviews = restore_story(legacy=False)
lost = [r for r in reviews if r not in srv.r.t["review_log"]]
if lost:
    fail(f"restore story: A's reviews {lost} were lost")
sp = srv.r.projection()
sp["parse_override"] = {}
for d in (a, b):
    if d.r.projection() != sp:
        fail(f"restore story: device {d.id[-2:]} does not converge with the server")
    if d.rebuild_pending or any(r["state"] != "acked" for r in d.outbox):
        fail(f"restore story: device {d.id[-2:]} has unfinished work")
if srv.r.t["card"][next(iter(srv.r.t["card"]))]["suspended"] is not True:
    fail("restore story: B's edit after the restore is missing")


# ------------------------------------------------------------------ restore below pull_seq (SYNC.md 12.5.1, C-10)
def restore_below_pull_seq_story(reset_keeps_pull_seq: bool):
    """A pulled to seq 10 (3..10 server-minted REST edits); the server is restored to seq 2; A's one
    pending review gets seq 3 < the old pull_seq. Returns (srv, a, review_id); raises Livelock."""
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op))
    a = Device("01931f3e-0000-7000-8000-0000000000a1", reset_keeps_pull_seq=reset_keeps_pull_seq)
    t = [0x0193a1b2c000]

    def tick():
        t[0] += 1000
        srv.pt = a.pt = t[0]

    tick()
    note, card = a.new_id(1), a.new_id(2)
    a.make("note.created", {"id": note, "note_type_id": VOCAB, "entry_seq": 1358280})
    a.make("card.created", {"id": card, "note_id": note, "kind": "meaning"})
    a.sync(srv)
    snap = srv.backup()  # covers seq 1..2
    a.sync(srv)  # learns durable_seq 2: purges both rows
    if a.outbox or a.pull_seq != 2:
        fail("restore below pull_seq: setup expects A's rows purged at pull_seq 2")
    for k in range(8):  # REST edits minted by the server: seq 3..10
        tick()
        srv._emit("note.updated", {"id": note, "set": {"tags": [f"rest{k}"]}}, None)
    tick()
    a.sync(srv)
    if a.pull_seq != 10 or srv.seq != 10:
        fail(f"restore below pull_seq: setup expects A at pull_seq 10 (got {a.pull_seq}, server {srv.seq})")
    tick()
    srv.restore(snap)  # back to seq 2, new epoch
    tick()
    rid = a.new_id(3)
    a.make("review.logged", {"id": rid, "card_id": card, "reviewed_at": a.pt, "utc_offset_min": 540,
                             "review_type": "scheduled", "rating": 3, "button": "good"})
    a.sync(srv)  # push: seq 3 in epoch E2 -> log reset -> re-push (duplicate, seq 3) -> rebuild
    return srv, a, rid


try:
    restore_below_pull_seq_story(reset_keeps_pull_seq=True)
    fail("restore below pull_seq: the pre-C-10 log reset should livelock (the story no longer shows the bug)")
except Livelock:
    pass
try:
    srv, a, rid = restore_below_pull_seq_story(reset_keeps_pull_seq=False)
except Livelock as e:
    fail(f"restore below pull_seq: {e}")
else:
    if a.rebuild_pending:
        fail("restore below pull_seq: rebuild_pending must be cleared after one sync cycle")
    if [o["seq"] for o in srv.log if o["op_type"] == "review.logged" and o["payload"]["id"] == rid] != [3]:
        fail("restore below pull_seq: the review must be in the log exactly once, at seq 3")
    if a.pull_seq != srv.seq or srv.seq != 3:
        fail(f"restore below pull_seq: A must have pulled the new log to its end (pull_seq {a.pull_seq}, server {srv.seq})")
    if [r["state"] for r in a.outbox] != ["acked"] or a.outbox[0]["acked_seq"] != 3:
        fail("restore below pull_seq: the review row must be acked at seq 3 and kept (3 > durable_seq 2)")
    sp = srv.r.projection()
    sp["parse_override"] = {}
    if a.r.projection() != sp:
        fail("restore below pull_seq: A does not converge with the server")
    if rid not in srv.r.t["review_log"]:
        fail("restore below pull_seq: A's review is missing on the server")
    a.make("card.suspended", {"id": next(iter(a.r.t["card"]))})  # reviewing / minting possible again
    a.sync(srv)


# ------------------------------------------------------------------ re-mint story (SYNC.md 10.4, 12.6)
Y2K = 946_684_800_000  # the phone's RTC resets to 2000-01-01


def remint_story():
    A, B = "01931f3e-0000-7000-8000-0000000000a1", "01931f3e-0000-7000-8000-0000000000b2"
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op))
    a, b = Device(A, guard=False), Device(B)  # A: a client without the 3.4 guard
    real = [0x0193a1b2c000]

    def tick(ms, skew_a=0):
        real[0] += ms
        srv.pt, b.pt, a.pt = real[0], real[0], real[0] + skew_a

    tick(1000)
    cards = []
    for k in range(20):
        note, card = a.new_id(1), a.new_id(2)
        a.make("note.created", {"id": note, "note_type_id": "00000000-0000-7000-8000-000000000101",
                                "entry_seq": 1358280 + k})
        a.make("card.created", {"id": card, "note_id": note, "kind": "meaning"})
        cards.append(card)
    a.sync(srv)
    b.sync(srv)
    skew = Y2K - (real[0] + 1000)  # from here A's clock reads 2000-01-01 and runs on from there
    probe = copy.deepcopy(a)
    probe.guard, probe.pt = True, Y2K
    try:
        probe.make("review.logged", {"id": probe.new_id(3), "card_id": cards[0], "reviewed_at": Y2K,
                                     "utc_offset_min": 540, "review_type": "scheduled", "rating": 3, "button": "good"})
        fail("re-mint story: the 3.4 guard must refuse to mint at a clock before 2020")
    except RuntimeError:
        pass
    truth, undone = {}, None  # original review id -> real time
    for day in range(7):  # a week offline, 100 reviews a day
        for k in range(100):
            tick(600_000, skew)
            rid = a.new_id(3)
            a.make("review.logged", {"id": rid, "card_id": cards[k % 19], "reviewed_at": a.pt, "utc_offset_min": 540,
                                     "review_type": "scheduled", "rating": 3, "button": "good"})
            truth[rid] = real[0]
            if day == 2 and k == 5:
                a.make("review.undone", {"review_id": rid})
                undone = rid
        tick(1000, skew)
        b.make("review.logged", {"id": b.new_id(3), "card_id": cards[19], "reviewed_at": b.pt, "utc_offset_min": 0,
                                 "review_type": "scheduled", "rating": 3, "button": "good"})
        b.make("card.suspended", {"id": cards[day]})
        b.sync(srv)
        tick(DAY_MS - 100 * 600_000 - 1000, skew)
    a.sync(srv)  # online, clock still wrong: every review is refused
    codes = {r["last_error"] for r in a.outbox if r["op"]["op_type"] == "review.logged" and r["state"] == "rejected"}
    if codes != {"clock_behind"} or a.remint_offset is None:
        fail(f"re-mint story: expected 700 clock_behind rejections and a recorded offset, got {codes}")
    tick(1000)  # the user fixes the clock
    a.sync(srv)
    a.remint()
    tick(1000)
    a.sync(srv)
    b.sync(srv)
    return srv, a, b, cards, truth, undone


srv, a, b, cards, truth, undone = remint_story()
sp = srv.r.projection()
sp["parse_override"] = {}
for d in (a, b):
    if d.r.projection() != sp:
        fail(f"re-mint story: device {d.id[-2:]} does not converge with the server")
    if d.rebuild_pending or any(r["state"] != "acked" for r in d.outbox):
        fail(f"re-mint story: device {d.id[-2:]} has unfinished work")
if any(rid in srv.r.t["review_log"] or rid in a.r.t["review_log"] for rid in truth):
    fail("re-mint story: an original (clock-rejected) review id reached a replica")
want = sorted(t for rid, t in truth.items() if rid != undone)
got = sorted(t for c in cards[:19] for _, t in srv.r.replay_inputs(c))
if got != want:
    fail(f"re-mint story: replay does not use the re-minted times ({len(got)} reviews, {len(want)} expected)")
for c in cards[:19]:
    times = [t for _, t in a.r.replay_inputs(c)]
    if times != sorted(times):
        fail("re-mint story: re-minted reviews are not in their original order")
if sum(len(srv.r.replay_set(c)) for c in cards[:19]) != 699:
    fail("re-mint story: expected 699 re-minted reviews (700 minus the undone one)")
for c in cards[:19]:
    times = [t for _, t in srv.r.replay_inputs(c)]
    if times != sorted(times):
        fail("re-mint story: a replay set is not in reviewed_at order (SYNC.md 9.1)")
# The second path of the review: a review dated 25 h before its card (creator ahead, reviewer behind) is accepted.
late = copy.deepcopy(b)
late.guard, late.pt = False, srv.r.t["card"][cards[0]]["created_at"] - 25 * 3_600_000
op = late.make("review.logged", {"id": late.new_id(3), "card_id": cards[0], "reviewed_at": late.pt,
                                 "utc_offset_min": 0, "review_type": "scheduled", "rating": 3, "button": "good"})
if srv.validate(op, srv.pt) is not None:
    fail("re-mint story: a review dated before its card must be accepted (no card-age check, SYNC.md 3.4)")
# The shift itself: one delta per batch; spacing kept unless the batch does not fit.
T26 = 1_780_000_000_000  # a 2026 instant
if remint_times([(Y2K, T26), (Y2K + 5, T26)], 0, T26 + 100) != (T26 - Y2K, [T26, T26 + 5]):
    fail("remint_times: an offset of 0 must shift the batch up to the card, keeping the spacing")
if remint_times([(0, 50), (200, 50)], 0, 100)[1] != [50, 100]:
    fail("remint_times: a batch wider than the window is clamped per review")
if remint_times([(500, 400), (510, 400)], -10_000, 300)[1] != [300, 300]:
    fail("remint_times: a card created ahead of now bounds the review at now")

# ------------------------------------------------------------------ clock-ahead re-mint story (SYNC.md 10.4, 12.7)
def ahead_story():
    """Phone A runs 10 days ahead (undetectable offline) and is offline for a week. It adds 100 words
    (note.created, card.created, note.updated), reviews their cards daily, undoes one review and
    suspends a card the server already has. Phone B meanwhile adds one of the same words, edits it
    later than A did, and reviews it. Re-mint must keep A's notes, cards, edits and reviews."""
    A, B = "01931f3e-0000-7000-8000-0000000000a1", "01931f3e-0000-7000-8000-0000000000b2"
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op))
    a, b = Device(A), Device(B)
    real = [0x0193a1b2c000]
    skew = 10 * DAY_MS  # more than the week offline + 24 h, so every op of the week is refused

    def tick(ms, skew_a=0):
        real[0] += ms
        srv.pt, b.pt, a.pt = real[0], real[0], real[0] + skew_a

    tick(1000)
    old_note, old_card = a.new_id(1), a.new_id(2)
    a.make("note.created", {"id": old_note, "note_type_id": VOCAB, "entry_seq": 1000000})
    a.make("card.created", {"id": old_card, "note_id": old_note, "kind": "meaning"})
    a.sync(srv)
    b.sync(srv)
    tick(1000, skew)  # A's clock jumps 10 days ahead; G1/G2 cannot see it
    notes, cards, born = [], [], {}
    shared = 2000000 + 42  # B adds this word too
    for k in range(100):
        tick(1000, skew)
        note, card = a.new_id(1), a.new_id(2)
        a.make("note.created", {"id": note, "note_type_id": VOCAB, "entry_seq": 2000000 + k})
        a.make("card.created", {"id": card, "note_id": note, "kind": "meaning"})
        a.make("note.updated", {"id": note, "set": {"fields": {"meaning": f"a{k}", "reading": f"r{k}"}}})
        notes.append(note)
        cards.append(card)
        born[note] = real[0]
    a.make("card.suspended", {"id": old_card})  # an edit of a row the server has: discard only
    truth, undone, b_note, b_card = {}, None, b.new_id(1), b.new_id(2)
    for day in range(7):
        for k in range(100):
            tick(600_000, skew)
            rid = a.new_id(3)
            a.make("review.logged", {"id": rid, "card_id": cards[k], "reviewed_at": a.pt, "utc_offset_min": 540,
                                     "review_type": "scheduled", "rating": 3, "button": "good"})
            truth[rid] = real[0]
            if day == 4 and k == 7:
                a.make("review.undone", {"review_id": rid})
                undone = rid
        tick(1000, skew)
        if day == 1:
            b.make("note.created", {"id": b_note, "note_type_id": VOCAB, "entry_seq": shared})
            b.make("card.created", {"id": b_card, "note_id": b_note, "kind": "meaning"})
        if day == 2:
            b.make("note.updated", {"id": b_note, "set": {"fields": {"meaning": "from B"}}})
        if day >= 1:
            b.make("review.logged", {"id": b.new_id(3), "card_id": b_card, "reviewed_at": b.pt, "utc_offset_min": 0,
                                     "review_type": "scheduled", "rating": 3, "button": "good"})
        b.sync(srv)
        tick(DAY_MS - 100 * 600_000 - 1000, skew)
    a.sync(srv)  # online, clock still 10 days ahead
    codes: dict = {}
    for r in a.outbox:
        if r["state"] == "rejected":
            codes.setdefault((r["op"]["op_type"], r["last_error"]), 0)
            codes[(r["op"]["op_type"], r["last_error"])] += 1
    want = {("note.created", "clock_ahead"): 100, ("card.created", "unknown_reference"): 100,
            ("note.updated", "unknown_reference"): 100, ("card.suspended", "clock_ahead"): 1,
            ("review.logged", "unknown_reference"): 700, ("review.undone", "unknown_reference"): 1}
    if codes != want or a.remint_offset != -skew:
        fail(f"clock-ahead story: unexpected rejections {codes} / offset {a.remint_offset}")
    tick(1000)  # the user fixes the clock
    a.sync(srv)
    a.remint()
    tick(1000)
    a.sync(srv)
    b.sync(srv)
    return srv, a, b, notes, cards, born, truth, undone, (old_card, shared, b_note, b_card)


srv, a, b, notes, cards, born, truth, undone, (old_card, shared, b_note, b_card) = ahead_story()
sp = srv.r.projection()
sp["parse_override"] = {}
for d in (a, b):
    if d.r.projection() != sp:
        fail(f"clock-ahead story: device {d.id[-2:]} does not converge with the server")
    if d.rebuild_pending or any(r["state"] != "acked" for r in d.outbox):
        fail(f"clock-ahead story: device {d.id[-2:]} has unfinished work")
N, K = srv.r.t["note"], srv.r.t["card"]
for k, (n, c) in enumerate(zip(notes, cards)):
    if n not in N or c not in K:
        fail(f"clock-ahead story: offline-created note/card {k} was lost")
        break
    if N[n]["entry_seq"] == shared:
        if N[n]["merged_into_id"] != b_note or K[c]["merged_into_id"] != b_card:
            fail("clock-ahead story: the re-minted duplicate must merge into B's note and card (SYNC.md 8)")
        continue
    if N[n]["fields"] != {"meaning": f"a{k}", "reading": f"r{k}"} or N[n]["deleted_at"] is not None:
        fail(f"clock-ahead story: the edit of offline-created note {k} was lost")
        break
    if N[n]["created_at"] != born[n]:
        fail(f"clock-ahead story: note {k} created_at {N[n]['created_at']} should be the real time {born[n]}")
        break
if N[b_note]["fields"] != {"meaning": "from B", "reading": "r42"}:
    fail(f"clock-ahead story: survivor fields {N[b_note]['fields']}: B's later edit must win, A's other field forwarded")
if K[old_card]["suspended"]:
    fail("clock-ahead story: the clock_ahead edit of an existing card must be discarded")
want = sorted(t for rid, t in truth.items() if rid != undone)
got = sorted(t for c in cards if not K[c]["merged_into_id"] for _, t in srv.r.replay_inputs(c)) + \
    [t for rid, t in srv.r.replay_inputs(b_card) if srv.r.t["review_log"][rid]["device_id"] == a.id]
if sorted(got) != want:
    fail(f"clock-ahead story: replay does not use the real review times ({len(got)} reviews, {len(want)} expected)")
times = [t for _, t in srv.r.replay_inputs(b_card)]
if times != sorted(times) or len(times) != 7 + 6:
    fail("clock-ahead story: the survivor's replay set must interleave A's and B's reviews by reviewed_at")
if any(rid in srv.r.t["review_log"] for rid in truth):
    fail("clock-ahead story: an original (rejected) review id reached the server")


# ------------------------------------------------------------------ backups toggled off and on (SYNC.md 6.2, C-8)
def backups_toggle_story():
    """durable_seq never decreases within an epoch when backups are disabled and enabled again; a
    restore of a backup older than durable_seq starts a new epoch and is the only way ops get lost."""
    srv = Server(schema_problems=lambda op: problems("op.schema.json", op))
    a = Device("01931f3e-0000-7000-8000-0000000000a1")
    srv.pt = a.pt = 0x0193a1b2c000
    seen = []

    def add(k):
        srv.pt += 1000
        a.pt = srv.pt
        n = a.new_id(1)
        a.make("note.created", {"id": n, "note_type_id": VOCAB, "entry_seq": 4000000 + k})
        a.sync(srv)
        seen.append(srv._meta()["durable_seq"])

    add(0)
    old = srv.backup()  # verified backup at seq 1
    add(1)
    srv.start(backups_disabled=True)  # restart with backups disabled: durable_seq = high_water
    if srv.durable_seq != srv.seq:
        fail("backups toggle: disabling backups must raise durable_seq to high_water_seq at startup")
    add(2)
    add(3)
    if srv.durable_seq != srv.seq or any(r["state"] == "acked" for r in a.outbox):
        fail("backups toggle: with backups disabled every commit is durable and the device purges")
    srv.start(backups_disabled=False)  # enabled again: nothing is lowered
    add(4)
    if seen != sorted(seen) or srv.durable_seq != 4:
        fail(f"backups toggle: durable_seq must never decrease within an epoch ({seen})")
    srv.backup()
    if srv.durable_seq != 5:
        fail("backups toggle: the first verified backup after re-enabling raises durable_seq again")
    if srv.durable_seq <= old[3]:
        fail("backups toggle: the old backup must count as older than durable_seq (restore must warn)")
    epoch = srv.log_epoch
    srv.restore(old)  # the operator confirmed the warning (6.3 restore script step 1)
    if srv.log_epoch == epoch:
        fail("backups toggle: every restore starts a new log_epoch")


backups_toggle_story()

if errors:
    print("\n".join(errors), file=sys.stderr)
    print(f"\nFAILED: {len(errors)} problem(s)", file=sys.stderr)
    sys.exit(1)
n_inv = len(list((ROOT / "examples/invalid").glob("*.json")))
n_sc = len(SCENARIO_FILES)
n_raw = len(list((ROOT / "examples/raw-push").glob("*.body.txt")))
print(f"ok: {len(schemas)} schemas, {len(index)} op types, {len(example_ops)} op examples, "
      f"{n_inv} invalid examples rejected (+{len(list((ROOT / 'examples/invalid-codec').glob('*.json')))} beyond the schema), {n_raw} raw push bodies (duplicate keys, depth), "
      f"{len({pat for _, pat in SCHEMA_PATTERNS})} schema patterns (ECMA-262), {n_sc} scenarios")
