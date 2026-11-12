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
#   5b. (F-4, SYNC.md 6.3 step 4) Record L = the live durable_seq read in that same transaction (null
#      when nothing was written) as the tag `L:<n|null>` on the verified snapshot, so a restore can
#      warn without the live database.
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

L=""
have_repo=""
for _ in 1 2 3 4 5; do   # retry: a transient network error must not look like "no repository"
  restic cat config >/dev/null 2>&1 && { have_repo=1; break; }
  sleep 4
done
if [ -z "$have_repo" ]; then
  log "restic repository not found after retries; initialising"
  restic init   # fails if the repository exists but cannot be opened (wrong password, network)
fi

# Earlier backups that never finished (OOM kill, eviction, deadline) leave no trace but a stale
# newest snapshot: alert, then carry on (this run may be the one that repairs it).
check_fresh || notify "kotobaya backup STALE" "$FAIL_DETAIL"
FAIL_DETAIL=""

# Never back up a database that was just restored and not yet finalized by the server: it is
# either half loaded or carries the previous epoch's identity (restore-k8s.sh also suspends this job).
pending() { psqlq -d "$1" -c "SELECT count(*) FROM app.server_state WHERE key = 'restore_pending'"; }
if [ "$(pending "$LIVE_DB")" != 0 ]; then
  FAIL_DETAIL="restore_pending is set in the live database: a restore is in progress or not yet finalized; refusing to back it up. "
  log "$FAIL_DETAIL"; exit 3
fi

log "1/6 pg_dump $LIVE_DB"
pg_dump -Fc --no-owner -f "$WORK_DIR/db.dump" "$LIVE_DB"
pg_restore --list "$WORK_DIR/db.dump" >/dev/null
sha256sum "$WORK_DIR/db.dump" | cut -d' ' -f1 >"$WORK_DIR/db.dump.sha256"

log "2/6 verify: restore the dump into a scratch database"
create_scratch
pg_restore --no-owner --exit-on-error -d "$SCRATCH_DB" "$WORK_DIR/db.dump"
if [ "$(pending "$SCRATCH_DB")" != 0 ]; then
  FAIL_DETAIL="the dump contains restore_pending (taken while a restore was being finalized); refusing. "
  log "$FAIL_DETAIL"; exit 3
fi
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
# Verify what was uploaded: structure + a sample of pack data, and the dump read back byte for byte.
restic check --read-data-subset=10%
want=$(cat "$WORK_DIR/db.dump.sha256")
got=$(restic dump "$SNAP" "$WORK_DIR/db.dump" | sha256sum | cut -d' ' -f1)
[ "$want" = "$got" ] || { FAIL_DETAIL="db.dump read back from the repository differs from what was dumped. "; log "$FAIL_DETAIL"; exit 1; }
log "dump read back from the repository: sha256 $got OK"
restic tag --add verified --remove pending "$SNAP"
log "snapshot verified (restic tag rewrites the snapshot, so its id changed from $SNAP)"

log "5/6 record durable_seq"
if [ -z "$E" ]; then
  log "dump has no log_epoch (server never started): durable_seq not written"
else
  res=$(psqlq -d "$LIVE_DB" -v s="$S" -v e="$E" -f "$(dirname "$0")/durable_seq.sql")
  log "$res"
  L=$(grep -o 'written durable_seq=[0-9]*' <<<"$res" | cut -d= -f2 || true)
fi
# The tag rewrite above changed the snapshot id: find the verified one by its identity tags.
VSNAP=$(restic snapshots --json --tag "kotobaya,verified,epoch:${E:-none},seq:$S" | jq -r 'sort_by(.time) | last | .id // empty')
[ -n "$VSNAP" ] || { FAIL_DETAIL="cannot find the verified snapshot to record L on. "; log "$FAIL_DETAIL"; exit 1; }
restic tag --add "L:${L:-null}" "$VSNAP"
log "recorded L=${L:-null} on snapshot $VSNAP"

log "6/6 retention (keep newest $KEEP_LAST verified; drop unverified)"
for id in $(restic snapshots --tag pending --json | jq -r '.[].id'); do restic forget "$id"; done
restic forget --tag verified --keep-last "$KEEP_LAST" --prune
log "backup OK: snapshot $SNAP, S=$S"
