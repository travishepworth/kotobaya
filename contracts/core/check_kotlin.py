#!/usr/bin/env python3
"""Self-checks of the Kotlin core contract (T006). Stdlib only.

1. OpType (core/.../contracts/sync/OpCodec.kt) lists exactly the ops of contracts/sync/ops/index.json,
   in the same order, with the same entity type, entity id parts, emitter and device_applies.
2. Every public declaration, enum entry and constructor property in core/.../kotobaya/contracts/
   has a KDoc comment directly above it (annotations may sit in between). `override` members inherit.
3. @JsExport appears only in contracts/js/CoreJs.kt.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/src/commonMain/kotlin/kotobaya/contracts"
INDEX = ROOT / "contracts/sync/ops/index.json"

ENTRY = re.compile(
    r'^\s+([A-Z][A-Z0-9_]*)\("([^"]+)", "([^"]+)", listOf\(([^)]*)\), Emitter\.(CLIENT|SERVER), (true|false)\),?$'
)


def check_optype() -> list[str]:
    errors: list[str] = []
    ops = json.loads(INDEX.read_text(encoding="utf-8"))["ops"]
    entries = []
    for line in (SRC / "sync/OpCodec.kt").read_text(encoding="utf-8").splitlines():
        m = ENTRY.match(line)
        if m:
            name, wire, entity, parts, emitter, applies = m.groups()
            entries.append((wire, entity, [p.strip().strip('"') for p in parts.split(",")], emitter.lower(), applies == "true", name))
    if len(entries) != len(ops):
        errors.append(f"OpType has {len(entries)} entries, index.json {len(ops)}")
    for i, (e, o) in enumerate(zip(entries, ops)):
        expected = (o["op_type"], o["entity_type"], o["entity_id"], o["emitter"], o["device_applies"])
        if e[:5] != expected:
            errors.append(f"OpType #{i} {e[5]}: {e[:5]} != index.json {expected}")
        if e[5] != o["op_type"].replace(".", "_").upper():
            errors.append(f"OpType #{i}: constant {e[5]} should be {o['op_type'].replace('.', '_').upper()}")
    return errors


DECL = re.compile(r"^\s*public\s")
ENUM_ENTRY = re.compile(r"^\s{4}[A-Z][A-Z0-9_]*(\(.*\))?,?$")


def check_kdoc() -> list[str]:
    errors: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        in_enum = False
        for i, line in enumerate(lines):
            if re.search(r"\benum class\b", line):
                in_enum = True
            if in_enum and line.strip() == ";":
                in_enum = False
            if in_enum and line.startswith("    public companion object"):
                in_enum = False
            needs = bool(DECL.match(line)) or (in_enum and bool(ENUM_ENTRY.match(line)))
            if not needs:
                continue
            j = i - 1
            while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
                if lines[j].strip() == "" and j < i - 1:
                    break
                j -= 1
            prev = lines[j].strip() if j >= 0 else ""
            if not prev.endswith("*/"):
                errors.append(f"{path.relative_to(ROOT)}:{i + 1}: no KDoc: {line.strip()[:70]}")
            if in_enum and line.rstrip().endswith("}"):
                in_enum = False
    return errors


def check_exports() -> list[str]:
    errors: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        if path.name == "CoreJs.kt":
            continue
        if "@JsExport" in path.read_text(encoding="utf-8"):
            errors.append(f"{path.relative_to(ROOT)}: @JsExport outside contracts/js/CoreJs.kt")
    return errors


def main() -> int:
    errors = check_optype() + check_kdoc() + check_exports()
    for e in errors:
        print("FAIL", e)
    files = len(list(SRC.rglob("*.kt")))
    print(f"kotlin contracts: {files} files, {len(errors)} problems")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
