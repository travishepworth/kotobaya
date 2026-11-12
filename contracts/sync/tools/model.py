"""Reference model of a replica's apply function (SYNC.md sections 7-9).

NOT normative and not production code: SYNC.md and OPS.md are the contract. This
model exists so that check.sh can prove the scenarios in examples/scenarios/ are
self-consistent (the expected state really follows from the rules, and the
order-independence claims hold). If the model and the documents disagree, the
documents win and the model has a bug.

It models a *pure replica*: it applies the ops it is given. It does not validate
or reject (server, SYNC.md 5.2), does not detect collisions and emit merges
(server, SYNC.md 8.1), and does not compute FSRS state: instead of the card cache
it exposes each card's replay set (SYNC.md 9.1).

Rows use the device representation: instants are epoch milliseconds, arrays are
JSON arrays.
"""
from __future__ import annotations

import copy

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


class MissingRow(Exception):
    """The op references a row this replica does not have (cannot happen in seq order)."""


def wall(hlc: str) -> int:
    """Wall-clock milliseconds of an HLC = the op's wall time."""
    return int(hlc[:12], 16)


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
    def replay_set(self, card_id) -> list:
        """Ids of the reviews that determine the card's schedule, in replay order (SYNC.md 9.1)."""
        rows = [r for r in self.t["review_log"].values()
                if (r["card_id"] == card_id or self.t["card"][r["card_id"]]["merged_into_id"] == card_id)
                and r["undone_at"] is None and r["review_type"] in FEEDS_SCHEDULE]
        return [r["id"] for r in sorted(rows, key=lambda r: (r["hlc"], r["id"]))]

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
