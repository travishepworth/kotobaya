-- Smoke test for V3__vocab_list.sql (C-21). Run by contracts/db/check.sh after V1, V2 and V3 are
-- applied, inside one transaction that is rolled back. The rows are the worked example of
-- VOCAB_LISTS.md section 6 (the loader's output for the two example CSV files).
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

INSERT INTO ref.dict_release (id, version, built_at, is_current, sources)
VALUES (1, '2026.11.0', '2026-11-01T00:00:00Z', false, '{}'),
       (2, '2026.11.1', '2026-11-14T00:00:00Z', true,
        '{"jlpt":{"version":"b062d4e38c4bdd0950ae1d4ec55f04b176182e03","license":"CC BY-SA 4.0 (list data: Jonathan Waller, CC BY)","url":"https://github.com/stephenmk/yomitan-jlpt-vocab"}}');

-- Minimal entries (readings/senses abbreviated). 1000010 is retired in release 2.
INSERT INTO ref.dict_entry (seq, headword, headword_reading, kanji_forms, readings, senses, freq_rank, added_release_id, retired_release_id)
VALUES (1198180, '会う', 'あう', '[{"text":"会う"}]', '[{"text":"あう"}]', '[]', 300, 1, NULL),
       (1381380, '青', 'あお', '[{"text":"青"}]', '[{"text":"あお"}]', '[]', NULL, 1, NULL),
       (1579840, '十', 'じゅう', '[{"text":"十"}]', '[{"text":"じゅう"},{"text":"とお"}]', '[]', 120, 1, NULL),
       (1005900, 'じゃ', 'じゃ', '[]', '[{"text":"じゃ"},{"text":"じゃあ"}]', '[]', NULL, 1, NULL),
       (1358280, '食べる', 'たべる', '[{"text":"食べる"}]', '[{"text":"たべる"}]', '[]', 250, 1, NULL),
       (1578850, '行く', 'いく', '[{"text":"行く"}]', '[{"text":"いく"},{"text":"ゆく"}]', '[]', 80, 1, NULL),
       (1000010, '行く', 'いく', '[{"text":"行く"}]', '[{"text":"いく"}]', '[]', NULL, 1, 2),
       (9990001, '乾', 'かん', '[{"text":"乾"}]', '[{"text":"かん"}]', '[]', NULL, 1, NULL),
       (9990002, '乾', 'かん', '[{"text":"乾"}]', '[{"text":"かん"}]', '[]', NULL, 1, NULL);

-- The dict-refresh load of release 2 (VOCAB_LISTS.md 3.4): replace everything, in one transaction.
DELETE FROM ref.vocab_list;  -- cascades to items and unmatched rows
INSERT INTO ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows)
VALUES ('jlpt-n5', 'jlpt', 0, 'JLPT N5', 2, 5, 6, 1, 0, 0),
       ('jlpt-n4', 'jlpt', 1, 'JLPT N4', 2, 1, 5, 0, 1, 3);
INSERT INTO ref.vocab_list_item (list_id, seq, position)
VALUES ('jlpt-n5', 1198180, 1), ('jlpt-n5', 1381380, 2), ('jlpt-n5', 1579840, 3),
       ('jlpt-n5', 1005900, 5), ('jlpt-n5', 1358280, 6),
       ('jlpt-n4', 1578850, 2);
INSERT INTO ref.vocab_list_unmatched (list_id, source_row, jmdict_seq, kana, kanji, reason, candidates)
VALUES ('jlpt-n4', 3, NULL, 'かん', '乾', 'ambiguous', '{9990001,9990002}'),
       ('jlpt-n4', 4, 9999999, 'ほげ', '保下', 'no_entry', '{}'),
       ('jlpt-n4', 5, NULL, 'あ', '亜', 'invalid_row', '{}');

SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows) VALUES ('jlpt-n3', 'jlpt', 2, 'JLPT N3', 2, 1, 5, 0, 0, 0)$$, 'accounting must add up');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows) VALUES ('freq-jpdb-1000', 'x', 0, 'x', 2, 0, 0, 0, 0, 0)$$, 'freq- prefix is reserved');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows) VALUES ('jlpt-x', 'jlpt', 1, 'x', 2, 0, 0, 0, 0, 0)$$, 'one list per (family, ordinal)');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_item VALUES ('jlpt-n5', 1198180, 9)$$, 'one row per (list, entry)');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_item VALUES ('jlpt-n5', 1578850, 1)$$, 'one entry per source row');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_item VALUES ('jlpt-n5', 4242424, 7)$$, 'item needs a dict_entry');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_unmatched VALUES ('jlpt-n4', 6, NULL, 'x', '', 'ambiguous', '{1}')$$, 'ambiguous needs >= 2 candidates');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_unmatched VALUES ('jlpt-n4', 6, NULL, 'x', '', 'no_entry', '{1,2}')$$, 'candidates only for ambiguous');
SELECT pg_temp.must_fail($$INSERT INTO ref.vocab_list_unmatched VALUES ('jlpt-n4', 6, NULL, 'x', '', 'gone', '{}')$$, 'unknown reason');

DO $$ BEGIN
  -- Loader invariants (VOCAB_LISTS.md 3.3), checked by the loader before commit.
  ASSERT (SELECT bool_and(l.entry_count = (SELECT count(*) FROM ref.vocab_list_item i WHERE i.list_id = l.id))
            FROM ref.vocab_list l), 'entry_count = item rows';
  ASSERT (SELECT bool_and(l.unmatched_rows = (SELECT count(*) FROM ref.vocab_list_unmatched u WHERE u.list_id = l.id))
            FROM ref.vocab_list l), 'unmatched_rows = unmatched rows';
  ASSERT NOT EXISTS (SELECT 1 FROM ref.vocab_list_item i JOIN ref.vocab_list l ON l.id = i.list_id
                      GROUP BY l.family, i.seq HAVING count(*) > 1), 'an entry is in at most one list per family';
  ASSERT NOT EXISTS (SELECT 1 FROM ref.vocab_list_item i JOIN ref.dict_entry d USING (seq)
                      WHERE d.retired_release_id IS NOT NULL), 'items are live entries';

  -- GET /deck-lists, vocab part (VOCAB_LISTS.md 4.1): lists with items, by key.
  ASSERT (SELECT string_agg(id || ':' || entry_count, ',' ORDER BY id) FROM ref.vocab_list WHERE entry_count > 0)
         = 'jlpt-n4:1,jlpt-n5:5', 'catalogue';

  -- deck-build order for jlpt-n5 (VOCAB_LISTS.md 4.3): freq_rank, then source position, then seq.
  ASSERT (SELECT array_agg(i.seq ORDER BY d.freq_rank ASC NULLS LAST, i.position, i.seq)
            FROM ref.vocab_list_item i JOIN ref.dict_entry d USING (seq)
           WHERE i.list_id = 'jlpt-n5' AND d.retired_release_id IS NULL)
         = '{1579840,1358280,1198180,1381380,1005900}', 'deck-build order';

  -- A later release replaces the lists wholesale.
  DELETE FROM ref.vocab_list;
  ASSERT (SELECT count(*) FROM ref.vocab_list_item) = 0 AND (SELECT count(*) FROM ref.vocab_list_unmatched) = 0, 'cascade';
END $$;

ROLLBACK;
\echo smoke_v3.sql: ok
