#!/usr/bin/env python3
"""Validator for the C-11 media pipeline formats (contracts/db/SNAPSHOT.md, MEDIA_PIPELINE.md).

usage:
  validate.py                                  self-test: every example + every built-in negative case
  validate.py content <archive>                a kotobaya-media-content archive (.zst or plain .jsonl)
  validate.py deck <archive>                   a kotobaya-deck-items archive
  validate.py pair <content> <deck>            both, plus the media-deck pairing rules (SNAPSHOT.md 5)
  validate.py publish <payload.json> <content.zst> <deck.zst>
                                               a media-publish payload with its two blobs (MEDIA_PIPELINE.md 3.1)
  validate.py deck-publish <payload.json> <deck.zst>
                                               a deck-publish payload with its blob (VOCAB_LISTS.md 5.1, C-21)

Exit status 0 = valid. Producers (worker) and consumers (server, devices) may shell out to this
script in tests, or port the rules; the rules here and in SNAPSHOT.md are the same list.
Needs python3 with jsonschema >= 4.18; zstd via compression.zstd (3.14+), the zstandard
package, or the zstd CLI.
"""
from __future__ import annotations

import copy
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
from collections import Counter
from typing import Any, Callable

import jsonschema

HERE = pathlib.Path(__file__).resolve().parent
EXAMPLES = HERE / "examples"
MAX_COMPRESSED = 512 * 1024 * 1024
MAX_LINE = 16 * 1024 * 1024
CONTENT_ORDER = ["header", "segment", "sentence", "occurrence", "grammar_span"]


class Invalid(Exception):
    pass


def _schema(name: str) -> jsonschema.protocols.Validator:
    schema = json.loads((HERE / name).read_text(encoding="utf-8"))
    cls = jsonschema.validators.validator_for(schema)
    cls.check_schema(schema)
    return cls(schema)


CONTENT_SCHEMA = _schema("content.v1.schema.json")
DECK_SCHEMA = _schema("deck-items.v1.schema.json")
PUBLISH_SCHEMA = _schema("media-publish.v1.schema.json")
DECK_PUBLISH_SCHEMA = _schema("deck-publish.v1.schema.json")


# ------------------------------------------------------------------ bytes and lines
def zstd_decompress(data: bytes) -> bytes:
    try:
        from compression import zstd  # type: ignore[import-not-found]  # Python 3.14+

        return zstd.decompress(data)
    except ImportError:
        pass
    try:
        import zstandard  # type: ignore[import-not-found]

        return zstandard.ZstdDecompressor().decompressobj().decompress(data)
    except ImportError:
        pass
    if shutil.which("zstd"):
        return subprocess.run(["zstd", "-dc"], input=data, capture_output=True, check=True).stdout
    raise SystemExit("validate.py: no zstd decoder (need Python 3.14, the zstandard package, or the zstd CLI)")


def zstd_compress(data: bytes) -> bytes:
    """Self-test only (negative cases built on the fly)."""
    try:
        from compression import zstd  # type: ignore[import-not-found]  # Python 3.14+

        return zstd.compress(data)
    except ImportError:
        pass
    try:
        import zstandard  # type: ignore[import-not-found]

        return zstandard.ZstdCompressor().compress(data)
    except ImportError:
        pass
    if shutil.which("zstd"):
        return subprocess.run(["zstd", "-c", "-q"], input=data, capture_output=True, check=True).stdout
    raise SystemExit("validate.py: no zstd encoder (need Python 3.14, the zstandard package, or the zstd CLI)")


def read_archive(path: pathlib.Path) -> bytes:
    data = path.read_bytes()
    if path.suffix == ".zst":
        if len(data) > MAX_COMPRESSED:
            raise Invalid("compressed size %d exceeds %d" % (len(data), MAX_COMPRESSED))
        return zstd_decompress(data)
    return data


