-- Smoke test for V1__init.sql: one worked example row per table, plus the
-- constraints implementers rely on. Run by contracts/db/check.sh after the
-- migration, inside one transaction that is rolled back.
-- The rows double as the canonical examples referenced from SCHEMA.md.
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.must_fail(stmt text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE stmt;
  EXCEPTION WHEN check_violation OR unique_violation OR foreign_key_violation OR not_null_violation OR restrict_violation THEN
    RETURN;
  END;
  RAISE EXCEPTION 'expected constraint violation: %', label;
END $$;

-- ---------------------------------------------------------------- ref.*
INSERT INTO ref.dict_release (id, version, built_at, is_current, sources)
VALUES (1, '2026.11.0', '2026-11-01T00:00:00Z', true,
        '{"jmdict":{"version":"2026-11-01","license":"CC BY-SA 4.0","url":"https://www.edrdg.org/jmdict/j_jmdict.html"}}');

INSERT INTO ref.dict_entry (seq, headword, headword_reading, is_common, kanji_forms, readings, senses, pos, misc, freq_rank, added_release_id)
VALUES (1358280, '食べる', 'たべる', true,
        '[{"text":"食べる","common":true,"tags":[]},{"text":"喰べる","common":false,"tags":["iK"]}]',
        '[{"text":"たべる","common":true,"tags":[],"applies_to":["*"]}]',
        '[{"pos":["v1","vt"],"misc":[],"field":[],"dialect":[],"info":[],"applies_to_kanji":["*"],"applies_to_kana":["*"],"gloss":[{"lang":"eng","text":"to eat"}],"xref":[]}]',
        '{v1,vt}', '{}', 250, 1);
INSERT INTO ref.dict_entry_form VALUES (1358280, 'kanji', 0, '食べる', '食べる', true), (1358280, 'kanji', 1, '喰べる', '喰べる', false), (1358280, 'reading', 0, 'たべる', 'たべる', true);
INSERT INTO ref.dict_entry_gloss VALUES (1358280, 0, 0, 'eng', 'to eat');
INSERT INTO ref.entry_kanji VALUES (1358280, '食'), (1358280, '喰');
INSERT INTO ref.freq_list VALUES ('jpdb', 'jpdb', 0, 1, 'https://jpdb.io');
INSERT INTO ref.entry_freq VALUES (1358280, 'jpdb', 250);
INSERT INTO ref.entry_pitch VALUES (1358280, 'たべる', '{2}');
INSERT INTO ref.kanji (literal, on_readings, kun_readings, meanings, stroke_count, grade, jlpt, freq_rank, radical, components)
VALUES ('食', '{ショク,ジキ}', '{く.う,た.べる}', '{eat,food}', 9, 2, 5, 328, '食', '{食}'),
       ('𠮟', '{シツ}', '{しか.る}', '{scold}', 5, 8, NULL, NULL, '口', '{口,匕}'); -- outside the BMP: still one character
INSERT INTO ref.example_sentence VALUES (4751, 'ja', '私はりんごを食べる。', 'I eat an apple.', 1234);
INSERT INTO ref.example_sentence_entry VALUES (4751, 1358280);

SELECT pg_temp.must_fail($$INSERT INTO ref.dict_release (id, version, built_at, is_current) VALUES (2, 'x', now(), true)$$, 'second current release');

-- Japanese trigram and prefix search work.
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM ref.dict_entry_form WHERE norm LIKE '%べる%') = 3, 'substring search';
  ASSERT (SELECT count(*) FROM ref.dict_entry_form WHERE norm LIKE 'たべ%') = 1, 'prefix search';
  ASSERT (SELECT count(*) FROM ref.example_sentence WHERE text LIKE '%りんご%') = 1, 'sentence search';
END $$;

-- ---------------------------------------------------------------- system
INSERT INTO app.device (id, name, kind) VALUES
  ('01931f3e-0000-7000-8000-0000000000a1', 'Pixel 9', 'android'),
  ('01931f3e-0000-7000-8000-0000000000b2', 'Web', 'web');

