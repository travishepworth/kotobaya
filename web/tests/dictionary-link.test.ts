// FEAT-DICT: every "open in dictionary" affordance builds its href here; the dictionary page reads it back.
import { describe, expect, it } from 'vitest';
import {
	dictionaryHref,
	dictionaryLinkFor,
	dictionarySearchHref,
	queryFromSearch,
	seqFromSearch
} from '../src/lib/state/dictionaryLink';

const query = (href: string): string => href.slice(href.indexOf('?'));

describe('dictionary deep link', () => {
	it('struggle item links to its entry and round-trips', () => {
		const href = dictionaryHref(1358280);
		expect(href).toBe('/dictionary?seq=1358280');
		expect(seqFromSearch(query(href))).toBe(1358280);
	});
	it('ignores missing or bad seq', () => {
		expect(seqFromSearch('')).toBeNull();
		expect(seqFromSearch('?seq=abc')).toBeNull();
	});
	it('a kanji search encodes the literal and round-trips', () => {
		const href = dictionarySearchHref('取');
		expect(href).toBe('/dictionary?q=%E5%8F%96');
		expect(queryFromSearch(query(href))).toBe('取');
		expect(seqFromSearch(query(href))).toBeNull();
	});
	it('search text with reserved characters round-trips', () => {
		expect(queryFromSearch(query(dictionarySearchHref('a&b=c #?')))).toBe('a&b=c #?');
	});
	it('ignores a missing or blank query', () => {
		expect(queryFromSearch('')).toBeNull();
		expect(queryFromSearch('?q=%20%20')).toBeNull();
	});
});

describe('dictionaryLinkFor', () => {
	it('prefers the entry seq', () => {
		expect(dictionaryLinkFor({ entrySeq: 42, kanjiLiteral: '取' })).toBe('/dictionary?seq=42');
	});
	it('falls back to a search for the kanji literal', () => {
		expect(dictionaryLinkFor({ entrySeq: null, kanjiLiteral: '取' })).toBe(dictionarySearchHref('取'));
	});
	it('no link when nothing is known', () => {
		expect(dictionaryLinkFor({ entrySeq: null, kanjiLiteral: null })).toBeNull();
		expect(dictionaryLinkFor({ kanjiLiteral: ' ' })).toBeNull();
		expect(dictionaryLinkFor(null)).toBeNull();
		expect(dictionaryLinkFor(undefined)).toBeNull();
	});
});
