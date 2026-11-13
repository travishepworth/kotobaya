#!/usr/bin/env python3
"""Assert the T609 hardening on rendered chart output (docs/SECURITY.md checklist).

Usage: check_hardening.py <rendered.yaml>...   (helm template output; needs PyYAML)

Every pod template of a Deployment, StatefulSet, Job or CronJob must have:
  - pod: runAsNonRoot, a non-zero runAsUser, seccompProfile RuntimeDefault,
    automountServiceAccountToken false (except pods that name a serviceAccountName);
  - every container and init container: allowPrivilegeEscalation false, capabilities drop [ALL],
    readOnlyRootFilesystem true (voicevox: the documented exception, see values.yaml), resources.limits.memory.
Every NetworkPolicy-protected component is checked by deploy/helm/check.sh with greps.
"""

import sys

import yaml

WORKLOADS = {"Deployment", "StatefulSet", "Job", "CronJob"}
WRITABLE_ROOT_OK = {"voicevox"}


def pod_spec(doc: dict) -> dict:
    spec = doc["spec"]
    if doc["kind"] == "CronJob":
        spec = spec["jobTemplate"]["spec"]
    return spec["template"]["spec"]


def check(doc: dict) -> list[str]:
    name = f'{doc["kind"]}/{doc["metadata"]["name"]}'
    pod = pod_spec(doc)
    errs: list[str] = []
    psc = pod.get("securityContext") or {}
    if psc.get("runAsNonRoot") is not True:
        errs.append(f"{name}: pod runAsNonRoot is not true")
    if not psc.get("runAsUser"):
        errs.append(f"{name}: pod runAsUser missing or 0")
    if (psc.get("seccompProfile") or {}).get("type") != "RuntimeDefault":
        errs.append(f"{name}: pod seccompProfile is not RuntimeDefault")
    if "serviceAccountName" not in pod and pod.get("automountServiceAccountToken") is not False:
        errs.append(f"{name}: automountServiceAccountToken is not false")
    for c in pod.get("initContainers", []) + pod["containers"]:
        cn = f'{name}[{c["name"]}]'
        sc = c.get("securityContext") or {}
        if sc.get("allowPrivilegeEscalation") is not False:
            errs.append(f"{cn}: allowPrivilegeEscalation is not false")
        if (sc.get("capabilities") or {}).get("drop") != ["ALL"]:
            errs.append(f"{cn}: capabilities.drop is not [ALL]")
        if sc.get("readOnlyRootFilesystem") is not True and c["name"] not in WRITABLE_ROOT_OK:
            errs.append(f"{cn}: readOnlyRootFilesystem is not true")
        if not ((c.get("resources") or {}).get("limits") or {}).get("memory"):
            errs.append(f"{cn}: no memory limit")
    return errs


def main(paths: list[str]) -> int:
    errs: list[str] = []
    seen = 0
    for p in paths:
        with open(p, encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if doc and doc.get("kind") in WORKLOADS:
                    seen += 1
                    errs += check(doc)
    for e in errs:
        print(f"FAIL: {e}", file=sys.stderr)
    if seen == 0:
        print("FAIL: no workloads rendered", file=sys.stderr)
        return 1
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
