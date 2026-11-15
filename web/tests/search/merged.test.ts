// ADR-026 on the web: one query -> dictionary words (dictionary order, flagged "In my cards"), then notes that no
// listed word covers; card-search operators list cards instead.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MergedSearchPage } from '../../src/lib/state/mergedSearch.svelte';

const entry = (seq: number, headword: string, reading: string, noteId: string | null) => ({
	seq,
	headword,
	headword_reading: reading,
	glosses: ['gloss ' + headword],
	is_common: true,
	freq_rank: null,
	note_id: noteId,
	pos: [],
	retired: false,
	status: noteId ? 'learning' : 'new'
});

const note = (id: string, seq: number | null, fields: Record<string, string>) => ({
	id,
	cards: [{ id: id + '-c', note_id: id, kind: 'meaning' }],
	created_at: '2026-11-01T00:00:00Z',
	created_by_device_id: 'd',
	deleted_at: null,
	entry_seq: seq,
	fields,
	grammar_point_id: null,
	kanji_literal: null,
	language: 'ja',
	merged_into_id: null,
	mnemonic: '',
	note_type_id: '00000000-0000-7000-8000-000000000101',
	origin: 'takoboto',
	source_media_id: null,
	source_sentence: '',
	source_sentence_id: null,
	tags: [],
	type: 'vocab',
	updated_at: '2026-11-01T00:00:00Z'
});

const json = (body: unknown): Response =>
	new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });

const calls: string[] = [];
beforeEach(() => {
	calls.length = 0;
	vi.stubGlobal(
		'fetch',
		vi.fn(async (input: string | URL) => {
			const url = new URL(String(input), 'http://localhost');
			calls.push(url.pathname + url.search);
			if (url.pathname === '/api/v1/dict/search')
				return json({ items: [entry(1, '猫', 'ねこ', null), entry(2, '子猫', 'こねこ', 'n-in')], next_cursor: null });
			if (url.pathname === '/api/v1/notes')
				return json({
					items: [
						note('n-in', 2, { meaning: 'kitten' }), // covered by the listed word 子猫
						note('n-tako', null, { notes: 'x', meaning: 'cat sleeps', reading: 'ねこ', expression: '猫寝る' })
					],
					next_cursor: null
				});
			if (url.pathname === '/api/v1/cards') return json({ items: [], next_cursor: null });
			return json({ items: [] });
		})
	);
});
afterEach(() => vi.unstubAllGlobals());

describe('merged search', () => {
	it('lists dictionary words in order with the in-my-cards flag, then notes not covered by a listed word', async () => {
		const s = new MergedSearchPage();
		s.query = '猫';
		await s.run();
		expect(s.mode).toBe('merged');
		expect(s.words.map((w) => [w.headword, w.inMyCards])).toEqual([
			['猫', false],
			['子猫', true]
		]);
		expect(s.noteRows.map((n) => n.label)).toEqual(['猫寝る']);
		expect(s.noteRows[0].meaning).toBe('cat sleeps');
		expect(calls.some((c) => c.startsWith('/api/v1/cards'))).toBe(false);
	});

	it('a card-syntax query lists cards only', async () => {
		const s = new MergedSearchPage();
		s.query = 'is:due lapses>3';
		await s.run();
		expect(s.mode).toBe('cards');
		expect(s.words).toEqual([]);
		expect(calls.some((c) => c.startsWith('/api/v1/cards'))).toBe(true);
		expect(calls.some((c) => c.startsWith('/api/v1/dict/search'))).toBe(false);
	});
});
