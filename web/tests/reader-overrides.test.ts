import { describe, expect, it } from 'vitest';
import {
	buildOverridePut,
	changeEdit,
	joinEdit,
	splitEdit,
	surfacesConcatenate
} from '../src/lib/reader/overrides';
import type { TokenLike } from '../src/lib/reader/overrides';

// 食べかけた -> 食べ / かけ / た
const toks: TokenLike[] = [
	{ surface: '食べ', lemma: '食べる', reading: 'たべ', seq: 1358280 },
	{ surface: 'かけ', lemma: 'かける', reading: 'かけ', seq: 1207610 },
	{ surface: 'た', lemma: 'た', reading: 'た', seq: null }
];
const ids = { mediaId: 'm1', sentenceId: 's1' };

describe('override builder', () => {
	it('split produces tokens concatenating to match_text', () => {
		const e = splitEdit(toks, 0, 1);
		expect(e?.matchText).toBe('食べ');
		expect(e?.tokens.map((t) => t.surface)).toEqual(['食', 'べ']);
		expect(surfacesConcatenate(e!.matchText, e!.tokens)).toBe(true);
		expect(splitEdit(toks, 2, 1)).toBeNull();
		expect(splitEdit(toks, 0, 0)).toBeNull();
	});

	it('join produces one token concatenating to match_text', () => {
		const e = joinEdit(toks, 0);
		expect(e?.matchText).toBe('食べかけ');
		expect(e?.tokens).toHaveLength(1);
		expect(surfacesConcatenate(e!.matchText, e!.tokens)).toBe(true);
		expect(joinEdit(toks, 2)).toBeNull();
	});

	it('change entry keeps the surface and sets seq', () => {
		const e = changeEdit(toks, 1, { seq: 5, lemma: '掛ける', reading: 'かける' });
		expect(e?.tokens).toEqual([{ surface: 'かけ', lemma: '掛ける', seq: 5, reading: 'かける' }]);
		expect(surfacesConcatenate(e!.matchText, e!.tokens)).toBe(true);
	});

	it('put body scopes: sentence, media, global', () => {
		const e = joinEdit(toks, 0)!;
		expect(buildOverridePut(e, 'sentence', ids)).toMatchObject({ mediaId: 'm1', sentenceId: 's1' });
		const m = buildOverridePut(e, 'media', ids);
		expect(m.mediaId).toBe('m1');
		expect(m.sentenceId).toBeUndefined();
		const g = buildOverridePut(e, 'global', ids);
		expect(g.mediaId).toBeUndefined();
		expect(g.sentenceId).toBeUndefined();
	});

	it('rejects tokens that do not concatenate', () => {
		expect(() =>
			buildOverridePut({ matchText: '食べ', tokens: [{ surface: '食', seq: null }] }, 'global', ids)
		).toThrow();
	});
});
