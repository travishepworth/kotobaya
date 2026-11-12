#!/usr/bin/env bash
# Restore drill against a k3d cluster that already runs the chart (deploy/k3d/up.sh).
#   CLUSTER=kotobaya-t112 deploy/k3d/up.sh        # once
#   CLUSTER=kotobaya-t112 deploy/backup/restore-drill.sh
# It: builds + imports the backup image, enables backups (chart values, dev secrets only), seeds data,
# runs `backup` and asserts durable_seq, runs `backup-verify` (pass), injects corrupt counts
# (verify must fail AND notify), mutates the DB, restores, starts the server and asserts:
#   log_epoch changed, durable_seq = S, server ready, data == backup, restore_pending gone,
#   blobs restored, and the durable_seq write rules (monotonic; skipped on epoch mismatch).
# Prints "DRILL PASS" and exits 0, or exits non-zero at the first failed assertion.
# Everything it creates is dev-only (random restic password, in-cluster repository bucket).
# Clean up with: k3d cluster delete $CLUSTER; docker rmi kotobaya-local/backup:dev
set -euo pipefail

CLUSTER=${CLUSTER:-kotobaya}; NS=${NS:-kotobaya}; REL=${REL:-kotobaya}
REG=${REG:-kotobaya-local}; TAG=${TAG:-dev}
HERE="$(cd "$(dirname "$0")" && pwd)"; CHART="$HERE/../helm/kotobaya"
k() { kubectl -n "$NS" "$@"; }
PG="$REL-postgres-0"
sql() { k exec -i "$PG" -- psql -X -v ON_ERROR_STOP=1 -At -U kotobaya -d "${DB:-kotobaya}"; }
step() { printf '\n=== %s\n' "$*"; }
assert_eq() { # name got want
  if [ "$2" = "$3" ]; then echo "ASSERT OK   $1: $2"; else echo "ASSERT FAIL $1: got '$2', want '$3'" >&2; exit 1; fi
}
assert_ne() { if [ "$2" != "$3" ]; then echo "ASSERT OK   $1: '$2' != '$3'"; else echo "ASSERT FAIL $1: both '$2'" >&2; exit 1; fi; }

kubectl config use-context "k3d-$CLUSTER" >/dev/null
k get deploy "$REL-api" >/dev/null || { echo "release not installed: run deploy/k3d/up.sh first" >&2; exit 1; }

step "build + import backup image"
docker build -q -t "$REG/backup:$TAG" "$HERE" >/dev/null
k3d image import -c "$CLUSTER" "$REG/backup:$TAG" >/dev/null

step "secrets, notification sink, enable backups"
S3K=$(k get secret kotobaya-objectstore -o jsonpath='{.data.S3_ACCESS_KEY}' | base64 -d)
S3S=$(k get secret kotobaya-objectstore -o jsonpath='{.data.S3_SECRET_KEY}' | base64 -d)
RPW=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
k create secret generic kotobaya-backup \
  --from-literal=RESTIC_REPOSITORY="s3:http://$REL-objectstore:8333/backup-drill-$RANDOM" \
  --from-literal=RESTIC_PASSWORD="$RPW" \
  --from-literal=AWS_ACCESS_KEY_ID="$S3K" --from-literal=AWS_SECRET_ACCESS_KEY="$S3S" \
  --dry-run=client -o yaml | k apply -f - >/dev/null
k apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: { name: ntfy-sink, labels: { app: ntfy-sink, app.kubernetes.io/instance: $REL } }
spec:
  containers:
    - name: sink
      image: $REG/backup:$TAG
      imagePullPolicy: Never
      command: [sh, -c, 'while true; do { sleep 1; printf "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"; } | nc -l -p 8080 >> /tmp/sink.log; done']
---
apiVersion: v1
kind: Service
metadata: { name: ntfy-sink }
spec: { selector: { app: ntfy-sink }, ports: [{ port: 8080 }] }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-ntfy-sink }
spec:
  podSelector: { matchLabels: { app: ntfy-sink } }
  policyTypes: [Ingress]
  ingress: [{ ports: [{ port: 8080 }] }]
