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
lacks default.yaml 'PodDisruptionBudget'
lacks default.yaml 'helm.sh/hook'

# external DB and S3, TLS with cert-manager, migrate as a pre-install+pre-upgrade hook, nginx SSE
render external.yaml --set postgres.enabled=false,objectStore.enabled=false,objectStore.endpoint=http://s3.example.invalid \
  --set ingress.tls.enabled=true,ingress.tls.clusterIssuer=letsencrypt,ingress.className=nginx \
  --set migrate.mode=hook,api.trustedProxies=10.0.0.0/8
lacks external.yaml 'kind: StatefulSet'
has external.yaml 'helm.sh/hook": pre-install,pre-upgrade'
lacks external.yaml 'initContainers:'
has external.yaml 'cert-manager.io/cluster-issuer'
has external.yaml 'proxy-buffering: "off"'
has external.yaml 'proxy-read-timeout'
has external.yaml 'path: /api/v1/events'
has external.yaml 'AUTH_TRUSTED_PROXIES'
has external.yaml 'http://s3.example.invalid'

# traefik: no extra events ingress; everything optional off
render dict.yaml --set-string worker.dictSourcesJson='{}'
has dict.yaml 'kind: ConfigMap'
has dict.yaml 'name: DICT_SOURCES_JSON'
render traefik.yaml --set ingress.className=traefik
lacks traefik.yaml 'proxy-buffering'
render off.yaml --set networkPolicy.enabled=false,migrate.enabled=false,ingress.enabled=false
lacks off.yaml 'kind: Ingress'
lacks off.yaml 'name: migrate'
echo "helm check OK"
