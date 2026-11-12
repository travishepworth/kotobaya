"""Reference model of a replica's apply function (SYNC.md sections 7-9).

NOT normative and not production code: SYNC.md and OPS.md are the contract. This
model exists so that check.sh can prove the scenarios in examples/scenarios/ are
self-consistent (the expected state really follows from the rules, and the
order-independence claims hold). If the model and the documents disagree, the
documents win and the model has a bug.

Three parts:

- `Replica`: a *pure replica*. It applies the ops it is given (sections 7-9). It does
  not compute FSRS state: instead of the card cache it exposes each card's replay set
  (SYNC.md 9.1).
- `Server`: a Replica plus what only the server does: validation (a subset of the
  rejection codes, `CHECKED_CODES`), the collision verdicts that emit `note.merged` /
  `card.merged` / `card.created` (SYNC.md 8.1), the log with `seq`, `log_epoch`,
  `durable_seq`, backup and restore (SYNC.md 5.5, 6.2).
- `Device`: a Replica plus the outbox and the device's sync procedure: push, pull,
  garbage collection of acked rows, log reset and rebuild (SYNC.md 5.4, 6, 10.3).

Rows use the device representation: instants are epoch milliseconds, arrays are
JSON arrays.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

SERVER = "00000000-0000-7000-8000-000000000001"
DEFAULT_PRESET = "00000000-0000-7000-8000-000000000010"
BUILTIN_NOTE_TYPES = {
    "00000000-0000-7000-8000-000000000101": "vocab", "00000000-0000-7000-8000-000000000102": "kanji",
    "00000000-0000-7000-8000-000000000103": "phrase", "00000000-0000-7000-8000-000000000104": "grammar",
    "00000000-0000-7000-8000-000000000105": "sentence", "00000000-0000-7000-8000-000000000106": "custom",
}
TABLES = ["note_type", "note", "card", "review_log", "word_status", "deck", "deck_item", "confusable",
          "grammar_point", "sentence_grammar_span", "settings", "fsrs_params", "media", "parse_override"]
PRIMARY_KEY = {
    "review_log": ["id"], "word_status": ["entry_seq"], "deck_item": ["deck_id", "item_key"],
    "confusable": ["note_a_id", "note_b_id", "reason"], "settings": ["key"],
    "sentence_grammar_span": ["sentence_id", "grammar_point_id", "start_offset", "end_offset", "origin"],
}
# Registers that are never forwarded from a merge loser to its survivor.
NOT_FORWARDED = {"note": {"entry_seq", "deleted"}, "card": {"deleted"}}
FEEDS_SCHEDULE = {"scheduled", "learning", "manual"}
PRESET_DEFAULTS = {"desired_retention": 0.9, "maximum_interval_days": 36500, "learning_steps_s": [60, 600],
                   "relearning_steps_s": [600], "fuzz": True, "easy_days": [1] * 7}


INDEX = {m["op_type"]: m for m in json.loads(
    (Path(__file__).resolve().parent.parent / "ops/index.json").read_text(encoding="utf-8"))["ops"]}
DAY_MS = 86_400_000
FLOOR_MS = 1_577_836_800_000  # 2020-01-01T00:00:00Z, SYNC.md 3.4
EPSILON_MS = 60_000  # slack on the clock_ahead hlc comparison, SYNC.md 3.4
WARN_MS = 300_000  # 5 minutes: clock warning; re-mint needs the clock within this, SYNC.md 3.4, 10.4
CLOCK_CODES = ("clock_ahead", "clock_behind")
# Rejection codes the model's Server decides. Others (device_mismatch, invalid_value, invalid_target,
# immutable) need auth or per-op rules the model does not implement; the model accepts those ops.
CHECKED_CODES = ("unsupported_op", "schema_invalid", "server_only", "unknown_reference", "clock_ahead",
                 "clock_behind")
VERDICT_OPS = {"note.merged", "card.merged", "card.created"}  # what 8.1 may emit
# Client creation ops and the table of the row they create (re-mint, SYNC.md 10.4).
CREATES = {"note_type.created": "note_type", "note.created": "note", "card.created": "card", "deck.created": "deck",
           "grammar.created": "grammar_point", "fsrs_params.created": "fsrs_params"}


class MissingRow(Exception):
    """The op references a row this replica does not have (cannot happen in seq order)."""


def wall(hlc: str) -> int:
    """Wall-clock milliseconds of an HLC = the op's wall time."""
    return int(hlc[:12], 16)


def hlc_mint(last: str, pt: int, device: str) -> str:
    """mint() of SYNC.md 3.2. `last` is a full HLC string ('' = (0, 0)); only its (ms, ctr) part counts."""
    ms, ctr = (int(last[:12], 16), int(last[13:17], 16)) if last else (0, 0)
    if pt > ms:
        ms, ctr = pt, 0
    elif ctr < 0xFFFF:
        ctr += 1
    else:
        ms, ctr = ms + 1, 0
    return f"{ms:012x}-{ctr:04x}-{device}"


def hlc_observe(last: str, h: str) -> str:
    """observe() of SYNC.md 3.2."""
    return h if not last or h[:17] > last[:17] else last


def remint_times(reviews: list, offset: int, now: int) -> tuple[int, list]:
    """The re-mint shift of SYNC.md 10.4. `reviews` = [(reviewed_at, card_created_at)] in original hlc
    order; returns (delta, new reviewed_at values). One shift for the whole batch keeps the spacing;
    the per-review clamp only bites when the batch does not fit between its cards and now."""
    low = [min(created, now) for _, created in reviews]
    d_hi = now - max(t for t, _ in reviews)
    d_lo = max(lo - t for (t, _), lo in zip(reviews, low))
    delta = min(max(offset, d_lo), d_hi)
    return delta, [min(max(t + delta, lo), now) for (t, _), lo in zip(reviews, low)]