INSERT INTO app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id)
VALUES ('01931f3e-1000-7000-8000-000000000001', 'Pixel 9', 'device', repeat('ab', 32), 'kb_dev_1', '{read,write,review}', '01931f3e-0000-7000-8000-0000000000a1');
SELECT pg_temp.must_fail($$INSERT INTO app.api_token (id, name, kind, token_hash, scopes) VALUES ('01931f3e-1000-7000-8000-000000000002', 'x', 'personal', repeat('cd', 32), '{read,root}')$$, 'unknown scope');
SELECT pg_temp.must_fail($$INSERT INTO app.api_token (id, name, kind, token_hash, scopes) VALUES ('01931f3e-1000-7000-8000-000000000003', 'x', 'device', repeat('ef', 32), '{read}')$$, 'device token without device');

INSERT INTO app.settings VALUES ('scheduler.day_rollover_hour', '4', '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1', '2026-11-12T10:00:00Z');
SELECT pg_temp.must_fail($$INSERT INTO app.settings VALUES ('Bad Key', '1', '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1', now())$$, 'settings key format');
SELECT pg_temp.must_fail($$INSERT INTO app.settings VALUES ('a.b', '1', '2026-11-12T10:00:00Z', now())$$, 'hlc format');

-- HLC order is byte order: later wall clock wins; same ms, higher counter wins; then device id.
DO $$ BEGIN
  ASSERT '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000b2'::app.hlc < '0193a1b2c3d5-0000-01931f3e-0000-7000-8000-0000000000a1'::app.hlc;
  ASSERT '0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1'::app.hlc > '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000b2'::app.hlc;
  ASSERT '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1'::app.hlc < '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000b2'::app.hlc;
END $$;

INSERT INTO app.change (op_id, op_type, entity_type, entity_id, device_id, hlc, payload)
VALUES ('01931f3e-2000-7000-8000-000000000001', 'word.status_set', 'word_status', '1358280',
        '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1', '{"entry_seq":1358280,"status":"known"}');
SELECT pg_temp.must_fail($$INSERT INTO app.change (op_id, op_type, entity_type, entity_id, device_id, hlc, payload)
  VALUES ('01931f3e-2000-7000-8000-000000000001', 'word.status_set', 'word_status', '1358280', '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1', '{}')$$, 'duplicate op_id');
SELECT pg_temp.must_fail($$UPDATE app.change SET payload = '{}'$$, 'change is append-only (update)');
SELECT pg_temp.must_fail($$DELETE FROM app.change$$, 'change is append-only (delete)');

INSERT INTO app.job (id, kind, payload, dedupe_key) VALUES ('01931f3e-3000-7000-8000-000000000001', 'media-parse', '{"media_id":"01931f3e-5000-7000-8000-000000000001"}', 'media-parse:01931f3e-5000-7000-8000-000000000001');
SELECT pg_temp.must_fail($$INSERT INTO app.job (id, kind, dedupe_key) VALUES ('01931f3e-3000-7000-8000-000000000002', 'media-parse', 'media-parse:01931f3e-5000-7000-8000-000000000001')$$, 'job dedupe');
SELECT pg_temp.must_fail($$UPDATE app.job SET status = 'succeeded' WHERE id = '01931f3e-3000-7000-8000-000000000001'$$, 'finished job needs finished_at');

INSERT INTO app.blob VALUES (repeat('0a', 32), 48213, 'audio/ogg', 'audio', 'tts');

-- ---------------------------------------------------------------- learning data
INSERT INTO app.grammar_point (id, title, pattern, meaning, jlpt, detection_rules, created_at, updated_at)
VALUES ('01931f3e-4000-7000-8000-000000000001', 'cannot help but', '〜ざるを得ない', 'have no choice but to', 2,
        '[{"rule":"[verb:未然形] ざる を 得 ない","draft":false}]', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z');

-- A vocab note with its two default cards.
INSERT INTO app.note (id, note_type_id, type, entry_seq, fields, tags, source_sentence, origin, created_by_device_id, created_at, updated_at, clocks)
VALUES ('01931f3e-6000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000101', 'vocab', 1358280,
        '{"sentence":"毎朝パンを食べる。","notes":""}', '{anime,frieren}', '毎朝パンを食べる。', 'takoboto',
        '01931f3e-0000-7000-8000-0000000000a1', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z',
        '{"fields.sentence":"0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1","tags":"0193a1b2c3d4-0000-01931f3e-0000-7000-8000-0000000000a1"}');
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM app.note WHERE search_text LIKE '%パンを食%') = 1, 'note search_text';
END $$;

