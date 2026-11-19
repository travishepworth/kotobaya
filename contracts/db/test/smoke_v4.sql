-- Smoke test for V4__tts_audio.sql (C-29). Run by contracts/db/check.sh after V1 .. V4 are applied,
-- inside one transaction that is rolled back.
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

-- The worker's write: the same statement twice converges on the later blob (idempotent upsert).
INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms)
VALUES ('3', 'たべる', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 640)
ON CONFLICT (voice, text) DO UPDATE
  SET blob_hash = EXCLUDED.blob_hash, duration_ms = EXCLUDED.duration_ms, created_at = now();
INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms)
VALUES ('3', 'たべる', '7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730', 655)
ON CONFLICT (voice, text) DO UPDATE
  SET blob_hash = EXCLUDED.blob_hash, duration_ms = EXCLUDED.duration_ms, created_at = now();
-- Another voice is another row; hiragana and katakana are different texts.
INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms)
VALUES ('8', 'たべる', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 640),
       ('3', 'タベル', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 640);

DO $$
BEGIN
  IF (SELECT count(*) FROM app.tts_audio) <> 3 THEN RAISE EXCEPTION 'expected 3 rows'; END IF;
  IF (SELECT blob_hash FROM app.tts_audio WHERE voice = '3' AND text = 'たべる')
     <> '7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730' THEN
    RAISE EXCEPTION 'upsert did not keep the later blob';
  END IF;
END $$;

SELECT pg_temp.must_fail($q$INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms) VALUES ('ずんだもん', 'あ', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 1)$q$, 'voice must be digits');
SELECT pg_temp.must_fail($q$INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms) VALUES ('', 'あ', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 1)$q$, 'voice not empty');
SELECT pg_temp.must_fail($q$INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms) VALUES ('3', '', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', 1)$q$, 'text not empty');
SELECT pg_temp.must_fail($q$INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms) VALUES ('3', 'い', 'ABC', 1)$q$, 'blob_hash is a sha256');
SELECT pg_temp.must_fail($q$INSERT INTO app.tts_audio (voice, text, blob_hash, duration_ms) VALUES ('3', 'う', 'b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c', -1)$q$, 'duration >= 0');

ROLLBACK;
\echo smoke_v4.sql: ok
