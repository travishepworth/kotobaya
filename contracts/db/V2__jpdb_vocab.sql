-- Kotobaya database contract, migration 2 (C-11): jpdb vocabulary mapping corrections.
-- Additive only. V1__init.sql is unchanged (its Flyway checksum must stay valid).
-- Contract text: contracts/db/MEDIA_PIPELINE.md section 5; SCHEMA.md 4.4.
--
-- Both tables are server-only import configuration ("system" group): never synced, never on a
-- device, not in exports, not mutated by ops. Writers:
--   * the media-publish runner (server) inserts vocab rows and replaces a media's link rows;
--   * the API (putJpdbVocabResolution / clearJpdbVocabResolution) changes resolution fields.
-- Every writer of a resolution field, and the remap-job claim step, holds
-- pg_advisory_xact_lock(7033011) (MEDIA_PIPELINE.md 5.4).

CREATE TABLE app.jpdb_vocab (
  jpdb_vid       bigint      PRIMARY KEY CHECK (jpdb_vid > 0),
  spelling       text        NOT NULL,
  reading        text        NOT NULL,
  resolution     text        NOT NULL DEFAULT 'unresolved' CHECK (resolution IN ('unresolved', 'mapped', 'ignored')),
  entry_seq      bigint      CHECK (entry_seq > 0),
  first_seen_at  timestamptz NOT NULL,
  resolved_at    timestamptz,
  CHECK ((resolution = 'mapped') = (entry_seq IS NOT NULL)),
  CHECK ((resolution = 'unresolved') = (resolved_at IS NULL))
);
CREATE INDEX jpdb_vocab_unresolved_idx ON app.jpdb_vocab (jpdb_vid) WHERE resolution = 'unresolved';
COMMENT ON TABLE  app.jpdb_vocab IS 'jpdb vocabulary that the automatic mapper could not map to a JMdict entry, with the user''s resolution. One row per jpdb vid, created the first time a published jpdb deck contains it unmapped, never deleted (a correction persists across re-imports and media deletion). Server-only import configuration, not learning data: no op, not synced. MEDIA_PIPELINE.md 5.';
COMMENT ON COLUMN app.jpdb_vocab.jpdb_vid IS 'jpdb vocabulary id (positive). Rows without a vid are never stored here (the importer reports them as skipped).';
COMMENT ON COLUMN app.jpdb_vocab.spelling IS 'Spelling as jpdb gives it, from the most recent publish that listed the vid (may be empty).';
COMMENT ON COLUMN app.jpdb_vocab.reading IS 'Reading as jpdb gives it, from the most recent publish that listed the vid (may be empty).';
COMMENT ON COLUMN app.jpdb_vocab.resolution IS 'unresolved = in the review queue; mapped = the importer maps the vid to entry_seq; ignored = the importer keeps it unmapped and out of JobResultMedia.unmapped (its tokens still count in unmapped_token_count). Written only by the API, under advisory lock 7033011.';
COMMENT ON COLUMN app.jpdb_vocab.entry_seq IS 'Set iff resolution = mapped. Soft reference to ref.dict_entry.seq (validated live by the API when written).';
COMMENT ON COLUMN app.jpdb_vocab.first_seen_at IS 'When the media-publish runner first inserted the row (server clock).';
COMMENT ON COLUMN app.jpdb_vocab.resolved_at IS 'When the current resolution was set (server clock); NULL iff unresolved.';

CREATE TABLE app.jpdb_vocab_media (
  jpdb_vid          bigint  NOT NULL REFERENCES app.jpdb_vocab (jpdb_vid),
  media_id          uuid    NOT NULL REFERENCES app.media (id) ON DELETE CASCADE,
  occurrence_count  integer NOT NULL CHECK (occurrence_count >= 0),
  PRIMARY KEY (jpdb_vid, media_id)
);
CREATE INDEX jpdb_vocab_media_media_idx ON app.jpdb_vocab_media (media_id);
COMMENT ON TABLE  app.jpdb_vocab_media IS 'Which jpdb media contain a vid the automatic mapper could not map (whatever its resolution), as of that media''s latest publish. The media-publish runner replaces all rows of a media in its publish transaction. Rows of deleted media stay; readers join app.media and filter deleted_at IS NULL. MEDIA_PIPELINE.md 5.';
COMMENT ON COLUMN app.jpdb_vocab_media.occurrence_count IS 'Occurrences of the vid in that media''s jpdb deck (jpdb''s count).';
