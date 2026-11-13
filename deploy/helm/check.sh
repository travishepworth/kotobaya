#!/usr/bin/env bash
# helm lint + template for the chart: default values, toggles, and assertions on the rendered output.
set -euo pipefail
cd "$(dirname "$0")"
c=kotobaya
helm lint "$c"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

render() { out=$1; shift; helm template t "$c" "$@" >"$tmp/$out"; }
has()  { grep -qE -- "$2" "$tmp/$1" || { echo "FAIL: $1 lacks: $2" >&2; exit 1; }; }
lacks() { ! grep -qE -- "$2" "$tmp/$1" || { echo "FAIL: $1 must not contain: $2" >&2; exit 1; }; }

# bundled Postgres + object store (defaults)
render default.yaml
has default.yaml 'POSTGRES_INITDB_ARGS'
has default.yaml '--locale=C.UTF-8'
has default.yaml 'path: /readyz'
has default.yaml 'path: /healthz'
has default.yaml 'initContainers:'
has default.yaml '/opt/server/bin/server'
has default.yaml 'kotobaya_worker.runner'
has default.yaml 'AUTH_PASSWORD_HASH'
has default.yaml 'AUTH_TOTP_SECRET'
lacks default.yaml 'DICT_SOURCES_JSON'
for v in DATABASE_URL S3_ENDPOINT S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY S3_REGION; do has default.yaml "name: $v"; done
has default.yaml 'name: PARSER_URL'
has default.yaml 'http://t-worker:8082'
has default.yaml 'name: JPDB_CACHE_DIR'
has default.yaml 'name: t-allow-parser'
has default.yaml 'name: t-jpdb-cache'
lacks default.yaml 'name: JPDB_API_KEY'
lacks default.yaml 'PodDisruptionBudget'
lacks default.yaml 'helm.sh/hook'

# external DB and S3, TLS with cert-manager, migrate as a pre-install+pre-upgrade hook, nginx SSE
ext_net='[{"to":[{"ipBlock":{"cidr":"10.9.9.9/32"}}],"ports":[{"port":5432},{"port":443}]}]'
render external.yaml --set postgres.enabled=false,objectStore.enabled=false,objectStore.endpoint=http://s3.example.invalid \
  --set ingress.tls.enabled=true,ingress.tls.clusterIssuer=letsencrypt,ingress.className=nginx \
  --set migrate.mode=hook,api.trustedProxies=10.0.0.0/8 --set-json "networkPolicy.egress.externalServices=$ext_net"
has external.yaml 'cidr: 10.9.9.9/32'
has external.yaml 'app.kubernetes.io/component: migrate'
# external DB/S3 with egress policies on but no externalServices: refuse to render (the app could not reach them)
if helm template t "$c" --set postgres.enabled=false >/dev/null 2>&1; then echo "FAIL: external DB without externalServices must not render" >&2; exit 1; fi
render ext-noegress.yaml --set postgres.enabled=false,networkPolicy.egress.enabled=false
lacks ext-noegress.yaml 'Egress'
lacks external.yaml 'kind: StatefulSet'
has external.yaml 'helm.sh/hook": pre-install,pre-upgrade'
lacks external.yaml 'initContainers:'
has external.yaml 'cert-manager.io/cluster-issuer'
has external.yaml 'proxy-buffering: "off"'
has external.yaml 'proxy-read-timeout'
has external.yaml 'path: /api/v1/events'
has external.yaml 'AUTH_TRUSTED_PROXIES'
has external.yaml 'http://s3.example.invalid'

# API heap (I-1 stopgap): JAVA_TOOL_OPTIONS defaults to 70% of the container limit; api.env overrides it, never duplicates it
has default.yaml 'name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=70"'
[ "$(grep -c 'name: JAVA_TOOL_OPTIONS' "$tmp/default.yaml")" = 1 ] || { echo "FAIL: JAVA_TOOL_OPTIONS must be rendered once" >&2; exit 1; }
render jvm-override.yaml --set api.env.JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50
has jvm-override.yaml 'name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=50"'
lacks jvm-override.yaml 'MaxRAMPercentage=70'
[ "$(grep -c 'name: JAVA_TOOL_OPTIONS' "$tmp/jvm-override.yaml")" = 1 ] || { echo "FAIL: a user JAVA_TOOL_OPTIONS must replace the default, not duplicate it" >&2; exit 1; }