def refs(op: dict) -> set:
    """Ids of the rows whose absence makes the op unknown_reference (OPS.md "Server rejects"; SYNC.md 10.4)."""
    t, p = op["op_type"], op["payload"]
    named = {"note.created": ("note_type_id", "grammar_point_id"), "card.created": ("note_id", "preset_id"),
             "card.preset_set": ("id", "preset_id"), "review.logged": ("card_id",), "review.undone": ("review_id",),
             "deck.item_put": ("deck_id",), "deck.item_removed": ("deck_id",),
             "confusable.confirmed": ("note_a_id", "note_b_id"), "confusable.rejected": ("note_a_id", "note_b_id"),
             "grammar.span_put": ("grammar_point_id",), "grammar.span_removed": ("grammar_point_id",)}
    keys = named.get(t, () if t in CREATES or "id" not in p else ("id",))
    out = {p[k] for k in keys if p.get(k)}
    if t.startswith("deck.item_") and p["item_key"].startswith("n:"):
        out.add(p["item_key"][2:])
    return out


def row_ids(op: dict) -> set:
    """Ids in the identity of the row the op writes (SYNC.md 10.4, "window row"): the parts of its
    `entity_id`, plus the note id of an `n:<id>` deck item key. So a deck item `D|n:N`, a confusable
    `A|B|reason` and a manual span `S|G|...` name every row id their identity contains."""
    parts = set(op["entity_id"].split("|"))
    return parts | {x[2:] for x in parts if x.startswith("n:")}


def window_seq(op: dict):
    """The entry_seq an op addresses by entry rather than by row id (SYNC.md 10.4 step 1c,
    "window-derived"): the seq of an `e:<seq>` deck item key, or of a word status op; else None."""
    t, p = op["op_type"], op["payload"]
    if t in ("deck.item_put", "deck.item_removed") and p["item_key"].startswith("e:"):
        return int(p["item_key"][2:])
    if t in ("word.status_set", "word.status_cleared"):
        return p["entry_seq"]
    return None


def remint_select(rows: list) -> tuple[list, list, list, list, list, set, dict]:
    """Step 1 of Re-mint (SYNC.md 10.4). `rows` = the pending_op rows in state `rejected`
    ({"op", "last_error"}), in any order. Returns (C, E, R, U, D, W, payloads):
    C the clock-rejected rows; E the creations, the ops that write a window row and the
    window-derived ops, in step-1 order; R the reviews to re-mint; U the undos among `rows` that undo
    a review of R (the caller adds the undos of R's reviews in other states); D the unknown_reference
    ops that depend on a window row but write a row other replicas can have (discarded, never
    re-minted); W the ids of the rows created in the window; payloads: op_id -> the payload step 6a
    mints for each op of E (the original one, except `e:<seq>` deck items, re-keyed `n:<note>`)."""
    W: set = set()
    C: list = []
    E: list = []
    R: list = []
    U: list = []
    D: list = []
    ordered = sorted(rows, key=lambda r: r["op"]["hlc"])
    for r in ordered:  # 1a/1b: one pass in ascending original hlc
        op, code = r["op"], r["last_error"]
        t = op["op_type"]
        if code in CLOCK_CODES:
            C.append(r)
            if t == "review.logged":
                R.append(r)
            elif t in CREATES:
                E.append(r)
                W.add(op["payload"]["id"])
            elif row_ids(op) & W:
                E.append(r)
        elif code == "unknown_reference" and refs(op) & (W | {x["op"]["payload"]["id"] for x in R}):
            if t == "review.logged":
                R.append(r)
            elif t == "review.undone":
                U.append(r)
            elif t in CREATES or row_ids(op) & W:
                E.append(r)
                if t in CREATES:
                    W.add(op["payload"]["id"])
            else:
                D.append(r)
    # 1c: window-derived ops: an e:<seq> deck item or a word status whose seq is the entry of a note
    # created in the window. The first such note.created (in hlc order) owns the seq.
    owner: dict = {}
    for r in E:
        o = r["op"]
        if o["op_type"] == "note.created" and o["payload"].get("entry_seq") is not None:
            owner.setdefault(o["payload"]["entry_seq"], o)
    taken = {id(r) for r in E + R}
    for r in ordered:
        if id(r) in taken or r not in C:
            continue
        seq = window_seq(r["op"])
        if seq in owner:
            E.append(r)
    note_hlc = lambda r: owner[window_seq(r["op"])]["hlc"] if window_seq(r["op"]) in owner else ""  # noqa: E731
    E.sort(key=lambda r: (max(r["op"]["hlc"], note_hlc(r)), r["op"]["hlc"] < note_hlc(r), r["op"]["hlc"]))  # after its note
    # 6a payloads. An e:<seq> item_put into a deck not in W is re-keyed n:<owner> iff the owner note is
    # live at re-mint time (no note.deleted of it in E: a window note is known to nobody else, so only
    # this device can have deleted it). An item_removed e:<seq> is re-keyed only if E holds a re-keyed
    # item_put of the same (deck, e:<seq>); otherwise it keeps e:<seq> and removes the shared row.
    deleted = {r["op"]["payload"]["id"] for r in E if r["op"]["op_type"] == "note.deleted"}
    rekeyed: set = set()
    payloads = {}
    for r in E:
        o = r["op"]
        p = copy.deepcopy(o["payload"])
        seq = window_seq(o)
        if o["op_type"].startswith("deck.item_") and p["item_key"].startswith("e:") and seq in owner \
                and p["deck_id"] not in W:
            note = owner[seq]["payload"]["id"]
            if o["op_type"] == "deck.item_put" and note not in deleted:
                rekeyed.add((p["deck_id"], seq))
                p["item_key"] = "n:" + note
            elif o["op_type"] == "deck.item_removed" and (p["deck_id"], seq) in rekeyed:
                p["item_key"] = "n:" + note
        payloads[o["op_id"]] = p
    return C, E, R, U, D, W, payloads


def entity_id(op_type: str, payload: dict) -> str:
    return "|".join(part[1:-1] if part.startswith("'") else str(payload[part])
                    for part in INDEX[op_type]["entity_id"])


def is_empty(v) -> bool:
    return v is None or v == "" or v == [] or v == {}