---
apiVersion: v1
kind: Pod
metadata: { name: drill-tools, labels: { app: drill-tools, app.kubernetes.io/instance: $REL } }
spec:
  containers:
    - name: tools
      image: $REG/backup:$TAG
      imagePullPolicy: Never
      command: [sleep, "36000"]
      envFrom: [{ secretRef: { name: kotobaya-objectstore } }]   # S3_ACCESS_KEY, S3_SECRET_KEY
      env:
        - { name: S3_ENDPOINT, value: "http://$REL-objectstore:8333" }
        - { name: S3_BUCKET, value: kotobaya }
YAML
helm upgrade "$REL" "$CHART" -n "$NS" --reuse-values --wait --timeout 8m \
  --set backup.enabled=true --set backup.image.tag="$TAG" \
  --set backup.notify.url="http://ntfy-sink:8080/kotobaya-alerts" >/dev/null
k wait --for=condition=Ready pod/ntfy-sink pod/drill-tools --timeout=120s >/dev/null
tools() { k exec drill-tools -- bash -c ". /opt/backup/lib.sh; rclone_env; $*"; }

run_job() { # name -> 0 when the Job succeeds; prints its log
  local cj=$1 job="$REL-drill-$1-$RANDOM" ok bad
  shift
  kubectl -n "$NS" create job "$job" --from="cronjob/$REL-$cj" --dry-run=client -o json |
    jq --argjson extra "${1:-[]}" '.spec.template.spec.containers[0].env += $extra' | k apply -f - >/dev/null
  for _ in $(seq 1 600); do
    ok=$(k get job "$job" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)
    bad=$(k get job "$job" -o jsonpath='{.status.failed}' 2>/dev/null || true)
    [ "${ok:-0}" -ge 1 ] 2>/dev/null && { k logs "job/$job" | sed 's/^/  | /'; return 0; }
    [ "${bad:-0}" -ge 1 ] 2>/dev/null && { k logs "job/$job" | sed 's/^/  | /'; return 1; }
    sleep 2
  done
  echo "job $job timed out" >&2; return 1
}
state() { sql <<<"SELECT value FROM app.server_state WHERE key='$1'"; }

step "seed data (before backup)"
BLOB_A=$(head -c 32 /dev/urandom | sha256sum | cut -d' ' -f1)
BLOB_B=$(head -c 32 /dev/urandom | sha256sum | cut -d' ' -f1)
seed() { # $1 = tag letter, $2 = blob hash, $3 = number of notes, $4 = number of changes
  sql <<SQL
INSERT INTO app.device (id, name, kind) VALUES ('11111111-1111-7111-8111-111111111111', 'drill', 'script') ON CONFLICT DO NOTHING;
INSERT INTO ref.dict_release (id, version, built_at, is_current) VALUES (1, 'drill', now(), true) ON CONFLICT DO NOTHING;
INSERT INTO ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id)
  VALUES ($((100000 + RANDOM)), '言葉', 'ことば', '[{"text":"ことば"}]', '[]', 1);
WITH n AS (
  INSERT INTO app.note (id, note_type_id, type, created_by_device_id, created_at, updated_at, fields)
  SELECT gen_random_uuid(), (SELECT id FROM app.note_type WHERE base_type='vocab' LIMIT 1), 'vocab',
         '11111111-1111-7111-8111-111111111111', now(), now(), jsonb_build_object('expression', '$1' || g)
  FROM generate_series(1, $3) g RETURNING id),
c AS (
  INSERT INTO app.card (id, note_id, kind, created_at, updated_at)
  SELECT gen_random_uuid(), id, 'meaning', now(), now() FROM n RETURNING id)
INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, button)
  SELECT gen_random_uuid(), id, '11111111-1111-7111-8111-111111111111',
         lpad(to_hex((extract(epoch FROM now()) * 1000)::bigint), 12, '0') || '-0000-11111111-1111-7111-8111-111111111111',
         now(), 'scheduled', 3, 'good' FROM c;
INSERT INTO app.change (op_id, op_type, entity_type, entity_id, device_id, hlc, payload)
  SELECT gen_random_uuid(), 'note.created', 'note', g::text, '11111111-1111-7111-8111-111111111111',
         lpad(to_hex((extract(epoch FROM now()) * 1000)::bigint), 12, '0') || '-' || lpad(to_hex(g), 4, '0') || '-11111111-1111-7111-8111-111111111111',
         '{}' FROM generate_series(1, $4) g;
