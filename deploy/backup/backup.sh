#!/usr/bin/env bash
# Nightly backup (contracts/sync/SYNC.md 6.3 "Backup job").
#
# Order, and why:
#   1. pg_dump (custom format): one consistent snapshot.
#   2. Restore the dump into a scratch database (the verification). From THAT copy read E (log_epoch)
#      and S (max change.seq) and the row counts: they describe the dump, never the live database.
#   3. Mirror the blob bucket AFTER the dump (rclone copy, never deletes). Blobs are immutable and
#      content-addressed and a row is inserted only after its object is stored, so every blob the
#      dump references is already in the bucket when the mirror starts. Then check every app.blob
#      row of the dump has a file in the mirror ("verified" includes the mirror).
#   4. restic backup of dump + counts + blobs as ONE snapshot, tagged `pending`; restic check;
#      only then retag `verified`.
#   5. Only after all of that: in the live database, in one statement, write durable_seq = S
#      monotonically IF the live log_epoch still equals E, otherwise write nothing.
#   6. Retention keeps the newest verified snapshot; unverified leftovers are dropped.
# Any failure: nothing is written to durable_seq, a notification is sent, exit non-zero.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
trap 'finish backup' EXIT

: "${KEEP_LAST:=14}"
: "${WORK_DIR:=/work}"
restic_need; db_env; rclone_env; wait_net
cd "$WORK_DIR"
rm -rf "${WORK_DIR:?}"/* 2>/dev/null || true

have_repo=""
for _ in 1 2 3 4 5; do   # retry: a transient network error must not look like "no repository"
  restic cat config >/dev/null 2>&1 && { have_repo=1; break; }
  sleep 4
done
if [ -z "$have_repo" ]; then
  log "restic repository not found after retries; initialising"
  restic init   # fails if the repository exists but cannot be opened (wrong password, network)
fi

log "1/6 pg_dump $LIVE_DB"
pg_dump -Fc --no-owner -f "$WORK_DIR/db.dump" "$LIVE_DB"
pg_restore --list "$WORK_DIR/db.dump" >/dev/null

log "2/6 verify: restore the dump into a scratch database"
create_scratch
pg_restore --no-owner --exit-on-error -d "$SCRATCH_DB" "$WORK_DIR/db.dump"
db_counts "$SCRATCH_DB" >"$WORK_DIR/counts.json"
E=$(jq -r '.log_epoch // empty' "$WORK_DIR/counts.json")
S=$(jq -r '.change_max_seq' "$WORK_DIR/counts.json")
log "dump contains log_epoch=${E:-<none>} S=$S: $(jq -c . "$WORK_DIR/counts.json")"

log "3/6 mirror blobs from s3://$S3_BUCKET (after the dump)"
mkdir -p "$WORK_DIR/blobs"
rclone copy "src:$S3_BUCKET" "$WORK_DIR/blobs" --transfers 4 --checkers 8
check_blobs "$SCRATCH_DB" "$WORK_DIR/blobs"
drop_scratch

log "4/6 restic backup"
restic backup --host kotobaya --tag kotobaya --tag pending --tag "epoch:${E:-none}" --tag "seq:$S" \
  --json "$WORK_DIR/db.dump" "$WORK_DIR/counts.json" "$WORK_DIR/blobs" | tail -n 1 >"$WORK_DIR/summary.json"
SNAP=$(jq -r '.snapshot_id' "$WORK_DIR/summary.json")
[ -n "$SNAP" ] && [ "$SNAP" != null ] || { log "no snapshot id from restic"; exit 1; }
restic check
restic tag --add verified --remove pending "$SNAP"
log "snapshot verified (restic tag rewrites the snapshot, so its id changed from $SNAP)"

log "5/6 record durable_seq"
if [ -z "$E" ]; then
  log "dump has no log_epoch (server never started): durable_seq not written"
else
  res=$(psqlq -d "$LIVE_DB" -v s="$S" -v e="$E" -f "$(dirname "$0")/durable_seq.sql")
  log "$res"
fi

log "6/6 retention (keep newest $KEEP_LAST verified; drop unverified)"
for id in $(restic snapshots --tag pending --json | jq -r '.[].id'); do restic forget "$id"; done
restic forget --tag verified --keep-last "$KEEP_LAST" --prune
log "backup OK: snapshot $SNAP, S=$S"
