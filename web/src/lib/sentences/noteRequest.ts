import type { NoteCreate, Sentence } from '../api/generated';

/** Built-in note types (contracts/db). */
export const SENTENCE_NOTE_TYPE = '00000000-0000-7000-8000-000000000105';
export const VOCAB_NOTE_TYPE = '00000000-0000-7000-8000-000000000101';

/**
 * Reading line of a sentence: each token's reading (surface when it has none), with the
 * untokenised gaps (spaces, quotes) copied from the text. Offsets are UTF-16.
 */
export function sentenceReading(s: Pick<Sentence, 'text' | 'tokens'>): string {
	let out = '';
	let pos = 0;
	for (const t of s.tokens) {
		if (t.s > pos) out += s.text.slice(pos, t.s);
		out += t.reading || s.text.slice(t.s, t.e);
		pos = Math.max(pos, t.e);
	}
	if (pos < s.text.length) out += s.text.slice(pos);
	return out;
}

export interface SentenceNoteOptions {
	id: string;
	reading?: string;
	translation?: string;
	deckId?: string;
}

/** createNote body for a sentence card made from a media sentence. */
export function buildSentenceNote(s: Sentence, o: SentenceNoteOptions): NoteCreate {
	const body: NoteCreate = {
		id: o.id,
		noteTypeId: SENTENCE_NOTE_TYPE,
		fields: {
			sentence: s.text,
			reading: o.reading ?? sentenceReading(s),
			translation: o.translation ?? '',
			audio: s.audioBlobHash ? `[sound:${s.audioBlobHash}]` : ''
		},
		sourceSentence: s.text,
		sourceSentenceId: s.id,
		sourceMediaId: s.mediaId
	};
	if (o.deckId) body.deckId = o.deckId;
	return body;
}

/** createNote body for the unknown word of a sentence as a vocab note. */
export function buildVocabNote(s: Sentence, entrySeq: number, id: string, deckId?: string): NoteCreate {
	const body: NoteCreate = {
		id,
		noteTypeId: VOCAB_NOTE_TYPE,
		entrySeq,
		sourceSentence: s.text,
		sourceSentenceId: s.id,
		sourceMediaId: s.mediaId
	};
	if (deckId) body.deckId = deckId;
	return body;
}
