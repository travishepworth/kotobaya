#!/usr/bin/env python3
"""Checks the sync contract. Run through ../check.sh.

1. Every *.schema.json is a valid JSON Schema 2020-12 document.
2. Every op type in ops/index.json has a payload schema, at least one example,
   a branch in op.schema.json and a section in OPS.md; nothing extra exists.
3. Every example validates: examples/ops (op.schema.json), examples/batches
   (batch.schema.json and the specific shape named by the file), examples/scenarios
   (scenario.schema.json). Every examples/invalid instance is REJECTED.
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
   and converges without loss under log_epoch / durable_seq.
"""
from __future__ import annotations

import copy
import json
import random
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

sys.path.insert(0, str(Path(__file__).parent))
from model import (CHECKED_CODES, SERVER, VERDICT_OPS, Device, MissingRow, Replica,  # noqa: E402
                   Server, wall)

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


def validator(ref: str) -> Draft202012Validator:
    """ref is relative to the contract root, e.g. 'batch.schema.json#/$defs/PushRequest'."""
    return Draft202012Validator({"$ref": (ROOT / "x").as_uri().rsplit("/", 1)[0] + "/" + ref}, registry=registry)


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
            got = srv.validate(r["op"], r.get("received_at", now))
            if got != r["code"]:
                fail(f"{where}: rejected op {r['op']['op_id']}: expected {r['code']}, model says {got}")


for p in sorted((ROOT / "examples/scenarios").glob("*.json")):
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

if errors:
    print("\n".join(errors), file=sys.stderr)
    print(f"\nFAILED: {len(errors)} problem(s)", file=sys.stderr)
    sys.exit(1)
n_inv = len(list((ROOT / "examples/invalid").glob("*.json")))
n_sc = len(list((ROOT / "examples/scenarios").glob("*.json")))
print(f"ok: {len(schemas)} schemas, {len(index)} op types, {len(example_ops)} op examples, "
      f"{n_inv} invalid examples rejected, {n_sc} scenarios")
