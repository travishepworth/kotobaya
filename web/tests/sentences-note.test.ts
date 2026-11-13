import { describe, expect, it } from 'vitest';
import type { Sentence } from '../src/lib/api/generated';
import {
	buildSentenceNote,
	buildVocabNote,
	sentenceReading,
	SENTENCE_NOTE_TYPE,
	VOCAB_NOTE_TYPE
} from '../src/lib/sentences/noteRequest';

const sentence = {
	id: 'sent-1',
	mediaId: 'media-1',
	segmentId: 'seg-1',
	ordinal: 0,
	origin: 'media',
	text: '猫が 好き。',
	audioBlobHash: 'ab'.repeat(32),
	imageBlobHash: null,
	startMs: 1000,
	endMs: 2000,
	location: null,
	unknownCount: 1,
	grammarSpans: [],
	tokens: [
		{ surface: '猫', lemma: '猫', reading: 'ねこ', s: 0, e: 1, seq: 1, pos: [], cform: null, override: false, _class: 'content' },
		{ surface: 'が', lemma: 'が', reading: 'が', s: 1, e: 2, seq: 2, pos: [], cform: null, override: false, _class: 'particle' },
		{ surface: '好き', lemma: '好き', reading: 'すき', s: 3, e: 5, seq: 3, pos: [], cform: null, override: false, _class: 'content' },
		{ surface: '。', lemma: '。', reading: '', s: 5, e: 6, seq: null, pos: [], cform: null, override: false, _class: 'other' }
	]
} as unknown as Sentence;

describe('sentence note request builder', () => {
	it('sets type, fields, sound tag and source ids', () => {
		const n = buildSentenceNote(sentence, { id: 'n1', translation: 'I like cats.' });
		expect(n.noteTypeId).toBe(SENTENCE_NOTE_TYPE);
		expect(n.id).toBe('n1');
		expect(n.fields).toEqual({
			sentence: '猫が 好き。',
			reading: 'ねこが すき。',
			translation: 'I like cats.',
			audio: `[sound:${'ab'.repeat(32)}]`
		});
		expect(n.sourceSentence).toBe('猫が 好き。');
		expect(n.sourceSentenceId).toBe('sent-1');
		expect(n.sourceMediaId).toBe('media-1');
	});

	it('leaves audio empty without a blob and honours an edited reading and deck', () => {
		const n = buildSentenceNote({ ...sentence, audioBlobHash: null }, { id: 'n2', reading: 'x', deckId: 'd1' });
		expect(n.fields?.audio).toBe('');
		expect(n.fields?.reading).toBe('x');
		expect(n.deckId).toBe('d1');
	});

	it('reading falls back to the surface and keeps gaps', () => {
		expect(sentenceReading(sentence)).toBe('ねこが すき。');
	});

	it('vocab note carries the entry and the source ids', () => {
		const v = buildVocabNote(sentence, 3, 'n3');
		expect(v).toMatchObject({
			noteTypeId: VOCAB_NOTE_TYPE,
			entrySeq: 3,
			sourceSentenceId: 'sent-1',
			sourceMediaId: 'media-1'
		});
	});
});
