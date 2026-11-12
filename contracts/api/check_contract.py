#!/usr/bin/env python3
"""Contract rules for contracts/api/openapi.yaml that a generic OpenAPI linter cannot express.

usage: check_contract.py <bundled openapi.json> <SUMMARY.md> <contracts/db/SCHEMA.md>

Checks (T004 acceptance):
  1. Every operation has an operationId, a tag, and an explicit `security` declaration; every
     non-public operation names exactly one scope per alternative, from the five known scopes
     (an empty scope list is allowed only for getPrincipal: "any valid credential"). An empty
     alternative `{}` is allowed only together with `x-auth: body-key` and a known
     `x-body-key-scope` (POST /ankiconnect: token in the JSON body).
  2. Every non-GET operation declares `x-emits-ops` (possibly empty): the sync ops the server
     emits on the caller's behalf (CLAUDE.md rule 8). GET operations must not declare it.
  3. Every JSON request body and every JSON response has at least one example.
  4. Every operationId appears in SUMMARY.md (the spec-resource -> operationId checklist), and
     every operationId named in the checklist tables exists.
  5. Every op name in `x-emits-ops` is an op of contracts/db/SCHEMA.md section 4, and every
     learning-data table of SCHEMA.md 4.1 (plus media, parse_override) is mapped to at least
     one existing operation in the SUMMARY.md cross-check table.
  6. The sync bodies are exactly contracts/sync/batch.schema.json#/$defs/PushRequest,
     PushResponse and PullResponse (no API-owned wrapper), and every example that declares
     `x-example-source` equals that file of contracts/sync/examples byte-for-byte as JSON.
  7. Every `METHOD /path` named in contracts/sync/SYNC.md section 14 exists in the spec, and
     each mutating one either emits an op of that row's table (`x-emits-ops`) or is a job
     (`x-emits-ops: []`, ops minted asynchronously by the server).
"""
import json
import os
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
                if alt == {}:
                    if op.get("x-auth") != "body-key" or op.get("x-body-key-scope") not in SCOPES:
                        err("%s: empty security alternative without x-auth: body-key and x-body-key-scope" % where)
                    continue
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

# 6. sync bodies and examples copied from contracts/sync
contracts_dir = os.path.dirname(os.path.dirname(os.path.abspath(sys.argv[3])))
api_dir = os.path.join(contracts_dir, "api")


def schema_title(schema):
    if "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[1]]
    return schema.get("title")


def json_body(op, status=None):
    holder = op["requestBody"] if status is None else op["responses"][status]
    return holder["content"]["application/json"]


try:
    push = spec["paths"]["/sync/push"]["post"]
    pull = spec["paths"]["/sync/pull"]["get"]
    for label, media, title in (
        ("push request", json_body(push), "PushRequest"),
        ("push response", json_body(push, "200"), "PushResponse"),
        ("pull response", json_body(pull, "200"), "PullResponse"),
    ):
        if schema_title(media["schema"]) != title:
            err("sync %s body must be batch.schema.json#/$defs/%s, got %r" % (label, title, media["schema"]))
except KeyError as e:
    err("sync operations missing: %s" % e)


def walk(node):
    if isinstance(node, dict):
        if "x-example-source" in node:
            src = os.path.normpath(os.path.join(api_dir, node["x-example-source"]))
            try:
                expected = json.load(open(src, encoding="utf-8"))
            except OSError:
                err("x-example-source %s does not exist" % node["x-example-source"])
            else:
                if node.get("value") != expected:
                    err("example differs from its x-example-source %s" % node["x-example-source"])
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)


walk(spec["paths"])


def check_sync_table():
    path = os.path.normpath(os.path.join(api_dir, "../sync/SYNC.md"))
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        err("contracts/sync/SYNC.md not found")
        return
    m = re.search(r"^## 14\..*?(?=^## 15\.)", text, re.S | re.M)
    if not m:
        err("SYNC.md section 14 not found")
        return

    def norm(p):
        return re.sub(r"\{[^}]+\}", "{}", p)

    by_norm = {norm(p): p for p in spec["paths"]}
    rows = 0
    for line in m.group(0).splitlines():
        if not line.startswith("| `"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 3:
            continue
        rows += 1
        prefixes = set(re.findall(r"`([a-z_]+)\.[a-z_]+`", cells[1]))
        for mm in re.finditer(r"`((?:GET|POST|PUT|PATCH|DELETE)(?:/(?:GET|POST|PUT|PATCH|DELETE))*) (/[^`? ]+)`", cells[2]):
            api_path = by_norm.get(norm(mm.group(2)))
            for method in mm.group(1).split("/"):
                where = "SYNC.md 14 %s %s %s" % (cells[0], method, mm.group(2))
                op = spec["paths"].get(api_path, {}).get(method.lower()) if api_path else None
                if op is None:
                    err("%s: no such operation" % where)
                    continue
                if method == "GET":
                    continue
                emits = op.get("x-emits-ops", [])
                if emits and not any(e.split(".")[0] in prefixes for e in emits):
                    err("%s: %s emits none of %s" % (where, op.get("operationId"), sorted(prefixes)))
    if rows == 0:
        err("SYNC.md section 14 has no table rows")


check_sync_table()

if errors:
    print("\n".join(errors))
    print("check_contract: %d problem(s)" % len(errors))
    sys.exit(1)
print("check_contract: %d operations ok" % len(op_ids))
