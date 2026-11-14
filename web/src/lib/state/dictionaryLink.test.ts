import { describe, expect, it } from 'vitest';
import { dictionaryHref, seqFromSearch } from './dictionaryLink';

describe('dictionary deep link', () => {
	it('struggle item links to its entry and round-trips', () => {
		const href = dictionaryHref(1358280);
		expect(href).toBe('/dictionary?seq=1358280');
		expect(seqFromSearch(href.slice(href.indexOf('?')))).toBe(1358280);
	});
	it('ignores missing or bad seq', () => {
		expect(seqFromSearch('')).toBeNull();
		expect(seqFromSearch('?seq=abc')).toBeNull();
	});
});
