#!/usr/bin/env bash
# In-cluster restore (contracts/sync/SYNC.md 6.2 / 6.3 "Restore script"). Run by restore-k8s.sh in a
# Job with the api and worker scaled to zero. Drops and recreates the application database, so it
# refuses while anything is connected (plain DROP DATABASE, no FORCE).
#
#   1. Restore the newest VERIFIED snapshot. An older one only with RESTORE_SNAPSHOT=<id> AND
#      RESTORE_CONFIRM_OLDER=yes: ops devices already purged (they trusted the newer durable_seq)
#      would be lost.
#   2. Restore the blobs (rclone copy; adds the mirror's objects, deletes nothing).
#   3. In the restored database set restore_pending = '1'. Nothing else in server_state:
#      the server finalizes at startup (new log_epoch, durable_seq, HLC).
# Re-running after a crash starts from step 1 again.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
trap 'finish restore' EXIT

: "${WORK_DIR:=/work}"
restic_need; db_env; rclone_env; wait_net
rm -rf "${WORK_DIR:?}"/* 2>/dev/null || true

NEWEST=$(restic snapshots --tag verified --latest 1 --json | jq -r 'sort_by(.time) | last | .id // empty')
[ -n "$NEWEST" ] || { FAIL_DETAIL="no verified snapshot to restore. "; log "$FAIL_DETAIL"; exit 1; }
SNAP=$NEWEST
if [ -n "${RESTORE_SNAPSHOT:-}" ]; then
  want=$(restic snapshots "$RESTORE_SNAPSHOT" --json | jq -r '.[0].id // empty')
  [ -n "$want" ] || { log "snapshot $RESTORE_SNAPSHOT not found"; exit 1; }
  if [ "$want" != "$NEWEST" ]; then
    log "WARNING: $want is NOT the newest verified snapshot ($NEWEST). Restoring it loses ops that devices"
    log "WARNING: already purged. Devices cannot recover them. Set RESTORE_CONFIRM_OLDER=yes to proceed."
    [ "${RESTORE_CONFIRM_OLDER:-}" = yes ] || { FAIL_DETAIL="older snapshot refused without confirmation. "; exit 2; }
    restic snapshots "$want" --json | jq -e '.[0].tags | index("verified")' >/dev/null \
      || { log "snapshot $want is not verified"; exit 1; }
    SNAP=$want
  fi
fi

log "1/3 restoring snapshot $SNAP"
restic restore "$SNAP" --target "$WORK_DIR/r" >/dev/null
DUMP=$(find "$WORK_DIR/r" -name db.dump | head -n 1)
COUNTS=$(find "$WORK_DIR/r" -name counts.json | head -n 1)
BLOBS=$(dirname "$DUMP")/blobs
[ -s "$DUMP" ] && [ -s "$COUNTS" ] && [ -d "$BLOBS" ] || { log "snapshot lacks db.dump, counts.json or blobs/"; exit 1; }
pg_restore --list "$DUMP" >/dev/null

psqlq -d postgres -c "DROP DATABASE IF EXISTS \"$LIVE_DB\""   # fails if api/worker still connected
psqlq -d postgres -c "CREATE DATABASE \"$LIVE_DB\" TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8'"
pg_restore --no-owner --exit-on-error -d "$LIVE_DB" "$DUMP"

log "2/3 restoring blobs"
rclone copy "$BLOBS" "src:$S3_BUCKET" --transfers 4
check_blobs "$LIVE_DB" "$BLOBS"

log "3/3 restore_pending"
psqlq -d "$LIVE_DB" <<'SQL'
INSERT INTO app.server_state (key, value, updated_at) VALUES ('restore_pending', '1', now())
ON CONFLICT (key) DO UPDATE SET value = '1', updated_at = now();
SQL
# the restored data must equal the backup (the epoch is still the old one until the server finalizes)
compare_counts "$COUNTS" "$LIVE_DB"
log "restore OK: snapshot $SNAP loaded, restore_pending set; start the server to finalize"
