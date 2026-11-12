#!/usr/bin/env python3
"""Normalise the bundled OpenAPI spec for openapi-generator 7.27.0 (see README.md, "Spec normalisation").

usage: normalize_spec.py IN.json OUT.json

Applies exactly four rules. Each rule must match at least once; if the contract changes so a
rule no longer matches, this script fails (so the workaround is never silently skipped or stale).
contracts/ is never edited; only the derived copy handed to the generators is changed.
"""
import json
import sys
from typing import Any

counts = {"param_string_default": 0, "conditional_allof": 0, "false_property": 0, "const_to_enum": 0}


def fix_param(p: dict[str, Any]) -> None:
    sc = p.get("schema")
    if isinstance(sc, dict) and isinstance(sc.get("default"), str):
        del sc["default"]
        counts["param_string_default"] += 1


def walk(o: Any) -> None:
    if isinstance(o, dict):
        if isinstance(o.get("allOf"), list):
            keep = [x for x in o["allOf"] if not (isinstance(x, dict) and ("if" in x or "then" in x or "else" in x))]
            counts["conditional_allof"] += len(o["allOf"]) - len(keep)
            if keep:
                o["allOf"] = keep
            else:
                del o["allOf"]
        if "const" in o and "enum" not in o and isinstance(o["const"], str):
            o["enum"] = [o.pop("const")]
            counts["const_to_enum"] += 1
        props = o.get("properties")
        if isinstance(props, dict):
            for k in [k for k, v in props.items() if v is False]:
                del props[k]
                counts["false_property"] += 1
        for v in list(o.values()):
            walk(v)
    elif isinstance(o, list):
        for v in o:
            walk(v)


def main() -> int:
    doc = json.load(open(sys.argv[1]))
    # Rule 1: string defaults on query/path parameters: the Kotlin generator emits `Sort.MinusCreated_at`
    # style references to a non-existent nested enum. Servers apply the documented defaults.
    for p in doc.get("components", {}).get("parameters", {}).values():
        fix_param(p)
    for item in doc["paths"].values():
        for op in item.values():
            if isinstance(op, dict):
                for p in op.get("parameters", []):
                    if isinstance(p, dict):
                        fix_param(p)
    # Rule 4: string `const: x` becomes `enum: [x]` (the TypeScript generator emits calls to undefined
    # FromJSON/ToJSON helpers for a nullable const).
    # Rules 2+3: JSON Schema if/then/else and `false` property schemas (PushResult): validation-only,
    # and they crash the Python generator.
    walk(doc)
    bad = [k for k, v in counts.items() if v == 0]
    if bad:
        print(f"normalize_spec: rule(s) matched nothing: {bad}. The contract changed; revisit clients/README.md.", file=sys.stderr)
        return 1
    print(f"normalize_spec: {counts}")
    json.dump(doc, open(sys.argv[2], "w"), indent=1, sort_keys=True)
    return 0


sys.exit(main())
