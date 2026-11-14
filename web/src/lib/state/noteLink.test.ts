import { describe, expect, it } from 'vitest';
import { noteHref, withPinned } from './noteLink';

describe('noteHref', () => {
	it('links to /notes with the note id', () => {
		expect(noteHref('abc')).toBe('/notes?note=abc');
		expect(noteHref('a b', 'x&y')).toBe('/notes?note=a%20b&field=x%26y');
	});
});

describe('withPinned', () => {
	it('keeps a linked note that the list reload did not return', () => {
		expect(withPinned([{ id: '2' }], { id: '1' }).map((n) => n.id)).toEqual(['1', '2']);
	});
	it('does not duplicate it, and passes through without a pin', () => {
		expect(withPinned([{ id: '1' }], { id: '1' })).toHaveLength(1);
		expect(withPinned([{ id: '1' }], null)).toHaveLength(1);
	});
});
