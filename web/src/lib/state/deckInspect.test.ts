import { describe, expect, it } from 'vitest';
import type { DeckItem, WordStatusValue } from '../api/generated';
import { applyBulk, DECK_FILTERS, filterStatusParam, matchesFilter, type WordStatusApi } from './deckInspect';

const item = (seq: number | null): DeckItem => ({ itemKey: seq === null ? 'n:x' : `e:${seq}`, entrySeq: seq }) as DeckItem;

describe('filters', () => {
	it('maps chips to status lists', () => {
		const m = Object.fromEntries(DECK_FILTERS.map((f) => [f, [...(filterStatusParam(f) ?? [])]]));
		expect(m).toEqual({
			all: [],
			new: ['new'],
			learning: ['learning'],
			known: ['known', 'never_forget'],
			ignored: ['blacklisted', 'ignored']
		});
		expect(filterStatusParam('all')).toBeUndefined();
		expect(matchesFilter('known', 'never_forget')).toBe(true);
		expect(matchesFilter('known', 'new')).toBe(false);
		expect(matchesFilter('all', 'new')).toBe(true);
	});
});

describe('applyBulk', () => {
	it('calls set once per entry and reports failures', async () => {
		const calls: number[] = [];
		const api: WordStatusApi = {
			set: async (seq, status) => {
				calls.push(seq);
				if (seq === 2) throw new Error('boom');
				return status as WordStatusValue;
			},
			clear: async () => 'new'
		};
		const progress: number[] = [];
		const r = await applyBulk(api, [item(1), item(2), item(null), item(3)], { kind: 'set', status: 'known' }, (d) => progress.push(d));
		expect(calls).toEqual([1, 2, 3]);
		expect([...r.updated.keys()]).toEqual(['e:1', 'e:3']);
		expect(r.failed.map((x) => x.itemKey)).toEqual(['e:2', 'n:x']);
		expect(progress).toEqual([1, 2, 3, 4]);
	});
	it('reset uses clear', async () => {
		const cleared: number[] = [];
		const api: WordStatusApi = {
			set: async () => 'known',
			clear: async (s) => {
				cleared.push(s);
				return 'learning';
			}
		};
		const r = await applyBulk(api, [item(5)], { kind: 'reset' });
		expect(cleared).toEqual([5]);
		expect(r.updated.get('e:5')).toBe('learning');
	});
});