class Replica:
    def __init__(self, device: bool = True):
        self.device = device  # devices skip parse_override.*
        self.t: dict[str, dict] = {name: {} for name in TABLES}
        for nt_id, base in BUILTIN_NOTE_TYPES.items():  # seed JSON (fields, templates) elided in the model
            self.t["note_type"][nt_id] = {"id": nt_id, "base_type": base, "builtin": True, "name": base.capitalize(),
                                          "fields": [], "templates": {}, "css": "", "created_at": 0, "updated_at": 0,
                                          "deleted_at": None, "clocks": {}}
        self.t["fsrs_params"][DEFAULT_PRESET] = {
            "id": DEFAULT_PRESET, "name": "Default", **copy.deepcopy(PRESET_DEFAULTS),
            "fsrs_version": None, "weights": None, "weights_version": 0, "optimized_at": None,
            "optimizer_stats": None, "created_at": 0, "updated_at": 0, "deleted_at": None, "clocks": {}}

    # ------------------------------------------------------------ helpers
    def need(self, table: str, key):
        row = self.t[table].get(key)
        if row is None:
            raise MissingRow(f"{table} {key}")
        return row

    @staticmethod
    def _put(row, reg, value, h):
        if "." in reg:
            col, k = reg.split(".", 1)
            if value is None:
                row[col].pop(k, None)
            else:
                row[col][k] = value
        elif reg == "deleted":
            row["deleted_at"] = wall(h)
        elif reg == "source_sentence":
            row["source_sentence"], row["source_sentence_id"], row["source_media_id"] = value
        else:
            row[reg] = value

    @staticmethod
    def _get(row, reg):
        if "." in reg:
            col, k = reg.split(".", 1)
            return row[col].get(k)
        if reg == "source_sentence":
            return (row["source_sentence"], row["source_sentence_id"], row["source_media_id"])
        return row[reg]  # 'deleted' is never read

    def write(self, row, reg, value, h) -> bool:
        """The clock rule (SYNC.md 7.1)."""
        if h > row["clocks"].get(reg, ""):
            self._put(row, reg, copy.deepcopy(value), h)
            row["clocks"][reg] = h
            row["updated_at"] = max(row["updated_at"], wall(h))
            return True
        return False

    def reg_write(self, table, key, reg, value, h):
        """A register write addressed to a row, forwarded if the row is a merge loser (SYNC.md 8.3)."""
        row = self.need(table, key)
        self.write(row, reg, value, h)
        if row.get("merged_into_id") and reg not in NOT_FORWARDED.get(table, set()):
            self.write(self.need(table, row["merged_into_id"]), reg, value, h)

    def create(self, table, key, fixed: dict, regs: list, objs: dict, h):
        """Creation rule (SYNC.md 7.2). regs: (register, initial value, column default). objs: column -> object."""
        if key in self.t[table]:
            return
        row = {**fixed, "created_at": wall(h), "updated_at": wall(h), "deleted_at": None, "clocks": {}}
        for reg, value, default in regs:
            self._put(row, reg, copy.deepcopy(value), h)
            if value != default:
                row["clocks"][reg] = h
        for col, obj in objs.items():
            row[col] = {}
            for k, v in obj.items():
                if not is_empty(v):
                    row[col][k] = copy.deepcopy(v)
                    row["clocks"][f"{col}.{k}"] = h
        self.t[table][key] = row

    def set_registers(self, table, key, setmap: dict, h, objs=()):
        self.need(table, key)
        setmap = dict(setmap)
        if "source_sentence" in setmap:
            src = (setmap.pop("source_sentence"), setmap.pop("source_sentence_id"), setmap.pop("source_media_id"))
            self.reg_write(table, key, "source_sentence", src, h)
        for name, value in setmap.items():
            if name in objs:
                for k, v in value.items():
                    self.reg_write(table, key, f"{name}.{k}", v, h)
            else:
                self.reg_write(table, key, name, value, h)

    def merge(self, table, loser_id, survivor_id, h):
        """note.merged / card.merged (SYNC.md 8.2)."""
        loser, survivor = self.need(table, loser_id), self.need(table, survivor_id)
        if loser["merged_into_id"]:
            return  # already merged: no-op
        if survivor["merged_into_id"]:
            survivor = self.need(table, survivor["merged_into_id"])
        for reg, clock in sorted(loser["clocks"].items()):
            if reg not in NOT_FORWARDED[table]:
                self.write(survivor, reg, self._get(loser, reg), clock)
        if loser["deleted_at"] is None:
            loser["deleted_at"] = wall(h)
        loser["merged_into_id"] = survivor["id"]
        loser["updated_at"] = max(loser["updated_at"], wall(h))
        for row in self.t[table].values():
            if row["merged_into_id"] == loser_id:
                row["merged_into_id"] = survivor["id"]

    def lww_row(self, table, key, h, row):
        """Whole-row last-writer-wins on a single hlc column."""
        old = self.t[table].get(key)
        if old is None or h > old["hlc"]:
            self.t[table][key] = row

    # ------------------------------------------------------------ apply
    def apply(self, op: dict):
        t, p, h = op["op_type"], op["payload"], op["hlc"]
        if t.startswith("parse_override.") and self.device:
            return
        getattr(self, "op_" + t.replace(".", "_"))(p, h, p.get, op)

    # note_type
    def op_note_type_created(self, p, h, g, op):
        self.create("note_type", p["id"], {"id": p["id"], "base_type": p["base_type"], "builtin": False},
                    [("name", p["name"], None), ("fields", p["fields"], None), ("css", g("css", ""), "")],
                    {"templates": p["templates"]}, h)

    def op_note_type_updated(self, p, h, g, op):
        self.set_registers("note_type", p["id"], p["set"], h, objs=("templates",))

    def op_note_type_deleted(self, p, h, g, op):
        self.reg_write("note_type", p["id"], "deleted", None, h)

    # note
    def op_note_created(self, p, h, g, op):
        nt = self.need("note_type", p["note_type_id"])
        if g("grammar_point_id"):
            self.need("grammar_point", p["grammar_point_id"])
        src = (g("source_sentence", ""), g("source_sentence_id"), g("source_media_id"))
        self.create("note", p["id"],
                    {"id": p["id"], "note_type_id": p["note_type_id"], "type": nt["base_type"],
                     "language": g("language", "ja"), "kanji_literal": g("kanji_literal"),
                     "grammar_point_id": g("grammar_point_id"), "origin": g("origin", "manual"),
                     "created_by_device_id": op["device_id"], "merged_into_id": None},
                    [("entry_seq", g("entry_seq"), None), ("tags", g("tags", []), []),
                     ("mnemonic", g("mnemonic", ""), ""), ("source_sentence", src, ("", None, None))],
                    {"fields": g("fields", {})}, h)

    def op_note_updated(self, p, h, g, op):
        self.set_registers("note", p["id"], p["set"], h, objs=("fields",))

    def op_note_deleted(self, p, h, g, op):
        self.reg_write("note", p["id"], "deleted", None, h)

    def op_note_merged(self, p, h, g, op):
        self.merge("note", p["loser_id"], p["survivor_id"], h)

    # card
    def op_card_created(self, p, h, g, op):
        self.need("note", p["note_id"])
        self.need("fsrs_params", g("preset_id", DEFAULT_PRESET))
        self.create("card", p["id"], {"id": p["id"], "note_id": p["note_id"], "kind": p["kind"], "merged_into_id": None},
                    [("preset_id", g("preset_id", DEFAULT_PRESET), DEFAULT_PRESET),
                     ("suspended", g("suspended", False), False), ("buried_until", g("buried_until"), None),
                     ("flags", g("flags", 0), 0)], {}, h)

    def op_card_suspended(self, p, h, g, op):
        self.reg_write("card", p["id"], "suspended", True, h)

    def op_card_unsuspended(self, p, h, g, op):
        self.reg_write("card", p["id"], "suspended", False, h)

    def op_card_buried(self, p, h, g, op):
        self.reg_write("card", p["id"], "buried_until", p["until"], h)

    def op_card_unburied(self, p, h, g, op):
        self.reg_write("card", p["id"], "buried_until", None, h)

    def op_card_flag_set(self, p, h, g, op):
        self.reg_write("card", p["id"], "flags", p["flags"], h)

    def op_card_preset_set(self, p, h, g, op):
        self.need("fsrs_params", p["preset_id"])
        self.reg_write("card", p["id"], "preset_id", p["preset_id"], h)

    def op_card_deleted(self, p, h, g, op):
        self.reg_write("card", p["id"], "deleted", None, h)

    def op_card_merged(self, p, h, g, op):
        self.merge("card", p["loser_id"], p["survivor_id"], h)

    # review
    def op_review_logged(self, p, h, g, op):
        self.need("card", p["card_id"])
        if p["id"] in self.t["review_log"]:
            return
        self.t["review_log"][p["id"]] = {
            "id": p["id"], "card_id": p["card_id"], "device_id": op["device_id"], "hlc": g("replay_hlc") or h,
            "reviewed_at": p["reviewed_at"], "utc_offset_min": p["utc_offset_min"], "review_type": p["review_type"],
            "rating": p["rating"], "button": g("button"), "duration_ms": g("duration_ms"),
            "manual_action": g("manual_action"), "manual_due_at": g("manual_due_at"), "sentence_id": g("sentence_id"),
            "import_source": g("import_source"), "undone_at": None, "undone_hlc": None}

    def op_review_undone(self, p, h, g, op):
        row = self.need("review_log", p["review_id"])
        if row["undone_hlc"] is None or h < row["undone_hlc"]:  # the EARLIEST undo wins
            row["undone_hlc"], row["undone_at"] = h, wall(h)

    # word_status
    def _word(self, seq, status, origin, h):
        row = self.t["word_status"].setdefault(seq, {
            "entry_seq": seq, "status": "new", "auto_status": None, "override_status": None, "override_hlc": None,
            "override_origin": None, "updated_at": wall(h)})
        if row["override_hlc"] is None or h > row["override_hlc"]:
            row.update(override_status=status, override_origin=origin, override_hlc=h,
                       status=status or row["auto_status"] or "new", updated_at=max(row["updated_at"], wall(h)))

    def op_word_status_set(self, p, h, g, op):
        self._word(p["entry_seq"], p["status"], g("origin", "manual"), h)

    def op_word_status_cleared(self, p, h, g, op):
        self._word(p["entry_seq"], None, None, h)

    # deck
    def op_deck_created(self, p, h, g, op):
        self.create("deck", p["id"],
                    {"id": p["id"], "type": p["type"], "language": g("language", "ja"), "media_id": g("media_id"),
                     "list_key": g("list_key"), "content_version": 0, "content_blob_hash": None},
                    [("name", p["name"], None), ("priority", g("priority", 0), 0), ("enabled", g("enabled", True), True),
                     ("new_cards_per_day", g("new_cards_per_day"), None), ("reviews_per_day", g("reviews_per_day"), None),
                     ("query", g("query"), None)], {"settings": g("settings", {})}, h)

    def op_deck_updated(self, p, h, g, op):
        self.set_registers("deck", p["id"], p["set"], h, objs=("settings",))

    def op_deck_deleted(self, p, h, g, op):
        self.reg_write("deck", p["id"], "deleted", None, h)

    def _published(self, table, key, p, h, extra=(), allow_equal=False):
        row = self.need(table, key)
        if p["content_version"] > row["content_version"] or (allow_equal and p["content_version"] == row["content_version"]):
            for col in ("content_version", "content_blob_hash", *extra):
                row[col] = p.get(col)
            row["updated_at"] = max(row["updated_at"], wall(h))

    def op_deck_content_published(self, p, h, g, op):
        self._published("deck", p["deck_id"], p, h)

    def _item(self, p, h, removed):
        self.need("deck", p["deck_id"])
        key, kind, value = p["item_key"], p["item_key"][0], p["item_key"][2:]
        if kind == "n":
            self.need("note", value)
        self.lww_row("deck_item", (p["deck_id"], key), h, {
            "deck_id": p["deck_id"], "item_key": key, "entry_seq": int(value) if kind == "e" else None,
            "note_id": value if kind == "n" else None, "position": 0 if removed else p["position"],
            "occurrence_count": 0, "added_at": wall(h), "deleted_at": wall(h) if removed else None, "hlc": h})

    def op_deck_item_put(self, p, h, g, op):
        self._item(p, h, False)

    def op_deck_item_removed(self, p, h, g, op):
        self._item(p, h, True)

    # confusable
    def _confusable(self, p, h, status):
        self.need("note", p["note_a_id"]), self.need("note", p["note_b_id"])
        key = (p["note_a_id"], p["note_b_id"], p["reason"])

        def rank(s):  # a user decision beats a suggestion whatever the clocks
            return 0 if s == "suggested" else 1

        row = self.t["confusable"].get(key)
        if row is None:
            row = self.t["confusable"][key] = {**p, "status": None, "hlc": "", "created_at": wall(h), "updated_at": 0}
        row["created_at"] = min(row["created_at"], wall(h))
        if row["status"] is None or (rank(status), h) > (rank(row["status"]), row["hlc"]):
            row.update(status=status, hlc=h, updated_at=wall(h))

    def op_confusable_suggested(self, p, h, g, op):
        self._confusable(p, h, "suggested")

    def op_confusable_confirmed(self, p, h, g, op):
        self._confusable(p, h, "confirmed")

    def op_confusable_rejected(self, p, h, g, op):
        self._confusable(p, h, "rejected")

    # grammar
    def op_grammar_created(self, p, h, g, op):
        self.create("grammar_point", p["id"], {"id": p["id"], "language": "ja"},
                    [("title", p["title"], None), ("pattern", g("pattern", ""), ""), ("meaning", g("meaning", ""), ""),
                     ("explanation", g("explanation", ""), ""), ("notes", g("notes", ""), ""), ("jlpt", g("jlpt"), None),
                     ("detection_rules", g("detection_rules", []), []), ("related_ids", g("related_ids", []), []),
                     ("links", g("links", []), []), ("is_stub", g("is_stub", False), False)], {}, h)

    def op_grammar_updated(self, p, h, g, op):
        self.set_registers("grammar_point", p["id"], p["set"], h)

    def op_grammar_deleted(self, p, h, g, op):
        self.reg_write("grammar_point", p["id"], "deleted", None, h)

    def _span(self, p, h, removed):
        self.need("grammar_point", p["grammar_point_id"])
        key = (p["sentence_id"], p["grammar_point_id"], p["start_offset"], p["end_offset"], "manual")
        self.lww_row("sentence_grammar_span", key, h, {
            "sentence_id": key[0], "grammar_point_id": key[1], "start_offset": key[2], "end_offset": key[3],
            "origin": "manual", "media_id": None if removed else p.get("media_id"), "rule_index": None,
            "created_at": wall(h), "deleted_at": wall(h) if removed else None, "hlc": h})

    def op_grammar_span_put(self, p, h, g, op):
        self._span(p, h, False)

    def op_grammar_span_removed(self, p, h, g, op):
        self._span(p, h, True)

    # settings
    def op_settings_set(self, p, h, g, op):
        self.lww_row("settings", p["key"], h, {"key": p["key"], "value": copy.deepcopy(p["value"]), "hlc": h,
                                               "updated_at": wall(h)})

    # fsrs_params
    def op_fsrs_params_created(self, p, h, g, op):
        self.create("fsrs_params", p["id"],
                    {"id": p["id"], "fsrs_version": None, "weights": None, "weights_version": 0, "optimized_at": None,
                     "optimizer_stats": None},
                    [("name", p["name"], None)] + [(c, g(c, d), d) for c, d in PRESET_DEFAULTS.items()], {}, h)

    def op_fsrs_params_updated(self, p, h, g, op):
        self.set_registers("fsrs_params", p["id"], p["set"], h)

    def op_fsrs_params_deleted(self, p, h, g, op):
        self.reg_write("fsrs_params", p["id"], "deleted", None, h)

    def op_fsrs_params_published(self, p, h, g, op):
        row = self.need("fsrs_params", p["id"])
        if p["weights_version"] > row["weights_version"]:
            row.update(weights_version=p["weights_version"], fsrs_version=p["fsrs_version"],
                       weights=list(p["weights"]), optimized_at=p["optimized_at"],
                       optimizer_stats=g("optimizer_stats"), updated_at=max(row["updated_at"], wall(h)))

    # media
    def op_media_created(self, p, h, g, op):
        self.create("media", p["id"],
                    {"id": p["id"], "language": "ja", "source": p["source"], "jpdb_id": g("jpdb_id"),
                     "parse_status": "pending", "parse_error": None, "content_version": 0, "content_blob_hash": None},
                    [("title", p["title"], None), ("kind", p["kind"], None),
                     ("cover_blob_hash", g("cover_blob_hash"), None)], {}, h)

    def op_media_updated(self, p, h, g, op):
        self.set_registers("media", p["id"], p["set"], h)

    def op_media_deleted(self, p, h, g, op):
        self.reg_write("media", p["id"], "deleted", None, h)

    def op_media_content_published(self, p, h, g, op):
        self._published("media", p["media_id"], p, h, extra=("parse_status", "parse_error"), allow_equal=True)

    # parse_override (server replica only)
    def op_parse_override_put(self, p, h, g, op):
        row = self.t["parse_override"].get(p["id"])
        if row is None:
            row = self.t["parse_override"][p["id"]] = {"id": p["id"], "created_at": wall(h), "deleted_at": None, "hlc": ""}
        row["created_at"] = min(row["created_at"], wall(h))
        if h > row["hlc"]:
            row.update(media_id=g("media_id"), sentence_id=g("sentence_id"), match_text=p["match_text"],
                       tokens=copy.deepcopy(p["tokens"]), note=g("note", ""), hlc=h, updated_at=wall(h))

    def op_parse_override_deleted(self, p, h, g, op):
        row = self.need("parse_override", p["id"])
        row["deleted_at"] = wall(h) if row["deleted_at"] is None else min(row["deleted_at"], wall(h))
        if h > row["hlc"]:
            row.update(hlc=h, updated_at=wall(h))

    # ------------------------------------------------------------ reading
    def replay_inputs(self, card_id) -> list:
        """(id, reviewed_at) of the replay set, in replay order: what FSRS replay reads (SYNC.md 9.3)."""
        return [(rid, self.t["review_log"][rid]["reviewed_at"]) for rid in self.replay_set(card_id)]

    def replay_set(self, card_id) -> list:
        """Ids of the reviews that determine the card's schedule, in replay order (SYNC.md 9.1)."""
        rows = [r for r in self.t["review_log"].values()
                if (r["card_id"] == card_id or self.t["card"][r["card_id"]]["merged_into_id"] == card_id)
                and r["undone_at"] is None and r["review_type"] in FEEDS_SCHEDULE]
        return [r["id"] for r in sorted(rows, key=lambda r: (r["reviewed_at"], r["hlc"], r["id"]))]

    def projection(self) -> dict:
        """What must be equal on converged replicas (SYNC.md section 11)."""
        out = {}
        for table, rows in self.t.items():
            out[table] = {}
            for key, row in rows.items():
                row = copy.deepcopy(row)
                if row.get("merged_into_id"):
                    row = {k: row[k] for k in ("id", "merged_into_id", "deleted_at")}
                if table == "word_status":
                    row.pop("updated_at")
                out[table][str(key)] = row
        out["replay_sets"] = {cid: self.replay_set(cid) for cid, c in self.t["card"].items()
                              if not c["merged_into_id"]}
        return out

    def find(self, table, partial: dict):
        pk = PRIMARY_KEY.get(table, ["id"])
        hits = [r for r in self.t[table].values() if all(r.get(k) == partial[k] for k in pk)]
        return hits[0] if hits else None


