import { describe, expect, it } from 'vitest';
import { defaultFilters, loadFilters, saveFilters, STORAGE_KEY } from '../src/lib/coverage/filters';
import type { StorageLike } from '../src/lib/coverage/filters';

function memory(): StorageLike & { data: Map<string, string> } {
	const data = new Map<string, string>();
	return {
		data,
		getItem: (k) => data.get(k) ?? null,
		setItem: (k, v) => void data.set(k, v)
	};
}

const throwing: StorageLike = {
	getItem: () => {
		throw new Error('denied');
	},
	setItem: () => {
		throw new Error('denied');
	}
};

describe('coverage filters persistence', () => {
	it('round-trips hide classes and segment', () => {
		const s = memory();
		const f = { hide: ['names' as const, 'ignored' as const], segmentId: 'seg-1' };
		saveFilters(f, s);
		expect(loadFilters(s)).toEqual(f);
	});

	it('round-trips an empty hide list', () => {
		const s = memory();
		saveFilters({ hide: [], segmentId: null }, s);
		expect(loadFilters(s)).toEqual({ hide: [], segmentId: null });
	});

	it('falls back to defaults on corrupt data', () => {
		const s = memory();
		s.data.set(STORAGE_KEY, '{nope');
		expect(loadFilters(s)).toEqual(defaultFilters());
	});

	it('survives a throwing localStorage', () => {
		expect(() => saveFilters(defaultFilters(), throwing)).not.toThrow();
		expect(loadFilters(throwing)).toEqual(defaultFilters());
		expect(loadFilters(null)).toEqual(defaultFilters());
	});
});