INSERT INTO app.blob (hash, size_bytes, content_type, kind) VALUES ('$2', 17, 'audio/mpeg', 'audio');
SQL
  tools "echo 'drill blob $1' | rclone rcat src:\$S3_BUCKET/$2"
}
seed a "$BLOB_A" 3 6
counts() { sql <<<"SELECT 'note='||(SELECT count(*) FROM app.note)||' card='||(SELECT count(*) FROM app.card)||' review_log='||(SELECT count(*) FROM app.review_log)||' dict_entry='||(SELECT count(*) FROM ref.dict_entry)||' blob='||(SELECT count(*) FROM app.blob)||' change_max='||(SELECT coalesce(max(seq),0) FROM app.change)"; }
COUNTS_A=$(counts); echo "state A: $COUNTS_A"
S_A=$(sql <<<"SELECT coalesce(max(seq),0) FROM app.change")
E0=$(state log_epoch)
echo "durable_seq before the first backup: [$(state durable_seq || true)] (empty on a fresh install)"

step "backup job"
run_job backup
assert_eq "durable_seq = S (max change.seq in the dump)" "$(state durable_seq)" "$S_A"
assert_eq "log_epoch unchanged by backup" "$(state log_epoch)" "$E0"

step "backup-verify (must pass)"
run_job backup-verify
echo "ASSERT OK   backup-verify exited 0"

step "failure injection: corrupt counts must fail the Job and notify"
if run_job backup-verify '[{"name":"BACKUP_VERIFY_INJECT_FAILURE","value":"1"}]'; then
  echo "ASSERT FAIL injected corruption did not fail backup-verify" >&2; exit 1
fi
echo "ASSERT OK   backup-verify Job failed on corrupted counts"
SINK=$(k exec ntfy-sink -- cat /tmp/sink.log)
case $SINK in *"backup-verify FAILED"*) echo "ASSERT OK   ntfy sink received the failure notification";;
  *) echo "ASSERT FAIL no notification in sink log: $SINK" >&2; exit 1;; esac

step "durable_seq write rules (the SQL the backup job uses)"
runsql() { k exec -i "$PG" -- psql -X -q -v ON_ERROR_STOP=1 -At -U kotobaya -d kotobaya -v s="$1" -v e="$2" <"$HERE/durable_seq.sql"; }
OUT=$(runsql 999999 "00000000-0000-7000-8000-00000000dead"); assert_eq "epoch mismatch writes nothing" "${OUT%%:*}" "skipped"
assert_eq "durable_seq untouched after mismatch" "$(state durable_seq)" "$S_A"
runsql 1 "$E0" >/dev/null; assert_eq "lower S never decreases durable_seq" "$(state durable_seq)" "$S_A"

step "restore_pending guards: backup refuses, durable_seq.sql writes nothing"
sql <<<"INSERT INTO app.server_state (key, value, updated_at) VALUES ('restore_pending', '1', now())" >/dev/null
SINK_BEFORE=$(k exec ntfy-sink -- grep -c "backup FAILED" /tmp/sink.log || true)
if run_job backup; then echo "ASSERT FAIL backup ran while restore_pending was set" >&2; exit 1; fi
echo "ASSERT OK   backup refused while restore_pending is set"
SINK_AFTER=$(k exec ntfy-sink -- grep -c "backup FAILED" /tmp/sink.log || true)
assert_ne "refused backup sent an alert" "$SINK_AFTER" "$SINK_BEFORE"
OUT=$(runsql 999999 "$E0"); assert_eq "durable_seq.sql refuses while restore_pending" "${OUT%%:*}" "skipped"
assert_eq "durable_seq untouched while restore_pending" "$(state durable_seq)" "$S_A"
assert_eq "backup-verify still passes after the refused backup" "$(run_job backup-verify | grep -c 'verify OK')" "1"
sql <<<"DELETE FROM app.server_state WHERE key='restore_pending'" >/dev/null

step "mutate: add rows after the backup (and lose a blob object)"
seed b "$BLOB_B" 2 4
COUNTS_B=$(counts); echo "state B: $COUNTS_B"
assert_ne "mutation changed the data" "$COUNTS_B" "$COUNTS_A"
tools "rclone deletefile src:\$S3_BUCKET/$BLOB_A"

step "restore (restore-k8s.sh)"
NS=$NS REL=$REL "$HERE/restore-k8s.sh"

