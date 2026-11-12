#!/usr/bin/env bash
# helm lint + template for the chart: default values and a few toggles.
set -euo pipefail
cd "$(dirname "$0")"
c=kotobaya
helm lint "$c"
helm template t "$c" >/dev/null
helm template t "$c" --set postgres.enabled=false,objectStore.enabled=false,objectStore.endpoint=http://s3.example.invalid,ingress.tls.enabled=true >/dev/null
helm template t "$c" --set networkPolicy.enabled=false,migrate.enabled=false,ingress.enabled=false >/dev/null
echo "helm check OK"