INSERT INTO app.card (id, note_id, kind, created_at, updated_at) VALUES
  ('01931f3e-7000-7000-8000-000000000001', '01931f3e-6000-7000-8000-000000000001', 'meaning', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z'),
  ('01931f3e-7000-7000-8000-000000000002', '01931f3e-6000-7000-8000-000000000001', 'reading', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z');

SELECT pg_temp.must_fail($$INSERT INTO app.note (id, note_type_id, type, created_by_device_id, created_at, updated_at)
  VALUES ('01931f3e-6000-7000-8000-0000000000ff', '00000000-0000-7000-8000-000000000101', 'kanji', '01931f3e-0000-7000-8000-0000000000a1', now(), now())$$, 'note.type must match note_type.base_type');
SELECT pg_temp.must_fail($$INSERT INTO app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at)
  VALUES ('01931f3e-6000-7000-8000-0000000000fe', '00000000-0000-7000-8000-000000000103', 'phrase', 1358280, '01931f3e-0000-7000-8000-0000000000a1', now(), now())$$, 'entry_seq only on vocab notes');

-- "Same word added twice offline": a second LIVE note for the entry is rejected...
SELECT pg_temp.must_fail($$INSERT INTO app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at)
  VALUES ('01931f3e-6000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000101', 'vocab', 1358280, '01931f3e-0000-7000-8000-0000000000b2', now(), now())$$, 'second live note for one entry');
-- ...so op-apply inserts it already merged into the survivor, with its cards.
INSERT INTO app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at, deleted_at, merged_into_id)
VALUES ('01931f3e-6000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000101', 'vocab', 1358280,
        '01931f3e-0000-7000-8000-0000000000b2', '2026-11-12T10:05:00Z', '2026-11-12T10:05:00Z', '2026-11-12T11:00:00Z', '01931f3e-6000-7000-8000-000000000001');
INSERT INTO app.card (id, note_id, kind, created_at, updated_at, deleted_at, merged_into_id)
VALUES ('01931f3e-7000-7000-8000-000000000011', '01931f3e-6000-7000-8000-000000000002', 'meaning', '2026-11-12T10:05:00Z', '2026-11-12T10:05:00Z', '2026-11-12T11:00:00Z', '01931f3e-7000-7000-8000-000000000001');
SELECT pg_temp.must_fail($$INSERT INTO app.card (id, note_id, kind, created_at, updated_at) VALUES ('01931f3e-7000-7000-8000-0000000000ff', '01931f3e-6000-7000-8000-000000000001', 'meaning', now(), now())$$, 'second live card for (note, kind)');
SELECT pg_temp.must_fail($$UPDATE app.note SET merged_into_id = '01931f3e-6000-7000-8000-000000000002' WHERE id = '01931f3e-6000-7000-8000-000000000001'$$, 'merged note must be tombstoned');

-- Reviews: one on the survivor card, one (from the other device, offline) on the loser card.
INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, button, duration_ms) VALUES
  ('01931f3e-8000-7000-8000-000000000001', '01931f3e-7000-7000-8000-000000000001', '01931f3e-0000-7000-8000-0000000000a1',
   '0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1', '2026-11-12T10:10:00Z', 540, 'learning', 3, 'good', 4200),
  ('01931f3e-8000-7000-8000-000000000002', '01931f3e-7000-7000-8000-000000000011', '01931f3e-0000-7000-8000-0000000000b2',
   '0193a1b2c3d5-0000-01931f3e-0000-7000-8000-0000000000b2', '2026-11-12T10:20:00Z', 540, 'learning', 1, 'something', 9000);
INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, manual_action, manual_due_at)
VALUES ('01931f3e-8000-7000-8000-000000000003', '01931f3e-7000-7000-8000-000000000001', '01931f3e-0000-7000-8000-0000000000b2',
        '0193a1b2c3d6-0000-01931f3e-0000-7000-8000-0000000000b2', '2026-11-12T10:30:00Z', 'manual', 'set_due', '2026-12-01T00:00:00Z');

