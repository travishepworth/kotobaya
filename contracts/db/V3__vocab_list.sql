-- Kotobaya database contract, migration 3 (C-21): vocabulary lists (JLPT) for list decks.
-- Additive only. V1__init.sql and V2__jpdb_vocab.sql are unchanged (Flyway checksums stay valid).
-- Contract text: contracts/db/VOCAB_LISTS.md (source, matching rule, deck-build, deck-publish).
--
-- All three tables are reference data (ref.*), server-only: not in the device dictionary package
-- (DICT_PACKAGE.md), not synced, not mutated by ops. The only writer is the dict-refresh job,
-- which replaces every row of all three tables in the transaction that makes a new release
-- current (VOCAB_LISTS.md 3.4). Readers: GET /deck-lists (counts) and the deck-build job.

CREATE TABLE ref.vocab_list (
  id              text     PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]*$' AND id !~ '^freq-'),
  family          text     NOT NULL CHECK (family ~ '^[a-z0-9][a-z0-9_-]*$'),
  ordinal         smallint NOT NULL CHECK (ordinal >= 0),
  title           text     NOT NULL CHECK (title <> ''),
  release_id      integer  NOT NULL REFERENCES ref.dict_release (id),
  entry_count     integer  NOT NULL CHECK (entry_count >= 0),
  source_rows     integer  NOT NULL CHECK (source_rows >= 0),
  duplicate_rows  integer  NOT NULL CHECK (duplicate_rows >= 0),
  shadowed_rows   integer  NOT NULL CHECK (shadowed_rows >= 0),
  unmatched_rows  integer  NOT NULL CHECK (unmatched_rows >= 0),
  UNIQUE (family, ordinal),
  CHECK (source_rows = entry_count + duplicate_rows + shadowed_rows + unmatched_rows)
);
COMMENT ON TABLE  ref.vocab_list IS 'One loaded vocabulary list that can be enabled as a list deck (v1: jlpt-n5 .. jlpt-n1). Replaced wholesale by every dict-refresh that builds a release. Server-only. VOCAB_LISTS.md 3.';
COMMENT ON COLUMN ref.vocab_list.id IS 'The deck list_key, e.g. jlpt-n3. Never starts with freq- (that prefix belongs to frequency bands).';
COMMENT ON COLUMN ref.vocab_list.family IS 'Source family the list was loaded from, e.g. jlpt. Shadowing (an entry is in at most one list of a family) is per family.';
COMMENT ON COLUMN ref.vocab_list.ordinal IS 'Load order within the family, easiest first (jlpt: n5 = 0, n4 = 1, n3 = 2, n2 = 3, n1 = 4). An entry matched by a lower ordinal is not an item of a higher one.';
COMMENT ON COLUMN ref.vocab_list.title IS 'Display title and default deck name, e.g. JLPT N3.';
COMMENT ON COLUMN ref.vocab_list.release_id IS 'The dictionary release the rows were matched against (the release made current by the same transaction).';
COMMENT ON COLUMN ref.vocab_list.entry_count IS 'Number of ref.vocab_list_item rows of the list = GET /deck-lists item_count = items of a deck-build of the list against this release.';
COMMENT ON COLUMN ref.vocab_list.source_rows IS 'Data rows read from the source file (header and blank lines excluded). Accounting: source_rows = entry_count + duplicate_rows + shadowed_rows + unmatched_rows; nothing is dropped silently.';
COMMENT ON COLUMN ref.vocab_list.duplicate_rows IS 'Rows that matched an entry already matched by an earlier row of the same list (alternative reading or spelling of one entry).';
COMMENT ON COLUMN ref.vocab_list.shadowed_rows IS 'Rows that matched an entry already in a lower-ordinal list of the same family (the word belongs to the easier level).';
COMMENT ON COLUMN ref.vocab_list.unmatched_rows IS 'Rows that matched no live entry = the list''s rows in ref.vocab_list_unmatched.';

CREATE TABLE ref.vocab_list_item (
  list_id   text    NOT NULL REFERENCES ref.vocab_list (id) ON DELETE CASCADE,
  seq       bigint  NOT NULL REFERENCES ref.dict_entry (seq),
  position  integer NOT NULL CHECK (position > 0),
  PRIMARY KEY (list_id, seq),
  UNIQUE (list_id, position)
);
CREATE INDEX vocab_list_item_seq_idx ON ref.vocab_list_item (seq);
COMMENT ON TABLE  ref.vocab_list_item IS 'Membership of a live JMdict entry in a vocabulary list. One row per (list, entry). VOCAB_LISTS.md 3.';
COMMENT ON COLUMN ref.vocab_list_item.seq IS 'ref.dict_entry.seq; the entry was live (retired_release_id IS NULL) in vocab_list.release_id.';
COMMENT ON COLUMN ref.vocab_list_item.position IS '1-based data-row number in the source file of the first row that matched this entry (source order). Not the deck order: deck-build orders by frequency (VOCAB_LISTS.md 4.3).';

CREATE TABLE ref.vocab_list_unmatched (
  list_id     text     NOT NULL REFERENCES ref.vocab_list (id) ON DELETE CASCADE,
  source_row  integer  NOT NULL CHECK (source_row > 0),
  jmdict_seq  bigint   CHECK (jmdict_seq > 0),
  kana        text     NOT NULL,
  kanji       text     NOT NULL,
  reason      text     NOT NULL CHECK (reason IN ('invalid_row', 'no_entry', 'ambiguous')),
  candidates  bigint[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (list_id, source_row),
  CHECK ((reason = 'ambiguous') = (cardinality(candidates) >= 2))
);
COMMENT ON TABLE  ref.vocab_list_unmatched IS 'Every source row that did not become (or confirm) a list item, with the reason. The unmatched report: query it, or read JobResultDictRefresh.vocab_lists. VOCAB_LISTS.md 3.3.';
COMMENT ON COLUMN ref.vocab_list_unmatched.source_row IS '1-based data-row number in the source file (same numbering as vocab_list_item.position).';
COMMENT ON COLUMN ref.vocab_list_unmatched.jmdict_seq IS 'The row''s jmdict_seq column when it was a valid positive integer; NULL when empty or invalid.';
COMMENT ON COLUMN ref.vocab_list_unmatched.kana IS 'The row''s kana column after normalisation (may be empty for invalid_row).';
COMMENT ON COLUMN ref.vocab_list_unmatched.kanji IS 'The row''s kanji column after normalisation; empty for kana-only words.';
COMMENT ON COLUMN ref.vocab_list_unmatched.reason IS 'invalid_row = unparsable row (wrong column count, empty kana, non-numeric jmdict_seq); no_entry = no live entry by seq or by forms; ambiguous = several live entries match the forms.';
COMMENT ON COLUMN ref.vocab_list_unmatched.candidates IS 'ambiguous only: the matching live seqs, ascending (at least 2); otherwise empty.';