step "assertions after restore"
E1=$(state log_epoch)
assert_ne "log_epoch changed" "$E1" "$E0"
assert_eq "durable_seq = S" "$(state durable_seq)" "$S_A"
assert_eq "restore_pending deleted" "$(sql <<<"SELECT count(*) FROM app.server_state WHERE key='restore_pending'")" "0"
assert_eq "server ready (api Available, /readyz)" "$(k get deploy "$REL-api" -o jsonpath='{.status.readyReplicas}')" "1"
assert_eq "data matches the backup" "$(counts)" "$COUNTS_A"
assert_eq "rows added after the backup are gone" "$(sql <<<"SELECT count(*) FROM app.blob WHERE hash='$BLOB_B'")" "0"
assert_eq "blob restored into the object store" "$(tools "rclone lsf src:\$S3_BUCKET/$BLOB_A" )" "$BLOB_A"

assert_eq "backup CronJobs resumed after restore" "$(k get cronjob "$REL-backup" "$REL-backup-verify" -o jsonpath='{.items[*].spec.suspend}')" "false false"
assert_eq "no previous database left behind" "$(sql <<<"SELECT count(*) FROM pg_database WHERE datname LIKE 'kotobaya_pre_restore%' OR datname LIKE 'kotobaya_scratch%'")" "0"

step "next backup after the restore uses the new epoch"
run_job backup
assert_eq "log_epoch still the finalized one" "$(state log_epoch)" "$E1"
assert_eq "durable_seq still S" "$(state durable_seq)" "$S_A"
run_job backup-verify

step "watchdog passes on a healthy install"
run_job backup-watchdog
echo "ASSERT OK   watchdog exited 0"

step "crash between the two renames: restore job killed, then recovery on re-run"
set +e; DRILL=1 KILL_BETWEEN_RENAMES=1 NS=$NS REL=$REL "$HERE/restore-k8s.sh" >/tmp/drill-kill.log 2>&1; KRC=$?; set -e
tail -n 5 /tmp/drill-kill.log
assert_ne "restore-k8s.sh failed when the job was killed" "$KRC" "0"
DB=postgres
assert_eq "live database is missing" "$(sql <<<"SELECT count(*) FROM pg_database WHERE datname='kotobaya'")" "0"
assert_eq "pre-restore copy kept" "$(sql <<<"SELECT count(*) FROM pg_database WHERE datname LIKE 'kotobaya\_pre\_restore\_%'")" "1"
assert_eq "restored copy kept (not dropped by the EXIT trap)" "$(sql <<<"SELECT count(*) FROM pg_database WHERE datname LIKE 'kotobaya\_restoring\_%'")" "1"
unset DB
assert_eq "backup CronJobs still suspended" "$(k get cronjob "$REL-backup" "$REL-backup-verify" -o jsonpath='{.items[*].spec.suspend}')" "true true"
assert_eq "watchdog CronJob never suspended" "$(k get cronjob "$REL-backup-watchdog" -o jsonpath='{.spec.suspend}')" "false"
if run_job backup-watchdog '[{"name":"STUCK_SECONDS","value":"0"}]'; then
  echo "ASSERT FAIL watchdog did not alert about CronJobs left suspended by a restore" >&2; exit 1
fi
echo "ASSERT OK   watchdog failed on backups left suspended"
case $(k exec ntfy-sink -- cat /tmp/sink.log) in *"backup-watchdog FAILED"*) echo "ASSERT OK   watchdog alert reached the sink";;
  *) echo "ASSERT FAIL no watchdog alert in the sink" >&2; exit 1;; esac
NS=$NS REL=$REL "$HERE/restore-k8s.sh"
assert_ne "log_epoch changed again after the recovered restore" "$(state log_epoch)" "$E1"
assert_eq "data matches the backup after recovery" "$(counts)" "$COUNTS_A"
assert_eq "durable_seq = S after recovery" "$(state durable_seq)" "$S_A"
assert_eq "backup CronJobs resumed after recovery" "$(k get cronjob "$REL-backup" "$REL-backup-verify" -o jsonpath='{.items[*].spec.suspend}')" "false false"
assert_eq "no leftover databases after recovery" "$(DB=postgres sql <<<"SELECT count(*) FROM pg_database WHERE datname LIKE 'kotobaya\_%'")" "0"

echo
echo "DRILL PASS: epoch $E0 -> $E1, durable_seq=$S_A, data == backup ($COUNTS_A)"
