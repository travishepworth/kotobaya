// Smoke tests of web/src/lib/core against the real built `@kotobaya/core` (T213b).
import { describe, expect, it } from 'vitest';
import {
	completeQuery,
	parseQuery,
	previewIntervals,
	renderCard,
	type CardSchedule,
	type Preset,
	type RenderInput,
	type SearchVocabulary
} from '../../src/lib/core/index';
import renderGoldens from '../../../core/src/commonTest/kotlin/kotobaya/render/RenderGoldens.kt?raw';

const AUDIO = 'a'.repeat(64);
const SENTENCE_AUDIO = 'c'.repeat(64);
const IMAGE = 'b'.repeat(64);

/** The vocab note of core's RenderFixtures (commonTest), as the web would pass it. */
function vocabInput(kind: string, side: 'front' | 'back'): RenderInput {
	return {
		noteType: {
			baseType: 'vocab',
			fieldKeys: [
				'expression',
				'reading',
				'meaning',
				'sentence',
				'sentence_translation',
				'audio',
				'sentence_audio',
				'image',
				'notes'
			],
			templates: [
				{ kind: 'meaning', isDefault: true, front: null, back: null },
				{ kind: 'reading', isDefault: true, front: null, back: null },
				{ kind: 'listening', isDefault: false, front: null, back: null },
				{ kind: 'production', isDefault: false, front: null, back: null },
				{ kind: 'pitch', isDefault: false, front: null, back: null }
			],
			css: ''
		},
		kind,
		fields: {
			expression: '',
			reading: '',
			meaning: 'food; <i>something to eat</i>',
			sentence: '美味[おい]しい 食[た]べ 物[もの]',
			sentence_translation: 'Tasty food',
			audio: `[sound:${AUDIO}]`,
			sentence_audio: `[sound:${SENTENCE_AUDIO}]`,
			image: `<img src="blob:${IMAGE}" alt="food">`,
			notes: '<b>common</b> word'
		},
		mnemonic: '食 = person + good\nfood is <good>',
		dictionary: {
			expression: '食べ物',
			reading: 'たべもの',
			furigana: [
				{ text: '食', reading: 'た' },
				{ text: 'べ', reading: null },
				{ text: '物', reading: 'もの' }
			],
			glosses: ['food', 'provisions'],
			pitchDrops: [3, 2]
		},
		side,
		options: { furigana: true, pitch: true, showMnemonic: true, mediaUrlTemplate: '/api/v1/blobs/{sha256}' }
	};
}

/** Reads one golden html string straight from core's RenderGoldens.kt so the two never drift. */
function golden(key: string): string {
	const source = renderGoldens;
	const start = source.indexOf(`"${key}" to Golden(`);
	if (start < 0) throw new Error(`golden ${key} not found`);
	const open = source.indexOf('"""', start) + 3;
	const close = source.indexOf('"""', open);
	return source.slice(open, close);
}

const vocabulary: SearchVocabulary = {
	deckNames: ['Frieren', 'Core 2k'],
	tags: ['leech', 'n5'],
	cardKinds: ['meaning', 'reading'],
	noteTypeNames: ['Vocab'],
	presetNames: ['Default'],
	fieldKeys: ['expression', 'meaning']
};

const preset: Preset = {
	desiredRetention: 0.9,
	maximumIntervalDays: 36500,
	learningStepsSeconds: [60, 600],
	relearningStepsSeconds: [600],
	fuzz: true,
	easyDays: [1, 1, 1, 1, 1, 1, 1],
	fsrsVersion: null,
	weights: null,
	weightsVersion: 0
};

const newCard: CardSchedule = {
	state: 'new',
	step: null,
	stability: null,
	difficulty: null,
	dueAt: null,
	intervalDays: null,
	lastReviewAt: null,
	reps: 0,
	lapses: 0
};

