import { describe, expect, it } from 'vitest';
import { compareDeckDrawOrder } from '../../src/lib/state/decks.svelte';

describe('compareDeckDrawOrder', () => {
	it('lists the lowest priority number first (the first drawn)', () => {
		const decks = [100, -10, 5, 0].map((priority, i) => ({ priority, name: `d${i}` }));
		expect([...decks].sort(compareDeckDrawOrder).map((d) => d.priority)).toEqual([-10, 0, 5, 100]);
	});
	it('breaks ties by name', () => {
		const decks = [
			{ priority: 0, name: 'b' },
			{ priority: 0, name: 'a' }
		];
		expect([...decks].sort(compareDeckDrawOrder).map((d) => d.name)).toEqual(['a', 'b']);
	});
});