# ====================================================================== server
class Server:
    """The server replica: validation, collision verdicts, the log (SYNC.md 5, 6, 8.1).

    new_card_id(), mint() and new_op_id() may be injected so that a test can make the server
    'happen' to pick the ids and clocks a scenario's log shows; by default it makes its own."""

    def __init__(self, schema_problems=None, new_card_id=None, mint=None, new_op_id=None):
        self.r = Replica(device=False)
        self.log: list[dict] = []
        self.by_op_id: dict[str, int] = {}
        self.seq = 0
        self.epoch_n = 1
        self.log_epoch = self._epoch_id(1)
        self.durable_seq = 0
        self.backups_disabled = False  # server configuration (SYNC.md 6.2, "Backups disabled")
        self.hlc_last = ""
        self.pt = 0
        self._n = 0
        self.schema_problems = schema_problems
        self._new_card_id = new_card_id or (lambda: self._uuid(0x0c))
        self._mint = mint or self._own_mint
        self._new_op_id = new_op_id or (lambda: self._uuid(0x0e))

    @staticmethod
    def _epoch_id(n: int) -> str:
        return f"0193a1b2-00ee-7000-8000-{n:012x}"

    def _uuid(self, kind: int) -> str:
        self._n += 1
        return f"0193a1b2-5{kind:03x}-7000-8000-{self._n:012x}"

    def _own_mint(self) -> str:
        return hlc_mint(self.hlc_last, self.pt, SERVER)

    # ---------------------------------------------------------------- validation (5.3)
    def validate(self, op: dict, now: int):
        t, p = op.get("op_type"), op.get("payload", {})
        if t not in INDEX or op.get("op_version") != INDEX[t]["op_version"]:
            return "unsupported_op"
        if (self.schema_problems and self.schema_problems(op)) or op["entity_type"] != INDEX[t]["entity_type"] \
                or op["entity_id"] != entity_id(t, p) or op["hlc"][18:] != op["device_id"]:
            return "schema_invalid"
        if INDEX[t]["emitter"] == "server" or op["device_id"] == SERVER \
                or (t == "deck.created" and p.get("type") in ("media", "list")) \
                or (t == "review.logged" and (p.get("import_source") is not None or p.get("replay_hlc") is not None)) \
                or (t == "word.status_set" and p.get("origin") == "jpdb_import"):
            return "server_only"
        try:
            copy.deepcopy(self.r).apply(op)
        except MissingRow:
            return "unknown_reference"
        w = wall(op["hlc"])
        if w > now + DAY_MS + EPSILON_MS or (t == "review.logged" and p["reviewed_at"] > now + DAY_MS):
            return "clock_ahead"
        if w < FLOOR_MS or (t == "review.logged" and p["reviewed_at"] < FLOOR_MS):
            return "clock_behind"
        return None

    # ---------------------------------------------------------------- log
    def _log(self, op: dict, cause) -> dict:
        self.seq += 1
        entry = {**op, "seq": self.seq, "caused_by_op_id": cause}
        self.log.append(entry)
        self.by_op_id[op["op_id"]] = self.seq
        if self.backups_disabled:  # 6.2: every commit counts as durable, in the same transaction
            self.durable_seq = max(self.durable_seq, self.seq)
        return entry

    def _emit(self, op_type: str, payload: dict, cause: str) -> dict:
        h = self._mint()
        self.hlc_last = hlc_observe(self.hlc_last, h)
        op = {"op_id": self._new_op_id(), "op_type": op_type, "op_version": 1,
              "entity_type": INDEX[op_type]["entity_type"], "entity_id": entity_id(op_type, payload),
              "device_id": SERVER, "hlc": h, "payload": payload}
        self.r.apply(op)
        return self._log(op, cause)

    def accept(self, op: dict) -> int:
        """Step 3 of SYNC.md 5.2 for an op that passed validation (or one the server minted itself)."""
        self.hlc_last = hlc_observe(self.hlc_last, op["hlc"])
        before = copy.deepcopy(self.r.t["card"]) if op["op_type"] == "card.created" else None
        self.r.apply(op)
        entry = self._log(op, None)
        self._verdicts(op, before)
        return entry["seq"]

    def push(self, ops: list) -> dict:
        """POST /sync/push (SYNC.md 5.2). Returns a PushResponse."""
        results = []
        for op in ops:
            if op["op_id"] in self.by_op_id:
                results.append({"op_id": op["op_id"], "status": "duplicate", "seq": self.by_op_id[op["op_id"]]})
                continue
            code = self.validate(op, self.pt)
            if code:
                results.append({"op_id": op["op_id"], "status": "rejected",
                                "error": {"code": code, "message": code}})
                continue
            results.append({"op_id": op["op_id"], "status": "accepted", "seq": self.accept(op)})
        return {"results": results, **self._meta()}

    def pull(self, since: int, limit: int = 500) -> dict:
        """GET /sync/pull (SYNC.md 6). Returns a PullResponse."""
        page = [o for o in self.log if o["seq"] > since][:limit]
        more = bool(page) and page[-1]["seq"] < self.seq
        return {"ops": copy.deepcopy(page), "next_since": page[-1]["seq"] if page else since, "has_more": more,
                **self._meta()}

    def _meta(self) -> dict:
        return {"high_water_seq": self.seq, "server_time": self.pt, "log_epoch": self.log_epoch,
                "durable_seq": self.durable_seq}

    # ---------------------------------------------------------------- backup / restore (6.2)
    def backup(self):
        """A verified backup of everything committed so far; durable_seq advances to it."""
        snap = copy.deepcopy((self.r, self.log, self.by_op_id, self.seq, self.hlc_last))
        self.durable_seq = max(self.durable_seq, self.seq)  # 6.3 step 3: greatest(), never lowered
        return snap

    def start(self, backups_disabled: bool):
        """A (re)start with the given configuration (6.3 server startup step 3): with backups disabled,
        durable_seq is raised to high_water_seq; it is never lowered when backups are enabled again."""
        self.backups_disabled = backups_disabled
        if backups_disabled:
            self.durable_seq = max(self.durable_seq, self.seq)

    def restore(self, snap):
        """Restore a backup: new epoch, durable_seq = the backup's seq, clock never backwards."""
        self.r, self.log, self.by_op_id, self.seq, restored_last = copy.deepcopy(snap)
        self.epoch_n += 1
        self.log_epoch = self._epoch_id(self.epoch_n)
        self.durable_seq = self.seq
        floor = f"{self.pt + DAY_MS:012x}-0000-{SERVER}"  # no op minted before the loss can be above this
        last = restored_last
        for o in self.log:
            last = hlc_observe(last, o["hlc"])
        self.hlc_last = hlc_observe(last, floor)

    # ---------------------------------------------------------------- verdicts (8.1)
    def _live_card(self, note_id: str, kind: str, exclude=None):
        hits = sorted(c["id"] for c in self.r.t["card"].values()
                      if c["note_id"] == note_id and c["kind"] == kind and c["deleted_at"] is None
                      and c["id"] != exclude)
        return hits[0] if hits else None

    def _merge_card_into_note(self, card: dict, survivor_note: str, cause: str):
        target = self._live_card(survivor_note, card["kind"])
        if target is None:
            target = self._new_card_id()
            self._emit("card.created", {"id": target, "note_id": survivor_note, "kind": card["kind"]}, cause)
        self._emit("card.merged", {"loser_id": card["id"], "survivor_id": target}, cause)

    def _verdicts(self, op: dict, cards_before):
        t, p, h = op["op_type"], op["payload"], op["hlc"]
        notes, cards = self.r.t["note"], self.r.t["card"]
        if t == "note.created" or (t == "note.updated" and "entry_seq" in p["set"]
                                   and notes[p["id"]]["clocks"].get("entry_seq") == h):
            n = notes[p["id"]]
            if n["deleted_at"] is not None or n["merged_into_id"]:
                return
            for col in ("entry_seq", "kanji_literal", "grammar_point_id"):
                if n.get(col) is None:
                    continue
                w = [x for x in notes.values() if x["id"] != n["id"] and x["deleted_at"] is None
                     and x.get(col) == n[col]]
                if w:
                    self._emit("note.merged", {"loser_id": n["id"], "survivor_id": w[0]["id"]}, op["op_id"])
                    for c in sorted((c for c in cards.values() if c["note_id"] == n["id"]
                                     and c["deleted_at"] is None and not c["merged_into_id"]),
                                    key=lambda c: (c["kind"], c["id"])):
                        self._merge_card_into_note(c, w[0]["id"], op["op_id"])
                    return
        if t == "card.created" and p["id"] not in cards_before:
            c, n = cards[p["id"]], notes[p["note_id"]]
            if n["merged_into_id"]:
                self._merge_card_into_note(c, n["merged_into_id"], op["op_id"])
            else:
                other = self._live_card(n["id"], c["kind"], exclude=c["id"])
                if other:
                    self._emit("card.merged", {"loser_id": c["id"], "survivor_id": other}, op["op_id"])


