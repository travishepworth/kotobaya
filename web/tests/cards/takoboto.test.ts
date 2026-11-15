// Takoboto notes (added on the phone through the AnkiDroid provider): their row label and card preview.
// Shape: note type "Takoboto Vocab" (fixed id, android TakobotoVocab.kt: built-in templates stored as null), fields
// keyed expression/reading/meaning/sentence/sentence_translation/notes. Stored `fields` come back from the server in
// jsonb key order (shortest key first), so the first stored field is NOT the expression.
import { describe, expect, it } from 'vitest';
import { NoteTypeFromJSON, type NoteType } from '../../src/lib/api/generated';
import { noteLabel, rowLabel } from '../../src/lib/cards/model';
import { resolveNoteType } from '../../src/lib/state/cards.svelte';
import { renderPreview } from '../../src/lib/state/preview.svelte';

const TAKOBOTO_TYPE_ID = '5f934315-8fa0-8d57-8e49-84a01037668a';
const NOTE_ID = '0190abcd-1111-7222-8333-444455556666';

function takobotoType(omitNulls = false): NoteType {
	const tpl = omitNulls ? { default: true } : { default: true, front: null, back: null };
	return NoteTypeFromJSON({
		id: TAKOBOTO_TYPE_ID,
		name: 'Takoboto Vocab',
		base_type: 'vocab',
		builtin: false,
		css: '',
		created_at: '2026-11-01T00:00:00Z',
		updated_at: '2026-11-01T00:00:00Z',
		deleted_at: null,
		fields: ['expression', 'reading', 'meaning', 'sentence', 'sentence_translation', 'notes'].map((key) => ({
			key,
			label: key,
			kind: 'text'
		})),
		templates: { meaning: tpl, reading: tpl }
	});
}

// jsonb order: by key length, then bytes.
const TAKOBOTO_FIELDS = { notes: 'JLPT N5', meaning: 'cat', reading: 'ねこ', sentence: '猫が好きです。', expression: '猫' };

describe('Takoboto note label', () => {
	it('shows the word, not the first stored field, not an id', () => {
		const label = rowLabel({ fields: TAKOBOTO_FIELDS, entrySeq: 1358280 }, NOTE_ID);
		expect(label).toBe('猫');
	});
	it('without an expression: headword, then reading (never the meaning or an id)', () => {
		const fields = { meaning: 'cat', reading: 'ねこ' };
		expect(rowLabel({ fields, entrySeq: 1358280 }, NOTE_ID, new Map([[1358280, '猫']]))).toBe('猫');
		expect(rowLabel({ fields, entrySeq: 1358280 }, NOTE_ID)).toBe('ねこ');
	});
	it('fields={} with the headword still loading is unnamed (null), not an id', () => {
		expect(noteLabel({ fields: {}, entrySeq: 1358280 })).toBeNull();
		expect(rowLabel({ fields: {}, entrySeq: 1358280 }, NOTE_ID, new Map([[1358280, '猫']]))).toBe('猫');
	});
	it('missing fields object does not throw', () => {
		expect(noteLabel({ entrySeq: null })).toBeNull();
	});
});

describe('Takoboto card preview', () => {
	for (const omit of [false, true]) {
		it(`renders both sides with the word (templates ${omit ? 'omitted' : 'null'})`, () => {
			for (const side of ['front', 'back'] as const) {
				const r = renderPreview({
					noteType: takobotoType(omit),
					kind: 'meaning',
					fields: TAKOBOTO_FIELDS,
					mnemonic: '',
					entry: null,
					kanji: null,
					side
				});
				expect(r.ok).toBe(true);
				if (r.ok) {
					expect(r.card.html).not.toBe('');
					expect(r.card.html).toContain('猫');
				}
			}
		});
	}
	it('fields={} fills from the dictionary entry', () => {
		const r = renderPreview({
			noteType: takobotoType(),
			kind: 'meaning',
			fields: {},
			mnemonic: '',
			entry: {
				headword: '猫',
				headwordReading: 'ねこ',
				pitch: [],
				senses: [{ gloss: [{ lang: 'eng', text: 'cat' }] }]
			} as never,
			kanji: null,
			side: 'front'
		});
		expect(r.ok).toBe(true);
		if (r.ok) expect(r.card.html).toContain('猫');
	});
	it('a note whose type row is deleted or missing still previews, through the built-in type of its base type', () => {
		const builtin = { ...takobotoType(), id: '00000000-0000-7000-8000-000000000101', builtin: true, deletedAt: null };
		const types = new Map([[builtin.id, builtin]]);
		const nt = resolveNoteType(types, TAKOBOTO_TYPE_ID, 'vocab');
		expect(nt?.id).toBe(builtin.id);
		const r = renderPreview({ noteType: nt as NoteType, kind: 'meaning', fields: TAKOBOTO_FIELDS, mnemonic: '', entry: null, kanji: null, side: 'front' });
		expect(r.ok).toBe(true);
		expect(resolveNoteType(new Map(), TAKOBOTO_TYPE_ID, 'vocab')).toBeUndefined();
	});
});