-- The replay set of a card = its own rows + rows of cards merged into it, in (reviewed_at, hlc, id) order
-- (SYNC.md 9.1), minus undone, minus drill/cram.
DO $$ BEGIN
  ASSERT (SELECT array_agg(r.id ORDER BY r.reviewed_at, r.hlc, r.id)
            FROM app.review_log r
            JOIN app.card c ON c.id = r.card_id
           WHERE '01931f3e-7000-7000-8000-000000000001' IN (c.id, c.merged_into_id) AND r.undone_at IS NULL
             AND r.review_type IN ('scheduled', 'learning', 'manual'))
         = ARRAY['01931f3e-8000-7000-8000-000000000001', '01931f3e-8000-7000-8000-000000000002', '01931f3e-8000-7000-8000-000000000003']::uuid[],
         'replay set and order';
END $$;

SELECT pg_temp.must_fail($$INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, button)
  VALUES ('01931f3e-8000-7000-8000-0000000000f1', '01931f3e-7000-7000-8000-000000000001', '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d7-0000-01931f3e-0000-7000-8000-0000000000a1', now(), 'scheduled', 3, 'something')$$, 'button/rating mismatch');
SELECT pg_temp.must_fail($$INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type)
  VALUES ('01931f3e-8000-7000-8000-0000000000f2', '01931f3e-7000-7000-8000-000000000001', '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d7-0000-01931f3e-0000-7000-8000-0000000000a1', now(), 'scheduled')$$, 'rating required');
SELECT pg_temp.must_fail($$INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, manual_action)
  VALUES ('01931f3e-8000-7000-8000-0000000000f3', '01931f3e-7000-7000-8000-000000000001', '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d7-0000-01931f3e-0000-7000-8000-0000000000a1', now(), 'manual', 'set_due')$$, 'set_due needs manual_due_at');
SELECT pg_temp.must_fail($$INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating)
  VALUES ('01931f3e-8000-7000-8000-0000000000f4', '01931f3e-7000-7000-8000-0000000000aa', '01931f3e-0000-7000-8000-0000000000a1', '0193a1b2c3d7-0000-01931f3e-0000-7000-8000-0000000000a1', now(), 'scheduled', 3)$$, 'review for unknown card');
-- Undo: two devices undo the same review; the smallest undo clock wins whatever the arrival order.
UPDATE app.review_log SET undone_at = '2026-11-12T10:12:00Z', undone_hlc = '0193a1b2c3d9-0000-01931f3e-0000-7000-8000-0000000000b2' WHERE id = '01931f3e-8000-7000-8000-000000000001';
UPDATE app.review_log SET undone_at = '2026-11-12T10:11:00Z', undone_hlc = '0193a1b2c3d8-0000-01931f3e-0000-7000-8000-0000000000a1' WHERE id = '01931f3e-8000-7000-8000-000000000001';
SELECT pg_temp.must_fail($$UPDATE app.review_log SET undone_at = now(), undone_hlc = '0193a1b2c3d9-0000-01931f3e-0000-7000-8000-0000000000b2' WHERE id = '01931f3e-8000-7000-8000-000000000001'$$, 'later undo must not replace earlier undo');
SELECT pg_temp.must_fail($$UPDATE app.review_log SET undone_at = NULL, undone_hlc = NULL WHERE id = '01931f3e-8000-7000-8000-000000000001'$$, 'undo cannot be cleared');
SELECT pg_temp.must_fail($$UPDATE app.review_log SET rating = 4, button = 'easy' WHERE id = '01931f3e-8000-7000-8000-000000000002'$$, 'review_log rows are immutable');
SELECT pg_temp.must_fail($$DELETE FROM app.review_log WHERE id = '01931f3e-8000-7000-8000-000000000002'$$, 'review_log rows cannot be deleted');
-- Imported history: no button, no duration, unknown UTC offset.
INSERT INTO app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, import_source)
VALUES ('01931f3e-8000-7000-8000-000000000004', '01931f3e-7000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000001',
        '018b00000000-0000-00000000-0000-7000-8000-000000000001', '2023-10-06T00:00:00Z', 'scheduled', 3, 'jpdb');