# ====================================================================== device
class Device:
    """A device replica with its outbox and sync procedure (SYNC.md 4, 5.4, 6, 10.3).

    legacy=True reproduces attempt-1 behaviour (purge acked rows once pulled; no log identity),
    to show the restore failure that log_epoch / durable_seq fix."""

    def __init__(self, device_id: str, legacy: bool = False, guard: bool = True):
        self.id, self.legacy, self.guard = device_id, legacy, guard  # guard=False: a client without 3.4 G1/G2
        self.r = Replica(device=True)
        self.outbox: list[dict] = []  # pending_op rows: op, state, acked_seq, last_error
        self.pull_seq = 0
        self.log_epoch = None
        self.durable_seq = 0
        self.hlc_last = ""
        self.hlc_acked = ""
        self.rebuild_pending = False
        self.remint_offset = None  # sync_state.remint_offset (10.5)
        self.last_skew = None  # server_time - pt at the last response
        self.pt = 0
        self._n = 0

    def new_id(self, kind: int) -> str:
        self._n += 1
        return f"0193a1b2-{kind:04x}-7000-8000-{self.id[-4:]}{self._n:08x}"

    def clock_guard_ok(self) -> bool:
        """The device-local guard of SYNC.md 3.4 (G1, G2)."""
        return self.pt >= FLOOR_MS and (not self.hlc_last or self.pt >= wall(self.hlc_last) - DAY_MS)

    def make(self, op_type: str, payload: dict, at=None) -> dict:
        """SYNC.md 4: mint, apply locally, queue. at = the virtual pt of mint_at (re-mint only, SYNC.md 10.4)."""
        assert not self.rebuild_pending, "no minting while a rebuild is pending"
        if self.guard and not self.clock_guard_ok():
            raise RuntimeError("fix your clock: minting refused (SYNC.md 3.4 G1/G2)")
        self.hlc_last = hlc_mint(self.hlc_last, self.pt if at is None else at, self.id)
        op = {"op_id": self.new_id(0xff), "op_type": op_type, "op_version": 1,
              "entity_type": INDEX[op_type]["entity_type"], "entity_id": entity_id(op_type, payload),
              "device_id": self.id, "hlc": self.hlc_last, "payload": payload}
        self.r.apply(op)
        self.outbox.append({"op": op, "state": "pending", "acked_seq": None, "last_error": None})
        return op

    # ---------------------------------------------------------------- log identity (6.2)
    def _log_changed(self, resp: dict) -> bool:
        if self.legacy:
            return False
        if self.log_epoch is None:
            self.log_epoch = resp["log_epoch"]
        return resp["log_epoch"] != self.log_epoch or resp["high_water_seq"] < self.pull_seq

    def _start_log_reset(self, resp: dict):
        """One transaction: every acked row back to pending, new epoch, rebuild_pending."""
        for row in self.outbox:
            if row["state"] == "acked":
                row.update(state="pending", acked_seq=None)
        self.log_epoch = resp["log_epoch"]
        self.durable_seq = resp["durable_seq"]
        self.rebuild_pending = True

    def _note_meta(self, resp: dict):
        if not self.legacy:
            self.durable_seq = resp["durable_seq"]

    # ---------------------------------------------------------------- push (5.4)
    def push(self, server: Server):
        while True:
            batch = sorted((r for r in self.outbox if r["state"] == "pending"), key=lambda r: r["op"]["hlc"])[:500]
            if not batch:
                return
            resp = server.push([copy.deepcopy(r["op"]) for r in batch])
            self.last_skew = resp["server_time"] - self.pt
            for row, res in zip(batch, resp["results"]):
                if res["status"] == "rejected":
                    row.update(state="rejected", last_error=res["error"]["code"])
                    if res["error"]["code"] in CLOCK_CODES and self.remint_offset is None:
                        self.remint_offset = self.last_skew  # 10.1, 10.5: first clock rejection
                else:
                    row.update(state="acked", acked_seq=res["seq"])
                    self.hlc_acked = max(self.hlc_acked, row["op"]["hlc"])
            if self._log_changed(resp):
                self._start_log_reset(resp)
                continue  # the rows are pending again: push them again
            self._note_meta(resp)

    # ---------------------------------------------------------------- pull (6)
    def pull(self, server: Server) -> bool:
        """Returns False if the pull stopped because the log changed identity."""
        while True:
            resp = server.pull(self.pull_seq)
            self.last_skew = resp["server_time"] - self.pt
            if self._log_changed(resp):
                self._start_log_reset(resp)
                return False
            for op in resp["ops"]:
                self.hlc_last = hlc_observe(self.hlc_last, op["hlc"])
                try:
                    self.r.apply(op)
                except MissingRow:
                    pass  # 7.7: cannot happen in seq order
            self.pull_seq = resp["next_since"]
            self._note_meta(resp)
            if not resp["has_more"]:
                return True

    def gc(self):
        limit = self.pull_seq if self.legacy else min(self.pull_seq, self.durable_seq)
        self.outbox = [r for r in self.outbox if not (r["state"] == "acked" and r["acked_seq"] <= limit)]

    # ---------------------------------------------------------------- rebuild (10.3)
    def rebuild(self, server: Server):
        while True:
            self.push(server)  # step 0
            self.r, self.pull_seq = Replica(device=True), 0  # step 1
            if self.pull(server):  # step 2 (restart if the log changed again)
                break
        for row in sorted((r for r in self.outbox if r["state"] in ("pending", "rejected")
                           or (r["state"] == "acked" and r["acked_seq"] > self.pull_seq)),
                          key=lambda r: r["op"]["hlc"]):  # step 3
            try:
                self.r.apply(row["op"])
            except MissingRow:
                pass
        self.rebuild_pending = False

    # ---------------------------------------------------------------- re-mint (10.4)
    def remint(self):
        """The Re-mint action of SYNC.md 10.4, one local transaction. The rebuild runs in the next sync."""
        assert not any(r["state"] == "pending" for r in self.outbox), "push until nothing is pending"
        assert self.last_skew is not None and abs(self.last_skew) <= WARN_MS, "clock must be correct"
        # 1. Selection, one pass in ascending original hlc.
        C, E, R, U, D, W, payloads = remint_select([r for r in self.outbox if r["state"] == "rejected"])
        ids = {r["op"]["payload"]["id"] for r in R}
        U += [r for r in self.outbox if r["op"]["op_type"] == "review.undone"
              and r["op"]["payload"]["review_id"] in ids and r not in U]
        drop = {id(r) for r in C + D + E + R + U}
        saved = (self.outbox, self.hlc_last)
        self.outbox = [r for r in self.outbox if id(r) not in drop]  # 2. discard
        if any(r["last_error"] == "clock_ahead" for r in C):  # clock repair
            self.hlc_last = max([self.hlc_acked] + [r["op"]["hlc"] for r in self.outbox])
        if not self.clock_guard_ok():  # 3. abort: nothing changes
            self.outbox, self.hlc_last = saved
            raise RuntimeError("fix your clock")
        todo = [r["op"]["payload"] for r in R
                if self.r.t["review_log"][r["op"]["payload"]["id"]]["undone_hlc"] is None]
        items = [(wall(r["op"]["hlc"]), FLOOR_MS) for r in E] + \
                [(p["reviewed_at"], FLOOR_MS if p["card_id"] in W else self.r.t["card"][p["card_id"]]["created_at"])
                 for p in todo]
        if items:  # 4-5. one shift for the whole batch
            delta, times = remint_times(items, self.remint_offset or 0, self.pt)
            for r, v in zip(E, times):  # 6a. rows created in the window and their edits, mint_at(v)
                self.make(r["op"]["op_type"], payloads[r["op"]["op_id"]], at=v)
            for p, t in zip(todo, times[len(E):]):  # 6b. reviews, in the original hlc order
                q = {**p, "id": self.new_id(3), "reviewed_at": t}
                if q.get("manual_due_at") is not None:
                    q["manual_due_at"] += delta
                self.make("review.logged", q)
        self.remint_offset = None  # 7.
        self.rebuild_pending = True

    def sync(self, server: Server):
        """Sync cycle (SYNC.md 6): push, then pull; a pending rebuild runs first."""
        self.push(server)
        if not self.rebuild_pending:
            self.pull(server)  # may itself start a log reset
        if self.rebuild_pending:
            self.rebuild(server)
        self.gc()