def _no_dup(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for k, v in pairs:
        if k in out:
            raise Invalid("duplicate member %r" % k)
        out[k] = v
    return out


def _bad_constant(name: str) -> Any:
    raise Invalid("non-JSON number %s" % name)


def _check_strings(value: Any, where: str) -> None:
    if isinstance(value, str):
        if "\x00" in value:
            raise Invalid("%s: U+0000 in a string" % where)
        try:
            value.encode("utf-8")
        except UnicodeEncodeError:
            raise Invalid("%s: lone surrogate in a string" % where) from None
    elif isinstance(value, dict):
        for k, v in value.items():
            _check_strings(k, where)
            _check_strings(v, where)
    elif isinstance(value, list):
        for v in value:
            _check_strings(v, where)


def parse_lines(data: bytes) -> list[dict[str, Any]]:
    """SNAPSHOT.md 2: UTF-8, no BOM, LF-terminated lines, one JSON object per line."""
    if data.startswith(b"\xef\xbb\xbf"):
        raise Invalid("byte order mark")
    if not data.endswith(b"\n"):
        raise Invalid("last line not terminated by LF (truncated?)")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise Invalid("not UTF-8: %s" % e) from None
    records = []
    for n, line in enumerate(text[:-1].split("\n"), start=1):
        if len(line.encode("utf-8")) > MAX_LINE:
            raise Invalid("line %d longer than %d bytes" % (n, MAX_LINE))
        if line == "" or line.strip() != line or "\r" in line:
            raise Invalid("line %d: empty, padded or CR" % n)
        try:
            rec = json.loads(line, object_pairs_hook=_no_dup, parse_constant=_bad_constant)
        except json.JSONDecodeError as e:
            raise Invalid("line %d: not JSON: %s" % (n, e)) from None
        except Invalid as e:
            raise Invalid("line %d: %s" % (n, e)) from None
        if not isinstance(rec, dict):
            raise Invalid("line %d: not an object" % n)
        _check_strings(rec, "line %d" % n)
        for k, v in rec.items():  # record members are never fractional (tokens/location may be)
            if k not in ("tokens", "location") and isinstance(v, float):
                raise Invalid("line %d: %s is not an integer" % (n, k))
        records.append(rec)
    if not records:
        raise Invalid("empty archive")
    return records


def schema_check(validator: jsonschema.protocols.Validator, records: list[dict[str, Any]]) -> None:
    for n, rec in enumerate(records, start=1):
        errs = sorted(validator.iter_errors(rec), key=lambda e: list(e.path))
        if errs:
            raise Invalid("line %d (%s): schema: %s" % (n, rec.get("record"), errs[0].message))


def utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


# ------------------------------------------------------------------ media content (SNAPSHOT.md 3)
def validate_content(records: list[dict[str, Any]]) -> dict[str, Any]:
    schema_check(CONTENT_SCHEMA, records)
    head = records[0]
    if head["record"] != "header":
        raise Invalid("first line is not the header")
    kinds = [r["record"] for r in records[1:]]
    if "header" in kinds:
        raise Invalid("second header")
    counts = Counter(kinds)
    for k in CONTENT_ORDER[1:]:
        if counts.get(k, 0) != head["counts"][k]:
            raise Invalid("header counts.%s = %d but %d records" % (k, head["counts"][k], counts.get(k, 0)))
    ranks = [CONTENT_ORDER.index(k) for k in kinds]
    if ranks != sorted(ranks):
        raise Invalid("records not in section order segment, sentence, occurrence, grammar_span")

    segs = [r for r in records if r["record"] == "segment"]
    sents = [r for r in records if r["record"] == "sentence"]
    occs = [r for r in records if r["record"] == "occurrence"]
    spans = [r for r in records if r["record"] == "grammar_span"]

    # segments: ordinals 0..k-1 in order, unique ids
    if [s["ordinal"] for s in segs] != list(range(len(segs))):
        raise Invalid("segment ordinals are not 0..%d in order" % (len(segs) - 1))
    seg_by_id = {s["id"]: s for s in segs}
    if len(seg_by_id) != len(segs):
        raise Invalid("duplicate segment id")

    # sentences: grouped by segment ordinal, ordinals 0..n-1 per segment, unique ids
    sent_by_id: dict[str, dict[str, Any]] = {}
    per_seg: dict[str, list[dict[str, Any]]] = {s["id"]: [] for s in segs}
    last_key = (-1, -1)
    for s in sents:
        seg = seg_by_id.get(s["segment_id"])
        if seg is None:
            raise Invalid("sentence %s: unknown segment_id" % s["id"])
        if s["id"] in sent_by_id or s["id"] in seg_by_id:
            raise Invalid("duplicate sentence id %s" % s["id"])
        key = (seg["ordinal"], s["ordinal"])
        if key <= last_key:
            raise Invalid("sentence %s out of order (segment ordinal, ordinal)" % s["id"])
        last_key = key
        if (s["start_ms"] is None) != (s["end_ms"] is None):
            raise Invalid("sentence %s: start_ms and end_ms must both be set or both null" % s["id"])
        if s["start_ms"] is not None and s["end_ms"] < s["start_ms"]:
            raise Invalid("sentence %s: end_ms < start_ms" % s["id"])
        n16 = utf16_len(s["text"])
        prev_end = 0
        for t in s["tokens"]:
            st, en = t.get("s"), t.get("e")
            if type(st) is not int or type(en) is not int or not (prev_end <= st < en <= n16):
                raise Invalid("sentence %s: token offsets not ascending, non-overlapping UTF-16 [s, e) inside text" % s["id"])
            prev_end = en
            seq = t.get("seq")
            if seq is not None and (type(seq) is not int or seq < 1):
                raise Invalid("sentence %s: token seq must be null or a positive integer" % s["id"])
        sent_by_id[s["id"]] = s
        per_seg[s["segment_id"]].append(s)
    for seg in segs:
        mine = per_seg[seg["id"]]
        if [s["ordinal"] for s in mine] != list(range(len(mine))):
            raise Invalid("segment %d: sentence ordinals are not 0..n-1" % seg["ordinal"])
        if seg["sentence_count"] != len(mine):
            raise Invalid("segment %d: sentence_count %d but %d sentences" % (seg["ordinal"], seg["sentence_count"], len(mine)))

    # occurrences: ordered by (segment ordinal, entry_seq), unique, sums
    last_okey = (-1, 0)
    occ_by_seg: dict[str, dict[int, dict[str, Any]]] = {s["id"]: {} for s in segs}
    for o in occs:
        seg = seg_by_id.get(o["segment_id"])
        if seg is None:
            raise Invalid("occurrence %d: unknown segment_id" % o["entry_seq"])
        key = (seg["ordinal"], o["entry_seq"])
        if key <= last_okey:
            raise Invalid("occurrence (%d, %d) duplicate or out of order" % key)
        last_okey = key
        ords = []
        for sid in o["sentence_ids"]:
            s = sent_by_id.get(sid)
            if s is None or s["segment_id"] != o["segment_id"]:
                raise Invalid("occurrence (%d, %d): sentence %s not in this segment" % (key + (sid,)))
            ords.append(s["ordinal"])
        if ords != sorted(ords):
            raise Invalid("occurrence (%d, %d): sentence_ids not in ordinal order" % key)
        occ_by_seg[o["segment_id"]][o["entry_seq"]] = o
    for seg in segs:
        total = sum(o["count"] for o in occ_by_seg[seg["id"]].values())
        if seg["unmapped_token_count"] > seg["token_count"]:
            raise Invalid("segment %d: unmapped_token_count > token_count" % seg["ordinal"])
        if seg["token_count"] != total + seg["unmapped_token_count"]:
            raise Invalid("segment %d: token_count %d != sum(occurrence.count) %d + unmapped_token_count %d"
                          % (seg["ordinal"], seg["token_count"], total, seg["unmapped_token_count"]))

    # SNAPSHOT.md 3.5: for segments with sentences, occurrences are the aggregation of the tokens
    for seg in segs:
        media_sents = [s for s in per_seg[seg["id"]] if s["origin"] == "media"]
        if not per_seg[seg["id"]]:
            continue
        counts: Counter[int] = Counter()
        classes: dict[int, Counter[str]] = {}
        where: dict[int, list[str]] = {}
        unmapped = 0
        for s in media_sents:
            for t in s["tokens"]:
                seq = t.get("seq")
                if seq is None:
                    unmapped += 1
                    continue
                counts[seq] += 1
                classes.setdefault(seq, Counter())[t.get("class", "content")] += 1
                lst = where.setdefault(seq, [])
                if (not lst or lst[-1] != s["id"]) and len(lst) < 20:
                    lst.append(s["id"])
        got = occ_by_seg[seg["id"]]
        if set(got) != set(counts):
            raise Invalid("segment %d: occurrence entries %s differ from token seqs %s"
                          % (seg["ordinal"], sorted(got), sorted(counts)))
        for seq, o in got.items():
            if o["count"] != counts[seq]:
                raise Invalid("segment %d: occurrence %d count %d != %d tokens" % (seg["ordinal"], seq, o["count"], counts[seq]))
            if o["sentence_ids"] != where[seq]:
                raise Invalid("segment %d: occurrence %d sentence_ids are not the first 20 containing sentences" % (seg["ordinal"], seq))
            top = max(classes[seq].values())
            if classes[seq].get(o["pos_class"], 0) != top:
                raise Invalid("segment %d: occurrence %d pos_class is not a most frequent token class" % (seg["ordinal"], seq))
        if seg["unmapped_token_count"] != unmapped:
            raise Invalid("segment %d: unmapped_token_count %d != %d tokens with seq null"
                          % (seg["ordinal"], seg["unmapped_token_count"], unmapped))

    # grammar spans: reference a sentence of the file, inside its text, ordered, unique
    order = {s["id"]: i for i, s in enumerate(sents)}
    last_skey: tuple[Any, ...] = (-1,)
    for g in spans:
        s = sent_by_id.get(g["sentence_id"])
        if s is None:
            raise Invalid("grammar_span: sentence %s not in the archive" % g["sentence_id"])
        if not g["start_offset"] < g["end_offset"] <= utf16_len(s["text"]):
            raise Invalid("grammar_span on %s: offsets outside the sentence" % g["sentence_id"])
        key = (order[g["sentence_id"]], g["start_offset"], g["end_offset"], g["grammar_point_id"])
        if key <= last_skey:
            raise Invalid("grammar_span on %s: duplicate or out of order" % g["sentence_id"])
        last_skey = key

    entry_totals: Counter[int] = Counter()
    for o in occs:
        entry_totals[o["entry_seq"]] += o["count"]
    return {"header": head, "segments": segs, "sentences": len(sents), "entry_totals": entry_totals,
            "tokens": sum(s["token_count"] for s in segs), "unmapped_tokens": sum(s["unmapped_token_count"] for s in segs)}


# ------------------------------------------------------------------ deck items (SNAPSHOT.md 4)
def validate_deck(records: list[dict[str, Any]]) -> dict[str, Any]:
    schema_check(DECK_SCHEMA, records)
    head = records[0]
    if head["record"] != "header":
        raise Invalid("first line is not the header")
    items = records[1:]
    if any(r["record"] != "item" for r in items):
        raise Invalid("second header")
    if len(items) != head["counts"]["item"]:
        raise Invalid("header counts.item = %d but %d records" % (head["counts"]["item"], len(items)))
    seqs = [i["entry_seq"] for i in items]
    if len(set(seqs)) != len(seqs):
        raise Invalid("duplicate entry_seq")
    keys = [(i["position"], i["entry_seq"]) for i in items]
    if keys != sorted(keys):
        raise Invalid("items not ordered by (position, entry_seq)")
    if head["deck_type"] == "media":
        if [i["position"] for i in items] != list(range(1, len(items) + 1)):
            raise Invalid("media deck: positions are not 1..n")
        if any(i["occurrence_count"] < 1 for i in items):
            raise Invalid("media deck: occurrence_count must be >= 1")
        ranked = sorted(items, key=lambda i: (-i["occurrence_count"], i["entry_seq"]))
        if ranked != items:
            raise Invalid("media deck: positions do not rank by occurrence_count desc, entry_seq asc")
    else:
        if any(i["occurrence_count"] != 0 for i in items):
            raise Invalid("list deck: occurrence_count must be 0")
    return {"header": head, "items": {i["entry_seq"]: i["occurrence_count"] for i in items}}


def validate_pair(content: dict[str, Any], deck: dict[str, Any]) -> None:
    """SNAPSHOT.md 5: a media deck's items are exactly the media's entries with summed counts."""
    ch, dh = content["header"], deck["header"]
    if dh["deck_type"] != "media":
        raise Invalid("pair: deck_type must be media")
    if dh["deck_id"] != ch["media_id"]:
        raise Invalid("pair: deck_id != media_id")
    if dh["content_version"] != ch["content_version"]:
        raise Invalid("pair: content_version differs")
    if dict(content["entry_totals"]) != deck["items"]:
        raise Invalid("pair: deck items are not exactly the content's entries with summed occurrence counts")


# ------------------------------------------------------------------ media-publish payload (MEDIA_PIPELINE.md 3.1)
def validate_publish(payload: dict[str, Any], content_blob: bytes, deck_blob: bytes) -> dict[str, Any]:
    errs = sorted(PUBLISH_SCHEMA.iter_errors(payload), key=lambda e: list(e.path))
    if errs:
        raise Invalid("payload: schema: %s at %s" % (errs[0].message, "/".join(map(str, errs[0].path))))
    for name, blob in (("content", content_blob), ("deck_items", deck_blob)):
        ref = payload[name]
        if hashlib.sha256(blob).hexdigest() != ref["blob_hash"] or len(blob) != ref["size_bytes"]:
            raise Invalid("payload: %s blob_hash/size_bytes do not match the blob" % name)
    content = validate_content(parse_lines(zstd_decompress(content_blob)))
    deck = validate_deck(parse_lines(zstd_decompress(deck_blob)))
    validate_pair(content, deck)
    version = payload["base_content_version"] + 1
    if content["header"]["media_id"] != payload["media_id"] or content["header"]["content_version"] != version:
        raise Invalid("payload: archive header media_id/content_version != payload media_id/base_content_version + 1")
    jpdb = payload["jpdb"]
    is_jpdb = jpdb is not None
    if payload["create"] is not None and (payload["create"]["source"] == "jpdb") != is_jpdb:
        raise Invalid("payload: jpdb must be set iff create.source = jpdb")
    if payload["create"] is not None and (payload["create"]["jpdb_id"] is not None) != is_jpdb:
        raise Invalid("payload: create.jpdb_id must be set iff source = jpdb")
    if not is_jpdb:
        if payload["unmapped"]:
            raise Invalid("payload: unmapped must be [] for non-jpdb media")
    else:
        segs = content["segments"]
        if len(segs) != 1 or segs[0]["kind"] != "whole" or content["sentences"] != 0:
            raise Invalid("jpdb: content must be one whole segment with no sentences")
        vocab = jpdb["vocab"]
        vids = [v["jpdb_vid"] for v in vocab]
        if len(set(vids)) != len(vids):
            raise Invalid("jpdb: duplicate jpdb_vid in vocab")
        if vocab != sorted(vocab, key=lambda v: (-v["occurrence_count"], v["jpdb_vid"])):
            raise Invalid("jpdb: vocab not ordered by occurrence_count desc, jpdb_vid asc")
        for v in vocab:
            if (v["resolution"] == "mapped") != (v["entry_seq"] is not None):
                raise Invalid("jpdb: vid %d: entry_seq must be set iff resolution = mapped" % v["jpdb_vid"])
            if v["resolution"] == "mapped" and v["entry_seq"] not in content["entry_totals"]:
                raise Invalid("jpdb: vid %d mapped to %d but the content has no occurrence of it" % (v["jpdb_vid"], v["entry_seq"]))
        expect = [{k: v[k] for k in ("jpdb_vid", "spelling", "reading", "occurrence_count")}
                  for v in vocab if v["resolution"] == "unresolved"]
        if payload["unmapped"] != expect:
            raise Invalid("jpdb: unmapped must equal the unresolved vocab entries, in order")
        not_mapped = sum(v["occurrence_count"] for v in vocab if v["resolution"] != "mapped")
        if not_mapped != content["unmapped_tokens"]:
            raise Invalid("jpdb: unmapped_token_count %d != occurrences of unresolved + ignored vocab %d"
                          % (content["unmapped_tokens"], not_mapped))
    return {"media_id": payload["media_id"], "content_version": version, "segments": len(content["segments"]),
            "sentences": content["sentences"], "tokens": content["tokens"], "unmapped_tokens": content["unmapped_tokens"],
            "unique_entries": len(deck["items"]), "unmapped": payload["unmapped"]}


# ------------------------------------------------------------------ deck-publish payload (VOCAB_LISTS.md 5.1, C-21)
def validate_deck_publish(payload: dict[str, Any], deck_blob: bytes) -> dict[str, Any]:
    errs = sorted(DECK_PUBLISH_SCHEMA.iter_errors(payload), key=lambda e: list(e.path))
    if errs:
        raise Invalid("payload: schema: %s at %s" % (errs[0].message, "/".join(map(str, errs[0].path))))
    ref = payload["deck_items"]
    if hashlib.sha256(deck_blob).hexdigest() != ref["blob_hash"] or len(deck_blob) != ref["size_bytes"]:
        raise Invalid("payload: deck_items blob_hash/size_bytes do not match the blob")
    records = parse_lines(zstd_decompress(deck_blob))
    deck = validate_deck(records)
    head = deck["header"]
    version = payload["base_content_version"] + 1
    if head["deck_type"] != "list":
        raise Invalid("deck-publish: deck_type must be list")
    if head["deck_id"] != payload["deck_id"] or head["content_version"] != version:
        raise Invalid("payload: archive header deck_id/content_version != payload deck_id/base_content_version + 1")
    items = records[1:]
    if not items:
        raise Invalid("deck-publish: empty list (a deck-build with no items fails instead of publishing)")
    if [i["position"] for i in items] != [1024 * k for k in range(1, len(items) + 1)]:
        raise Invalid("deck-publish: positions are not 1024, 2048, ...")
    return {"deck_id": payload["deck_id"], "content_version": version, "items": len(items),
            "list_key": payload["list_key"], "changed": True, "unmatched": payload["unmatched"]}


# ------------------------------------------------------------------ self-test
def _lines(name: str) -> list[dict[str, Any]]:
    return parse_lines((EXAMPLES / name).read_bytes())


def _dump(records: list[dict[str, Any]]) -> bytes:
    return "".join(json.dumps(r, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n" for r in records).encode()


Mut = Callable[[list[dict[str, Any]]], None]


def _set(i: int, key: str, value: Any) -> Mut:
    def f(rs: list[dict[str, Any]]) -> None:
        rs[i][key] = value
    return f


def _swap(i: int, j: int) -> Mut:
    def f(rs: list[dict[str, Any]]) -> None:
        rs[i], rs[j] = rs[j], rs[i]
    return f


def _drop(i: int) -> Mut:
    def f(rs: list[dict[str, Any]]) -> None:
        del rs[i]
    return f


NEW_C = "new-media.content.v1.jsonl"
NEW_D = "new-media.deck-items.v1.jsonl"
JPDB_C = "jpdb-reimport.content.v1.jsonl"
LIST_D = "list-deck.deck-items.v1.jsonl"
# (label, archive, mutation, expected error substring). Line 0 = header. In NEW_C: 1-2 segments,
# 3-5 sentences, 6-16 occurrences, 17 grammar span.
NEGATIVE: list[tuple[str, str, Mut, str]] = [
    ("truncated: a record missing", NEW_C, _drop(16), "counts.occurrence"),
    ("header not first", NEW_C, _swap(0, 1), "first line"),
    ("unknown format_version", NEW_C, lambda rs: rs[0].__setitem__("format_version", 2), "schema"),
    ("unknown record kind", NEW_C, lambda rs: rs.insert(3, {"record": "chapter_note"}), "schema"),
    ("segment ordinal gap", NEW_C, _set(2, "ordinal", 2), "segment ordinals"),
    ("sections out of order", NEW_C, _swap(5, 6), "section order"),
    ("sentence_count wrong", NEW_C, _set(1, "sentence_count", 3), "sentence_count"),
    ("token_count sum wrong", NEW_C, _set(1, "token_count", 8), "token_count"),
    ("start_ms without end_ms", NEW_C, _set(3, "end_ms", None), "start_ms and end_ms"),
    ("U+0000 in text", NEW_C, _set(3, "text", "パン\x00を食べる。"), "U+0000"),
    ("token outside text", NEW_C, lambda rs: rs[3]["tokens"][2].__setitem__("e", 9), "token offsets"),
    ("jpdb: occurrence count breaks the token_count sum", JPDB_C, _set(2, "count", 58), "token_count"),
    ("occurrence count disagrees with the tokens", NEW_C, lambda rs: (rs[6].__setitem__("count", 2), rs[1].__setitem__("token_count", 8)), "tokens"),
    ("sentence_ids not the first containing sentences", NEW_C, _set(6, "sentence_ids", []), "first 20"),
    ("occurrence sentence from other segment", NEW_C, _set(15, "sentence_ids", ["0193a1b2-0007-7000-8000-0000000005b0"]), "not in this segment"),
    ("more than 20 sentence_ids", NEW_C, _set(6, "sentence_ids", ["0193a1b2-0007-7000-8000-0000000005b0"] * 21), "schema"),
    ("grammar span past end of text", NEW_C, _set(17, "end_offset", 13), "outside the sentence"),
    ("duplicate member", NEW_C, None, "duplicate member"),  # handled specially below
    ("deck: positions not dense", NEW_D, _set(6, "position", 7), "positions are not 1..n"),
    ("deck: duplicate entry", NEW_D, _set(2, "entry_seq", 1101220), "duplicate entry_seq"),
    ("deck: rank tie broken wrong", NEW_D, lambda rs: (rs[1].__setitem__("entry_seq", 1358280), rs[2].__setitem__("entry_seq", 1101220)), "rank by occurrence_count"),
    ("list deck: nonzero occurrence_count", LIST_D, _set(1, "occurrence_count", 3), "list deck"),
    ("list deck: unordered", LIST_D, _swap(1, 2), "ordered by (position, entry_seq)"),
]


def selftest() -> int:
    failures = 0

    def ok(label: str, fn: Callable[[], Any]) -> None:
        nonlocal failures
        try:
            fn()
            print("  ok   %s" % label)
        except Invalid as e:
            failures += 1
            print("  FAIL %s: %s" % (label, e))

    print("== examples")
    for jsonl in sorted(EXAMPLES.glob("*.jsonl")):
        zst = jsonl.with_name(jsonl.name + ".zst")

        def same(j: pathlib.Path = jsonl, z: pathlib.Path = zst) -> None:
            if not z.exists() or zstd_decompress(z.read_bytes()) != j.read_bytes():
                raise Invalid("%s does not decompress to %s" % (z.name, j.name))
        ok("%s decompresses to %s" % (zst.name, jsonl.name), same)
        if ".content." in jsonl.name:
            ok(jsonl.name, lambda j=jsonl: validate_content(parse_lines(j.read_bytes())))
        else:
            ok(jsonl.name, lambda j=jsonl: validate_deck(parse_lines(j.read_bytes())))
    for stem in ("new-media", "jpdb-reimport"):
        ok("pair %s" % stem, lambda s=stem: validate_pair(
            validate_content(_lines(s + ".content.v1.jsonl")), validate_deck(_lines(s + ".deck-items.v1.jsonl"))))
        payload = json.loads((EXAMPLES / ("media-publish.%s.json" % stem)).read_text(encoding="utf-8"))
        ok("media-publish.%s.json" % stem, lambda p=payload, s=stem: validate_publish(
            p, (EXAMPLES / (s + ".content.v1.jsonl.zst")).read_bytes(), (EXAMPLES / (s + ".deck-items.v1.jsonl.zst")).read_bytes()))

    dp = json.loads((EXAMPLES / "deck-publish.list-deck.json").read_text(encoding="utf-8"))
    ok("deck-publish.list-deck.json", lambda: validate_deck_publish(dp, (EXAMPLES / (LIST_D + ".zst")).read_bytes()))

    print("== negative cases (each must be rejected)")
    for label, name, mut, expect in NEGATIVE:
        if mut is None:
            data = (EXAMPLES / name).read_bytes().replace(b'{"content_version":1,', b'{"content_version":1,"content_version":1,', 1)
        else:
            rs = copy.deepcopy(_lines(name))
            mut(rs)
            data = _dump(rs)
        try:
            recs = parse_lines(data)
            (validate_content if ".content." in name else validate_deck)(recs)
        except Invalid as e:
            if expect in str(e):
                print("  ok   %s" % label)
            else:
                failures += 1
                print("  FAIL %s: rejected for another reason: %s" % (label, e))
        else:
            failures += 1
            print("  FAIL %s: accepted" % label)

    payload_neg: list[tuple[str, str, Callable[[dict[str, Any]], None], str]] = [
        ("publish: create with base > 0", "new-media", lambda p: p.__setitem__("base_content_version", 1), "schema"),
        ("publish: header version != base + 1", "jpdb-reimport", lambda p: p.__setitem__("base_content_version", 2), "base_content_version + 1"),
        ("publish: create on existing media", "jpdb-reimport", lambda p: p.__setitem__("create", {"title": "x", "kind": "anime", "source": "jpdb", "jpdb_id": "anime/5370", "deck": {"priority": 0, "enabled": True, "new_cards_per_day": None}}), "schema"),
        ("publish: hash mismatch", "new-media", lambda p: p["content"].__setitem__("size_bytes", 1012), "do not match"),
        ("publish: unmapped != unresolved vocab", "jpdb-reimport", lambda p: p.__setitem__("unmapped", []), "unmapped must equal"),
        ("publish: ignored tokens not counted", "jpdb-reimport", lambda p: p["jpdb"]["vocab"][2].__setitem__("occurrence_count", 41), "unmapped_token_count"),
        ("publish: mapped without entry_seq", "jpdb-reimport", lambda p: p["jpdb"]["vocab"][1].__setitem__("entry_seq", None), "entry_seq must be set"),
    ]
    for label, stem, mut2, expect in payload_neg:
        p = json.loads((EXAMPLES / ("media-publish.%s.json" % stem)).read_text(encoding="utf-8"))
        mut2(p)
        try:
            validate_publish(p, (EXAMPLES / (stem + ".content.v1.jsonl.zst")).read_bytes(),
                             (EXAMPLES / (stem + ".deck-items.v1.jsonl.zst")).read_bytes())
        except Invalid as e:
            if expect in str(e):
                print("  ok   %s" % label)
            else:
                failures += 1
                print("  FAIL %s: rejected for another reason: %s" % (label, e))
        else:
            failures += 1
            print("  FAIL %s: accepted" % label)

    def _list_blob(mut: Callable[[list[dict[str, Any]]], None]) -> bytes:
        rs = copy.deepcopy(_lines(LIST_D))
        mut(rs)
        return zstd_compress(_dump(rs))

    def _with_blob(p: dict[str, Any], blob: bytes) -> bytes:
        p["deck_items"] = {"blob_hash": hashlib.sha256(blob).hexdigest(), "size_bytes": len(blob)}
        return blob

    deck_neg: list[tuple[str, Callable[[dict[str, Any]], bytes], str]] = [
        ("deck-publish: header version != base + 1", lambda p: (p.__setitem__("base_content_version", 0), (EXAMPLES / (LIST_D + ".zst")).read_bytes())[1], "base_content_version + 1"),
        ("deck-publish: other deck", lambda p: (p.__setitem__("deck_id", "0193a1b2-0009-7000-8000-0000000000ff"), (EXAMPLES / (LIST_D + ".zst")).read_bytes())[1], "deck_id"),
        ("deck-publish: hash mismatch", lambda p: (p["deck_items"].__setitem__("size_bytes", 204), (EXAMPLES / (LIST_D + ".zst")).read_bytes())[1], "do not match"),
        ("deck-publish: unknown member", lambda p: (p.__setitem__("items", 3), (EXAMPLES / (LIST_D + ".zst")).read_bytes())[1], "schema"),
        ("deck-publish: empty list", lambda p: _with_blob(p, _list_blob(lambda rs: (rs.__delitem__(slice(1, None)), rs[0]["counts"].__setitem__("item", 0)))), "empty list"),
        ("deck-publish: sparse positions", lambda p: _with_blob(p, _list_blob(lambda rs: rs[3].__setitem__("position", 4096))), "1024, 2048"),
        ("deck-publish: media deck archive", lambda p: _with_blob(p, _list_blob(lambda rs: rs[0].__setitem__("deck_type", "media"))), "media deck"),
    ]
    for label, mut3, expect in deck_neg:
        p = json.loads((EXAMPLES / "deck-publish.list-deck.json").read_text(encoding="utf-8"))
        try:
            blob = mut3(p)
            validate_deck_publish(p, blob)
        except Invalid as e:
            if expect in str(e):
                print("  ok   %s" % label)
            else:
                failures += 1
                print("  FAIL %s: rejected for another reason: %s" % (label, e))
        else:
            failures += 1
            print("  FAIL %s: accepted" % label)

    if failures:
        print("validate.py: %d failure(s)" % failures)
        return 1
    print("contracts/db/media: ok")
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        return selftest()
    cmd, args = argv[0], [pathlib.Path(a) for a in argv[1:]]
    try:
        if cmd == "content" and len(args) == 1:
            validate_content(parse_lines(read_archive(args[0])))
        elif cmd == "deck" and len(args) == 1:
            validate_deck(parse_lines(read_archive(args[0])))
        elif cmd == "pair" and len(args) == 2:
            validate_pair(validate_content(parse_lines(read_archive(args[0]))),
                          validate_deck(parse_lines(read_archive(args[1]))))
        elif cmd == "deck-publish" and len(args) == 2:
            result = validate_deck_publish(json.loads(args[0].read_text(encoding="utf-8")), args[1].read_bytes())
            print(json.dumps(result, ensure_ascii=False))
        elif cmd == "publish" and len(args) == 3:
            result = validate_publish(json.loads(args[0].read_text(encoding="utf-8")), args[1].read_bytes(), args[2].read_bytes())
            print(json.dumps(result, ensure_ascii=False))
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except Invalid as e:
        print("invalid: %s" % e, file=sys.stderr)
        return 1
    print("valid")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