DO $$ BEGIN
  ASSERT (SELECT utc_offset_min IS NULL FROM app.review_log WHERE id = '01931f3e-8000-7000-8000-000000000004'), 'utc offset unknown by default';
END $$;

-- Card cache after a review.
UPDATE app.card SET state = 'review', stability = 3.17, difficulty = 5.28, due_at = '2026-11-15T10:10:00Z', interval_days = 3,
       last_review_at = '2026-11-12T10:10:00Z', last_review_id = '01931f3e-8000-7000-8000-000000000001', reps = 1
 WHERE id = '01931f3e-7000-7000-8000-000000000002';
SELECT pg_temp.must_fail($$UPDATE app.card SET due_at = NULL WHERE id = '01931f3e-7000-7000-8000-000000000002'$$, 'non-new card needs due_at');

INSERT INTO app.word_status (entry_seq, status, auto_status, updated_at) VALUES (1358280, 'learning', 'learning', '2026-11-12T10:10:00Z');
UPDATE app.word_status SET status = 'never_forget', override_status = 'never_forget', override_origin = 'manual',
       override_hlc = '0193a1b2c3d9-0000-01931f3e-0000-7000-8000-0000000000a1' WHERE entry_seq = 1358280;
-- Clearing the override keeps the clock so the clear wins against older sets.
UPDATE app.word_status SET status = 'learning', override_status = NULL, override_origin = NULL,
       override_hlc = '0193a1b2c3da-0000-01931f3e-0000-7000-8000-0000000000a1' WHERE entry_seq = 1358280;
SELECT pg_temp.must_fail($$UPDATE app.word_status SET status = 'known' WHERE entry_seq = 1358280$$, 'status must equal coalesce(override, auto, new)');

-- ---------------------------------------------------------------- media
INSERT INTO app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at)
VALUES ('01931f3e-5000-7000-8000-000000000001', '葬送のフリーレン', 'anime', 'file', 'parsed', 1, '2026-11-12T09:00:00Z', '2026-11-12T09:30:00Z');
INSERT INTO app.media_segment (id, media_id, ordinal, kind, title, sentence_count, token_count, unmapped_token_count)
VALUES ('01931f3e-5100-7000-8000-000000000001', '01931f3e-5000-7000-8000-000000000001', 0, 'episode', 'Episode 1', 1, 4, 0);
INSERT INTO app.sentence (id, media_id, segment_id, ordinal, text, tokens, start_ms, end_ms)
VALUES ('01931f3e-5200-7000-8000-000000000001', '01931f3e-5000-7000-8000-000000000001', '01931f3e-5100-7000-8000-000000000001', 0, '毎朝パンを食べる。',
        '[{"s":0,"e":2,"surface":"毎朝","lemma":"毎朝","reading":"まいあさ","pos":["名詞"],"cform":null,"seq":1524660,"class":"content","override":false},{"s":2,"e":4,"surface":"パン","lemma":"パン","reading":"ぱん","pos":["名詞"],"cform":null,"seq":1103090,"class":"content","override":false},{"s":4,"e":5,"surface":"を","lemma":"を","reading":"を","pos":["助詞"],"cform":null,"seq":2029010,"class":"particle","override":false},{"s":5,"e":8,"surface":"食べる","lemma":"食べる","reading":"たべる","pos":["動詞"],"cform":"終止形","seq":1358280,"class":"content","override":false}]',
        61000, 63500);
INSERT INTO app.occurrence VALUES ('01931f3e-5100-7000-8000-000000000001', 1358280, '01931f3e-5000-7000-8000-000000000001', 1, 'content', '{01931f3e-5200-7000-8000-000000000001}');
-- Manual span: natural key, soft sentence reference, reversible tombstone.
INSERT INTO app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc)
VALUES ('01931f3e-5200-7000-8000-000000000001', '01931f3e-4000-7000-8000-000000000001', 5, 8, 'manual', '0193a1b2c3db-0000-01931f3e-0000-7000-8000-0000000000b2');
INSERT INTO app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index)
VALUES ('01931f3e-5200-7000-8000-000000000001', '01931f3e-4000-7000-8000-000000000001', 5, 8, 'detected', '01931f3e-5000-7000-8000-000000000001', 0);
SELECT pg_temp.must_fail($$INSERT INTO app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin)
  VALUES ('01931f3e-5200-7000-8000-000000000001', '01931f3e-4000-7000-8000-000000000001', 0, 2, 'manual')$$, 'manual span needs hlc');