describe('renderCard', () => {
	it('returns sanitised html with ruby for a vocab back side', () => {
		const out = renderCard(vocabInput('meaning', 'back'));
		expect(out.html).toContain('<ruby>');
		expect(out.html).not.toContain('<good>');
		expect(out.html).not.toContain('blob:');
		expect(out.audio).toEqual([AUDIO, SENTENCE_AUDIO]);
		expect(out.warnings).toEqual([]);
		expect(out.extraCss).toBe('');
	});

	it('equals the T213a golden across the JS boundary', () => {
		expect(renderCard(vocabInput('meaning', 'back')).html).toBe(golden('vocab/meaning/back'));
		expect(renderCard(vocabInput('listening', 'front')).html).toBe(golden('vocab/listening/front'));
	});

	it('strips script and handlers from field html', () => {
		const input = vocabInput('meaning', 'back');
		input.fields = { ...input.fields, meaning: '<script>alert(1)</script><b onclick="x()">ok</b>' };
		const out = renderCard(input);
		expect(out.html).not.toContain('<script');
		expect(out.html).not.toContain('onclick');
		expect(out.html).toContain('<b>ok</b>');
	});

	it('throws on an invalid side (host bug)', () => {
		const input = { ...vocabInput('meaning', 'front'), side: 'middle' as 'front' };
		expect(() => renderCard(input)).toThrow();
	});
});

describe('parseQuery', () => {
	it('accepts the spec example and returns its canonical form', () => {
		const result = parseQuery('deck:"Frieren" is:due lapses>3');
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.canonical).toBe('deck:Frieren is:due lapses>3');
		expect(result.tokens.length).toBeGreaterThan(0);
		expect(result.tokens.every((t) => t.end > t.start)).toBe(true);
	});

	it('returns errors with positions for an invalid query', () => {
		const query = 'deck:"Frieren" bogus:1';
		const result = parseQuery(query);
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.errors.length).toBeGreaterThan(0);
		const [error] = result.errors;
		expect(error.code).toBe('unknown_key');
		expect(error.messageKey).toBe('search.error.unknown_key');
		expect(error.start).toBe(query.indexOf('bogus'));
		expect(error.end).toBeGreaterThan(error.start);
		expect(result.tokens.length).toBeGreaterThan(0);
	});

	it('reports an unterminated quote at its position', () => {
		const result = parseQuery('deck:"Frieren');
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.errors[0].start).toBeGreaterThanOrEqual(0);
	});

	it('accepts the empty query', () => {
		expect(parseQuery('').ok).toBe(true);
	});
});

describe('completeQuery', () => {
	it('completes deck names after deck:', () => {
		const query = 'deck:Fr';
		const completions = completeQuery(query, query.length, vocabulary);
		expect(completions.length).toBeGreaterThan(0);
		expect(completions.some((c) => c.insert.includes('Frieren'))).toBe(true);
		for (const c of completions) {
			expect(['key', 'value', 'operator']).toContain(c.kind);
			expect(c.replaceEnd).toBeGreaterThanOrEqual(c.replaceStart);
		}
	});

	it('completes keys for an empty query and honours the limit', () => {
		expect(completeQuery('', 0, vocabulary).length).toBeGreaterThan(0);
		expect(completeQuery('', 0, vocabulary, 2).length).toBeLessThanOrEqual(2);
	});
});

describe('previewIntervals', () => {
	const now = Date.UTC(2026, 10, 13, 12, 0, 0);

	it('returns four intervals, ratings 1-4, for a new card', () => {
		const previews = previewIntervals(newCard, preset, now);
		expect(previews.map((p) => p.rating)).toEqual([1, 2, 3, 4]);
		for (const p of previews) {
			expect(p.dueAt).toBe(now + p.intervalMs);
			expect(p.intervalMs).toBeGreaterThan(0);
			expect(['new', 'learning', 'review', 'relearning']).toContain(p.state);
		}
		expect(previews[3].intervalMs).toBeGreaterThan(previews[0].intervalMs);
	});

	it('is deterministic for a review id (fuzzed)', () => {
		const review: CardSchedule = {
			...newCard,
			state: 'review',
			stability: 20,
			difficulty: 5,
			dueAt: now,
			intervalDays: 20,
			lastReviewAt: now - 20 * 86_400_000,
			reps: 5
		};
		const opts = { reviewId: '0193a1b2-0001-7000-8000-000000000999', utcOffsetMin: 540 };
		expect(previewIntervals(review, preset, now, opts)).toEqual(previewIntervals(review, preset, now, opts));
	});

	it('rejects a non-integral or negative instant', () => {
		expect(() => previewIntervals(newCard, preset, 1.5)).toThrow();
		expect(() => previewIntervals(newCard, preset, -1)).toThrow();
	});
});
