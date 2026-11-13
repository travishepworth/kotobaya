-- Smoke test for V2__jpdb_vocab.sql (C-11). Run by contracts/db/check.sh after V1 and V2 are
-- applied, inside one transaction that is rolled back. The rows are the worked example of
-- MEDIA_PIPELINE.md section 5.
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.must_fail(stmt text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE stmt;
  EXCEPTION WHEN check_violation OR unique_violation OR foreign_key_violation OR not_null_violation THEN
    RETURN;
  END;
  RAISE EXCEPTION 'expected constraint violation: %', label;
END $$;

INSERT INTO app.media (id, title, kind, source, jpdb_id, parse_status, content_version, content_blob_hash, created_at, updated_at)
VALUES ('0193a1b2-0008-7000-8000-000000000001', '葬送のフリーレン', 'anime', 'jpdb', 'anime/5370', 'parsed', 4,
        'abababababababababababababababababababababababababababababababab', '2026-11-12T09:00:00Z', '2026-11-13T09:00:00Z');

-- The publish runner inserts a row the first time a vid is seen unmapped (ON CONFLICT: refresh spelling/reading only).
INSERT INTO app.jpdb_vocab (jpdb_vid, spelling, reading, first_seen_at)
VALUES (9300001, 'フリーレン', 'ふりーれん', '2026-11-12T09:00:00Z'),
       (9300002, 'ヒンメル', 'ひんめる', '2026-11-12T09:00:00Z'),
       (9300003, '魔族', 'まぞく', '2026-11-12T09:00:00Z')
ON CONFLICT (jpdb_vid) DO UPDATE SET spelling = EXCLUDED.spelling, reading = EXCLUDED.reading;
-- The API resolves two of them (under pg_advisory_xact_lock(7033011)).
SELECT pg_advisory_xact_lock(7033011);
UPDATE app.jpdb_vocab SET resolution = 'ignored', resolved_at = '2026-11-13T08:00:00Z' WHERE jpdb_vid = 9300002;
UPDATE app.jpdb_vocab SET resolution = 'mapped', entry_seq = 1612400, resolved_at = '2026-11-13T08:01:00Z' WHERE jpdb_vid = 9300003;
-- Replace the media's link rows (publish transaction).
DELETE FROM app.jpdb_vocab_media WHERE media_id = '0193a1b2-0008-7000-8000-000000000001';
INSERT INTO app.jpdb_vocab_media (jpdb_vid, media_id, occurrence_count)
VALUES (9300001, '0193a1b2-0008-7000-8000-000000000001', 312),
       (9300002, '0193a1b2-0008-7000-8000-000000000001', 40),
       (9300003, '0193a1b2-0008-7000-8000-000000000001', 75);

SELECT pg_temp.must_fail($$UPDATE app.jpdb_vocab SET resolution = 'mapped' WHERE jpdb_vid = 9300001$$, 'mapped needs entry_seq and resolved_at');
SELECT pg_temp.must_fail($$UPDATE app.jpdb_vocab SET entry_seq = 1358280 WHERE jpdb_vid = 9300002$$, 'entry_seq only when mapped');
SELECT pg_temp.must_fail($$UPDATE app.jpdb_vocab SET resolution = 'unresolved' WHERE jpdb_vid = 9300002$$, 'unresolved clears resolved_at');
SELECT pg_temp.must_fail($$INSERT INTO app.jpdb_vocab (jpdb_vid, spelling, reading, first_seen_at) VALUES (0, 'x', 'x', now())$$, 'vid must be positive');
SELECT pg_temp.must_fail($$INSERT INTO app.jpdb_vocab_media VALUES (9399999, '0193a1b2-0008-7000-8000-000000000001', 1)$$, 'link needs a vocab row');
SELECT pg_temp.must_fail($$INSERT INTO app.jpdb_vocab_media VALUES (9300001, '0193a1b2-0008-7000-8000-0000000000ff', 1)$$, 'link needs a media row');

-- The review queue query (listJpdbVocab, status = unresolved): live media only, ordered by total.
DO $$ BEGIN
  ASSERT (SELECT array_agg(v.jpdb_vid ORDER BY t.total DESC, v.jpdb_vid)
            FROM app.jpdb_vocab v
            JOIN LATERAL (SELECT sum(l.occurrence_count) AS total
                            FROM app.jpdb_vocab_media l JOIN app.media m ON m.id = l.media_id
                           WHERE l.jpdb_vid = v.jpdb_vid AND m.deleted_at IS NULL) t ON t.total IS NOT NULL
           WHERE v.resolution = 'unresolved') = '{9300001}', 'queue lists the unresolved vid';
  -- Clearing a correction puts the vid back into the queue.
  UPDATE app.jpdb_vocab SET resolution = 'unresolved', entry_seq = NULL, resolved_at = NULL WHERE jpdb_vid = 9300003;
  ASSERT (SELECT count(*) FROM app.jpdb_vocab WHERE resolution = 'unresolved') = 2, 'cleared correction is unresolved again';
  -- A deleted media hides its links from the queue but keeps the rows.
  UPDATE app.media SET deleted_at = '2026-11-13T10:00:00Z' WHERE id = '0193a1b2-0008-7000-8000-000000000001';
  ASSERT (SELECT count(*) FROM app.jpdb_vocab_media l JOIN app.media m ON m.id = l.media_id WHERE m.deleted_at IS NULL) = 0, 'deleted media hidden';
  ASSERT (SELECT count(*) FROM app.jpdb_vocab_media) = 3, 'link rows kept';
END $$;

ROLLBACK;
\echo smoke_v2.sql: ok