SELECT pg_temp.must_fail($$INSERT INTO app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc)
  VALUES ('01931f3e-5200-7000-8000-000000000001', '01931f3e-4000-7000-8000-000000000001', 5, 8, 'manual', '0193a1b2c3df-0000-01931f3e-0000-7000-8000-0000000000a1')$$, 'same manual span from a second device is the same row (apply as LWW update)');
-- span_removed then span_put again: the same row flips back.
UPDATE app.sentence_grammar_span SET deleted_at = '2026-11-12T13:00:00Z', hlc = '0193a1b2c3e0-0000-01931f3e-0000-7000-8000-0000000000a1' WHERE origin = 'manual';
UPDATE app.sentence_grammar_span SET deleted_at = NULL, hlc = '0193a1b2c3e1-0000-01931f3e-0000-7000-8000-0000000000b2' WHERE origin = 'manual';
-- A span for a sentence the server no longer (or never) has is accepted.
INSERT INTO app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc)
VALUES ('01931f3e-5200-7000-8000-0000000000ee', '01931f3e-4000-7000-8000-000000000001', 0, 3, 'manual', '0193a1b2c3e2-0000-01931f3e-0000-7000-8000-0000000000b2');
-- Purging a media's content removes detected spans only.
DO $$ BEGIN
  DELETE FROM app.sentence_grammar_span WHERE media_id = '01931f3e-5000-7000-8000-000000000001' AND origin = 'detected';
  ASSERT (SELECT count(*) FROM app.sentence_grammar_span) = 2, 'manual spans survive a purge';
END $$;
INSERT INTO app.parse_override (id, media_id, match_text, tokens, created_at, updated_at, hlc)
VALUES ('01931f3e-5400-7000-8000-000000000001', NULL, 'フリーレン', '[{"surface":"フリーレン","lemma":"フリーレン","seq":null}]', '2026-11-12T09:40:00Z', '2026-11-12T09:40:00Z', '0193a1b2c3dc-0000-01931f3e-0000-7000-8000-0000000000b2');

-- ---------------------------------------------------------------- decks
INSERT INTO app.deck (id, name, type, priority, media_id, content_version, created_at, updated_at)
VALUES ('01931f3e-5000-7000-8000-000000000001', '葬送のフリーレン', 'media', 10, '01931f3e-5000-7000-8000-000000000001', 1, '2026-11-12T09:30:00Z', '2026-11-12T09:30:00Z');
INSERT INTO app.deck (id, name, type, priority, created_at, updated_at)
VALUES ('01931f3e-9000-7000-8000-000000000002', 'Mined', 'user', 0, '2026-11-12T09:30:00Z', '2026-11-12T09:30:00Z');
INSERT INTO app.deck (id, name, type, query, created_at, updated_at)
VALUES ('01931f3e-9000-7000-8000-000000000003', 'Leeches', 'smart', 'tag:leech lapses>3', '2026-11-12T09:30:00Z', '2026-11-12T09:30:00Z');
SELECT pg_temp.must_fail($$INSERT INTO app.deck (id, name, type, created_at, updated_at) VALUES ('01931f3e-9000-7000-8000-0000000000ff', 'x', 'media', now(), now())$$, 'media deck needs media_id');
SELECT pg_temp.must_fail($$INSERT INTO app.deck (id, name, type, media_id, created_at, updated_at) VALUES ('01931f3e-9000-7000-8000-0000000000fe', 'x', 'media', '01931f3e-5000-7000-8000-000000000001', now(), now())$$, 'media deck id must equal media id');
INSERT INTO app.deck (id, name, type, list_key, created_at, updated_at) VALUES ('01931f3e-9000-7000-8000-000000000004', 'JLPT N3', 'list', 'jlpt-n3', '2026-11-12T09:30:00Z', '2026-11-12T09:30:00Z');
SELECT pg_temp.must_fail($$INSERT INTO app.deck (id, name, type, list_key, created_at, updated_at) VALUES ('01931f3e-9000-7000-8000-000000000005', 'JLPT N3', 'list', 'jlpt-n3', now(), now())$$, 'one live deck per list_key');

