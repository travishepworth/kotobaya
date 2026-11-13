// Fixtures and a fetch stub for the preview component tests (TEST-1, regression for the HOTFIX-0.1.7 loop).
// The stub answers in wire form (snake_case JSON), exactly as the generated client expects.
import { vi } from 'vitest';
import type { Card, NoteType } from '../../src/lib/api/generated';

export const CAT_SEQ = 1358280;

const ENTRY_WIRE = {
	freq_rank: 1,
	freqs: [],
	headword: '猫',
	headword_reading: 'ねこ',
	is_common: true,
	kanji: ['猫'],
	kanji_forms: [{ common: true, tags: [], text: '猫' }],
	language: 'ja',
	misc: [],
	note_id: null,
	pitch: [{ positions: [0], reading: 'ねこ' }],
	pos: ['n'],
	readings: [{ applies_to: [], common: true, tags: [], text: 'ねこ' }],
	retired: false,
	senses: [
		{
			applies_to_kana: [],
			applies_to_kanji: [],
			dialect: [],
			field: [],
			gloss: [{ lang: 'eng', text: 'cat' }],
			info: [],
			misc: [],
			pos: ['n'],
			xref: []
		}
	],
	seq: CAT_SEQ,
	status: 'new'
};

const KANJI_WIRE = {
	components: [],
	freq_rank: null,
	grade: 1,
	jlpt: 5,
	kun_readings: ['ひ', 'か'],
	literal: '日',
	meanings: ['day', 'sun'],
	nanori: [],
	note_id: null,
	on_readings: ['ニチ', 'ジツ'],
	radical: null,
	stroke_count: 4
};

const NOTE_WIRE = {
	id: '00000000-0000-7000-8000-0000000000aa',
	cards: [],
	entry_seq: null,
	fields: {},
	grammar_point_id: null,
	kanji_literal: null,
	language: 'ja',
	merged_into_id: null,
	mnemonic: '',
	note_type_id: '00000000-0000-7000-8000-000000000101',
	origin: 'manual',
	source_media_id: null,
	source_sentence: '',
	source_sentence_id: null,
	tags: [],
	type: 'vocab'
};

const jsonResponse = (body: unknown, status = 200): Response =>
	new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/** Replaces global fetch with a router for the dictionary, kanji and note endpoints used by the previews. */
export function stubPreviewFetch(): void {
	vi.stubGlobal(
		'fetch',
		vi.fn(async (input: string | URL) => {
			const path = new URL(String(input), 'http://localhost').pathname;
			if (path === `/api/v1/dict/entries/${CAT_SEQ}`) return jsonResponse(ENTRY_WIRE);
			if (path.startsWith('/api/v1/kanji/')) return jsonResponse(KANJI_WIRE);
			if (path.startsWith('/api/v1/notes/')) return jsonResponse(NOTE_WIRE);
			return jsonResponse({ code: 'not_found', status: 404, title: 'Not found', type: 'urn:kotobaya:problem:not_found' }, 404);
		})
	);
}

export const vocabNoteType: NoteType = {
	baseType: 'vocab',
	builtin: true,
	createdAt: new Date(0),
	css: '',
	deletedAt: null,
	fields: [
		{ key: 'expression', kind: 'text', label: 'Expression' },
		{ key: 'reading', kind: 'text', label: 'Reading' },
		{ key: 'meaning', kind: 'text', label: 'Meaning' }
	],
	id: '00000000-0000-7000-8000-000000000101',
	name: 'Vocab',
	templates: { meaning: { front: '{{expression}}', back: '{{meaning}}', _default: true } },
	updatedAt: new Date(0)
};

const BASE_CARD = {
	buriedUntil: null,
	createdAt: new Date(0),
	deletedAt: null,
	difficulty: null,
	dueAt: null,
	flags: 0,
	intervalDays: null,
	kind: 'meaning',
	lapses: 0,
	lastReviewAt: null,
	leech: false,
	mergedIntoId: null,
	presetId: '00000000-0000-7000-8000-000000000201',
	reps: 0,
	stability: null,
	state: 'new',
	step: null,
	suspended: false,
	updatedAt: new Date(0)
} as const;

/** A card whose owning note references a dictionary entry (`entrySeq`) or a kanji (`kanjiLiteral`). */
export function previewCard(ref: { entrySeq?: number; kanjiLiteral?: string }): Card {
	const noteId = '00000000-0000-7000-8000-0000000000aa';
	return {
		...BASE_CARD,
		id: '00000000-0000-7000-8000-0000000000cc',
		noteId,
		note: {
			entrySeq: ref.entrySeq ?? null,
			fields: { expression: '', reading: '', meaning: '' },
			grammarPointId: null,
			id: noteId,
			kanjiLiteral: ref.kanjiLiteral ?? null,
			mnemonic: '',
			noteTypeId: vocabNoteType.id,
			sourceSentence: '',
			tags: [],
			type: 'vocab'
		}
	};
}
