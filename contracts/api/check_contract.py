#!/usr/bin/env python3
"""Contract rules for contracts/api/openapi.yaml that a generic OpenAPI linter cannot express.

usage: check_contract.py <bundled openapi.json> <SUMMARY.md> <contracts/db/SCHEMA.md>

Checks (T004 acceptance):
  1. Every operation has an operationId, a tag, and an explicit `security` declaration; every
     non-public operation names exactly one scope per alternative, from the five known scopes
     (an empty scope list is allowed only for getPrincipal: "any valid credential").
  2. Every non-GET operation declares `x-emits-ops` (possibly empty): the sync ops the server
     emits on the caller's behalf (CLAUDE.md rule 8). GET operations must not declare it.
  3. Every JSON request body and every JSON response has at least one example.
  4. Every operationId appears in SUMMARY.md (the spec-resource -> operationId checklist), and
     every operationId named in the checklist tables exists.
  5. Every op name in `x-emits-ops` is an op of contracts/db/SCHEMA.md section 4, and every
     learning-data table of SCHEMA.md 4.1 (plus media, parse_override) is mapped to at least
     one existing operation in the SUMMARY.md cross-check table.
"""
import json
import re
import sys

SCOPES = {"read", "write", "review", "import", "admin"}
PUBLIC = {"getHealth", "login", "pairDevice"}
ANY_CREDENTIAL = {"getPrincipal"}
METHODS = ("get", "put", "post", "delete", "patch", "head", "options")
TABLES = [
    "note_type", "note", "card", "review_log", "word_status", "deck", "deck_item", "confusable",
    "grammar_point", "sentence_grammar_span", "settings", "fsrs_params", "media", "parse_override",
]

spec = json.load(open(sys.argv[1], encoding="utf-8"))
summary = open(sys.argv[2], encoding="utf-8").read()
schema_md = open(sys.argv[3], encoding="utf-8").read()
errors = []


def err(msg):
    errors.append(msg)


# ops known to the database contract: `a.b` names in backticks, with a/b/c shorthand expanded
known_ops = set()
for m in re.finditer(r"`([a-z_]+)\.([a-z_]+(?:/[a-z_]+)*)`", schema_md):
    for verb in m.group(2).split("/"):
        known_ops.add(m.group(1) + "." + verb)

op_ids = {}
for path, item in spec["paths"].items():
    for method in METHODS:
        op = item.get(method)
        if op is None:
            continue
        where = "%s %s" % (method.upper(), path)
        oid = op.get("operationId")
        if not oid:
            err("%s: no operationId" % where)
            continue
        if oid in op_ids:
            err("%s: duplicate operationId %s" % (where, oid))
        op_ids[oid] = where
        if not op.get("tags"):
            err("%s: no tag" % where)

        # 1. scopes
        if "security" not in op:
            err("%s: no security declaration" % where)
        elif oid in PUBLIC:
            if op["security"] != []:
                err("%s: expected to be public (security: [])" % where)
        else:
            if not op["security"]:
                err("%s: public but not in the PUBLIC allow-list" % where)
            for alt in op["security"]:
                if len(alt) != 1:
                    err("%s: each security alternative must name exactly one scheme" % where)
                for scheme, scopes in alt.items():
                    if oid in ANY_CREDENTIAL:
                        if scopes:
                            err("%s: expected no scope" % where)
                    elif len(scopes) != 1 or scopes[0] not in SCOPES:
                        err("%s: %s must name exactly one known scope, got %r" % (where, scheme, scopes))

        # 2. emitted ops
        if method == "get":
            if "x-emits-ops" in op:
                err("%s: GET must not declare x-emits-ops" % where)
        elif oid not in ("login", "logout", "pairDevice", "createPairing"):
            if "x-emits-ops" not in op:
                err("%s: mutating operation without x-emits-ops" % where)
            for name in op.get("x-emits-ops", []):
                if name not in known_ops:
                    err("%s: x-emits-ops names %s, which is not an op in contracts/db/SCHEMA.md" % (where, name))

        # 3. examples
        def has_example(media):
            return "example" in media or bool(media.get("examples"))

        body = op.get("requestBody")
        if body:
            for ctype, media in body.get("content", {}).items():
                if not has_example(media):
                    err("%s: request body %s has no example" % (where, ctype))
        for status, resp in op.get("responses", {}).items():
            for ctype, media in resp.get("content", {}).items():
                if not has_example(media):
                    err("%s: response %s %s has no example" % (where, status, ctype))
        for param in item.get("parameters", []) + op.get("parameters", []):
            if "$ref" in param:
                param = spec["components"]["parameters"][param["$ref"].rsplit("/", 1)[1]]
            if "example" not in param and not param.get("examples"):
                err("%s: parameter %s has no example" % (where, param.get("name")))

        # 4. checklist
        if not re.search(r"`%s`" % re.escape(oid), summary):
            err("%s: operationId %s missing from SUMMARY.md" % (where, oid))

# 4b/5. SUMMARY tables only reference operations that exist
table_rows = [l for l in summary.splitlines() if l.startswith("|")]
for line in table_rows:
    cells = [c.strip() for c in line.strip("|").split("|")]
    if len(cells) < 2:
        continue
    for name in re.findall(r"`([a-z][A-Za-z]+)`", cells[-1]):
        if re.search(r"[A-Z]", name) and name not in op_ids:
            err("SUMMARY.md names operationId %s, which does not exist" % name)
for table in TABLES:
    rows = [l for l in table_rows if re.match(r"\|\s*`%s`\s*\|" % re.escape(table), l)]
    if not rows:
        err("SUMMARY.md cross-check has no row for table %s" % table)
        continue
    named = [n for n in re.findall(r"`([a-z][A-Za-z]+)`", rows[0]) if n in op_ids]
    if not named:
        err("SUMMARY.md cross-check row for %s names no existing operation" % table)

if errors:
    print("\n".join(errors))
    print("check_contract: %d problem(s)" % len(errors))
    sys.exit(1)
print("check_contract: %d operations ok" % len(op_ids))