INSERT INTO app.deck_item (deck_id, item_key, entry_seq, position, occurrence_count, added_at)
VALUES ('01931f3e-5000-7000-8000-000000000001', 'e:1358280', 1358280, 1, 1, '2026-11-12T09:30:00Z');
INSERT INTO app.deck_item (deck_id, item_key, note_id, position, added_at, hlc)
VALUES ('01931f3e-9000-7000-8000-000000000002', 'n:01931f3e-6000-7000-8000-000000000001', '01931f3e-6000-7000-8000-000000000001', 1024, '2026-11-12T10:00:00Z', '0193a1b2c3d4-0002-01931f3e-0000-7000-8000-0000000000a1');
SELECT pg_temp.must_fail($$INSERT INTO app.deck_item (deck_id, item_key, entry_seq, added_at) VALUES ('01931f3e-9000-7000-8000-000000000002', 'e:1', 1358280, now())$$, 'item_key must match target');
SELECT pg_temp.must_fail($$INSERT INTO app.deck_item (deck_id, item_key, entry_seq, added_at) VALUES ('01931f3e-5000-7000-8000-000000000001', 'e:1358280', 1358280, now())$$, 'same entry twice in a deck');

-- Late match: a vocab note without an entry gets one (plain LWW register). If the value collided
-- with a live note, op-apply would merge instead of letting the unique index reject the op.
INSERT INTO app.note (id, note_type_id, type, created_by_device_id, created_at, updated_at)
VALUES ('01931f3e-6000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000101', 'vocab', '01931f3e-0000-7000-8000-0000000000a1', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z');
UPDATE app.note SET entry_seq = 1000220 WHERE id = '01931f3e-6000-7000-8000-000000000004';
UPDATE app.note SET entry_seq = 1000230 WHERE id = '01931f3e-6000-7000-8000-000000000004';
SELECT pg_temp.must_fail($$UPDATE app.note SET entry_seq = 1358280 WHERE id = '01931f3e-6000-7000-8000-000000000004'$$, 'collision must be handled by merge, not by a plain update');
UPDATE app.note SET entry_seq = 1358280, deleted_at = '2026-11-12T11:30:00Z', merged_into_id = '01931f3e-6000-7000-8000-000000000001' WHERE id = '01931f3e-6000-7000-8000-000000000004';

-- Confusable: needs a second live note.
INSERT INTO app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at)
VALUES ('01931f3e-6000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000101', 'vocab', 1169870, '01931f3e-0000-7000-8000-0000000000a1', '2026-11-12T10:00:00Z', '2026-11-12T10:00:00Z');
INSERT INTO app.confusable (note_a_id, note_b_id, reason, status, hlc, created_at, updated_at)
VALUES ('01931f3e-6000-7000-8000-000000000001', '01931f3e-6000-7000-8000-000000000003', 'look_alike', 'suggested',
        '0193a1b2c3dd-0000-00000000-0000-7000-8000-000000000001', '2026-11-12T12:00:00Z', '2026-11-12T12:00:00Z');
SELECT pg_temp.must_fail($$INSERT INTO app.confusable (note_a_id, note_b_id, reason, hlc, created_at, updated_at)
  VALUES ('01931f3e-6000-7000-8000-000000000003', '01931f3e-6000-7000-8000-000000000001', 'look_alike', '0193a1b2c3dd-0000-00000000-0000-7000-8000-000000000001', now(), now())$$, 'confusable pair must be ordered');

-- Seeds exist.
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM app.note_type WHERE builtin) = 6, 'six built-in note types';
  ASSERT (SELECT count(*) FROM app.fsrs_params) = 1, 'default preset';
  ASSERT (SELECT count(*) FROM app.device WHERE kind = 'server') = 1, 'server device';
END $$;

ROLLBACK;
\echo smoke.sql: ok
