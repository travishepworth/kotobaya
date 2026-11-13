import { describe, expect, it } from 'vitest';
import { rowLabel } from './model';

describe('rowLabel', () => {
	const id = '0190abcd-1111-2222-3333-444455556666';
	it('prefers field text', () => {
		expect(rowLabel({ fields: { a: '<b>猫</b>' } }, id)).toBe('猫');
	});
	it('falls back to headword, then kanji literal, then short id', () => {
		const hw = new Map([[42, '食べる']]);
		expect(rowLabel({ fields: {}, entrySeq: 42 }, id, hw)).toBe('食べる');
		expect(rowLabel({ fields: {}, entrySeq: 43, kanjiLiteral: null }, id, hw)).toBe('0190abcd');
		expect(rowLabel({ fields: {}, kanjiLiteral: '猫' }, id)).toBe('猫');
		expect(rowLabel(null, id)).toBe('0190abcd');
	});
});