render jpdb.yaml --set worker.jpdbApiKey.enabled=true
has jpdb.yaml 'name: JPDB_API_KEY'
# traefik: no extra events ingress; everything optional off
render dict.yaml --set-string worker.dictSourcesJson='{}'
has dict.yaml 'kind: ConfigMap'
has dict.yaml 'name: DICT_SOURCES_JSON'
render traefik.yaml --set ingress.className=traefik
lacks traefik.yaml 'proxy-buffering'
render off.yaml --set networkPolicy.enabled=false,migrate.enabled=false,ingress.enabled=false
lacks off.yaml 'kind: Ingress'
lacks off.yaml 'name: migrate'
# ntfy (T309): off by default; on renders Deployment/Service/Ingress, auth deny-all, api forwarding env
lacks default.yaml 'binwiederhier/ntfy'
lacks default.yaml 'NTFY_BASE_URL'
render ntfy.yaml --set ntfy.enabled=true,ingress.className=nginx
has ntfy.yaml 'binwiederhier/ntfy'
has ntfy.yaml 'name: NTFY_AUTH_DEFAULT_ACCESS, value: "deny-all"'
has ntfy.yaml 'name: NTFY_POST_BASE_URL, value: "http://t-ntfy:8080"'
has ntfy.yaml 'key: NTFY_TOKEN'
has ntfy.yaml 'path: /ntfy'
render ntfy-ext.yaml --set ntfy.externalBaseUrl=https://ntfy.example.invalid
lacks ntfy-ext.yaml 'binwiederhier/ntfy'
has ntfy-ext.yaml 'name: NTFY_BASE_URL, value: "https://ntfy.example.invalid"'
# ntfy refuses a base-url with a path: the default (/ntfy on the app host) goes to the api only, a path-less one to both
[ "$(grep -c 'kotobaya.kotobaya.example.invalid/ntfy' "$tmp/ntfy.yaml")" = 1 ] || { echo "FAIL: ntfy must not get a base-url with a path" >&2; exit 1; }
render ntfy-host.yaml --set ntfy.enabled=true,ntfy.baseUrl=https://ntfy.example.invalid/
[ "$(grep -c 'NTFY_BASE_URL, value: "https://ntfy.example.invalid' "$tmp/ntfy-host.yaml")" = 2 ] || { echo "FAIL: a path-less ntfy.baseUrl goes to api and ntfy" >&2; exit 1; }
has ntfy.yaml 'name: t-allow-ntfy'
has ntfy.yaml 'name: t-egress-api'

# --- T609 hardening -------------------------------------------------------------------------------
# Every workload in every toggle combination: non-root, read-only root fs (voicevox excepted), no privilege
# escalation, drop ALL, seccomp RuntimeDefault, no SA token, memory limits.
render all.yaml --set backup.enabled=true,ntfy.enabled=true,voicevox.enabled=true,optimizer.enabled=true \
  --set 'httpRoute.enabled=true,httpRoute.parentRefs[0].name=gw'
render hook.yaml --set migrate.mode=hook
python3 check_hardening.py "$tmp"/*.yaml
# network policies: default deny both ways, explicit paths only
has default.yaml 'name: t-default-deny-ingress'
has default.yaml 'name: t-egress-dns'
for p in t-egress-data t-egress-api t-egress-worker t-egress-objectstore t-allow-postgres t-allow-objectstore t-allow-parser t-allow-edge; do
  has default.yaml "name: $p\$"
done
has default.yaml '169.254.0.0/16'
has all.yaml 'name: t-egress-backup'
has all.yaml 'name: t-allow-voicevox'
lacks default.yaml 'name: t-egress-backup'
# postgres admits only the DATABASE_URL holders (not web, ntfy, voicevox)
awk '/name: t-allow-postgres$/{f=1} f&&/values:/{print; exit}' "$tmp/default.yaml" | grep -q 'values: \[api, worker, backup, backup-verify, backup-restore, optimize, migrate\]' \
  || { echo "FAIL: postgres ingress must list exactly the DATABASE_URL holders" >&2; exit 1; }
render nonp.yaml --set networkPolicy.enabled=false
lacks nonp.yaml 'kind: NetworkPolicy'
render noegress.yaml --set networkPolicy.egress.enabled=false
lacks noegress.yaml 'name: t-egress-'
render edge.yaml --set-json 'networkPolicy.ingressFrom=[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"gw"}}}]'
has edge.yaml 'kubernetes.io/metadata.name: gw'
# gateway response headers: blobs are sandboxed (S-01), everything nosniff
has all.yaml "Content-Security-Policy, value: \"sandbox; default-src 'none'\""
[ "$(grep -c 'X-Content-Type-Options, value: "nosniff"' "$tmp/all.yaml")" = 4 ] || { echo "FAIL: every HTTPRoute rule sets nosniff" >&2; exit 1; }
# no Secret values in values.yaml: secret-looking keys only ever name a Secret or a key
! grep -nEi '^\s*(password|token|secret|accessKey|secretKey)[A-Za-z]*:\s*\S' "$c/values.yaml" | grep -viE 'secretName|SecretName' \
  || { echo "FAIL: values.yaml must not carry secret values" >&2; exit 1; }
bash ../backup/check.sh
echo "helm check OK"

# VOICEVOX (BUG-4): runs the engine directly (the image's gosu entrypoint fails as non-root); the per-component
# readOnlyRootFilesystem value wins over the global container context, for both true and false.
for ro in true false; do
  render "vv-$ro.yaml" --set voicevox.enabled=true,voicevox.readOnlyRootFilesystem=$ro
  has "vv-$ro.yaml" 'command: \[/opt/python/bin/python3, ./run.py\]'
  lacks "vv-$ro.yaml" 'gosu'
  python3 - "$tmp/vv-$ro.yaml" "$ro" <<'PY'
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
d = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"].endswith("-voicevox"))
c = d["spec"]["template"]["spec"]["containers"][0]
sc = c["securityContext"]
assert c["command"][0] == "/opt/python/bin/python3", c["command"]
assert d["spec"]["template"]["spec"]["securityContext"]["runAsNonRoot"] is True
assert sc["readOnlyRootFilesystem"] is (sys.argv[2] == "true"), sc
PY
done
