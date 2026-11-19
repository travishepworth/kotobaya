-- Kotobaya database contract, migration 4 (C-29, ADR-047): the TTS audio index.
-- Additive only. V1 .. V3 are unchanged (Flyway checksums stay valid).
-- Contract text: contracts/db/SCHEMA.md 4.3 and 6.6; API `resolveSpeech`, `QueueItem.word_audio`.
--
-- System table, server-only: derived data (a cache of what VOICEVOX said), not learning data.
-- Not synced, not mutated by ops, not in exports' learning data. Losing every row loses nothing
-- but CPU time: the next resolve regenerates.

CREATE TABLE app.tts_audio (
  voice        text        NOT NULL CHECK (voice ~ '^[0-9]{1,9}$'),
  text         text        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 500),
  blob_hash    text        NOT NULL CHECK (blob_hash ~ '^[0-9a-f]{64}$'),
  duration_ms  integer     NOT NULL CHECK (duration_ms >= 0),
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (voice, text)
);
COMMENT ON TABLE  app.tts_audio IS 'Which audio blob holds the synthesized speech of (voice, text). One row per pair; the identity of TTS audio everywhere (API TtsAudio, device table tts_audio). Written only by the worker''s tts job after the object and its app.blob row are stored: INSERT ... ON CONFLICT (voice, text) DO UPDATE SET blob_hash, duration_ms, created_at = now(). Read by resolveSpeech, getReviewQueue and createDrillSession (QueueItem.word_audio). Server-only, not synced, no op. C-29, ADR-047.';
COMMENT ON COLUMN app.tts_audio.voice IS 'VOICEVOX style id as decimal digits, e.g. 3. The effective voice of the request: never empty, never a name.';
COMMENT ON COLUMN app.tts_audio.text IS 'The exact text synthesized (TtsRequest.text; for word audio the core WordAudioResolver speech text, e.g. たべる). Compared byte for byte: no folding, no normalisation here.';
COMMENT ON COLUMN app.tts_audio.blob_hash IS 'app.blob.hash of the audio (kind audio, origin tts). Soft reference: a reader that finds no app.blob row for it treats the pair as not generated and deletes this row.';
COMMENT ON COLUMN app.tts_audio.duration_ms IS 'Length of the audio (JobResultTts.duration_ms).';
COMMENT ON COLUMN app.tts_audio.created_at IS 'When the row was last written (the latest synthesis of the pair).';
