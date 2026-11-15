import { describe, expect, it, vi } from 'vitest';
import type { Deck } from '../../src/lib/api/generated';
import { deckItemKey, loadMembership, pickableDecks, toggleMembership, wordNoteCreate, type DeckPickerApi } from '../../src/lib/ui/deckPicker';

const deck = (o: Partial<Deck>): Deck => ({ id: 'd', name: 'x', type: 'user', priority: 0, deletedAt: null, ...o }) as Deck;

function fakeApi(items: Record<string, string[]>): DeckPickerApi & { add: ReturnType<typeof vi.fn>; rm: ReturnType<typeof vi.fn> } {
	const add = vi.fn(async () => ({}));
	const rm = vi.fn(async () => ({}));
	return {
		add,
		rm,
		listDecks: async () => ({ items: [] }) as never,
		listDeckItems: async ({ deckId }) => ({ items: (items[deckId] ?? []).map((itemKey) => ({ itemKey })), nextCursor: null }) as never,
		addDeckItems: add,
		removeDeckItem: rm
	};
}

describe('deck item keys', () => {
	it('uses e:<seq> for a note with an entry and n:<id> for a custom note', async () => {
		expect(deckItemKey({ entrySeq: 12, noteId: 'abc' })).toBe('e:12');
		expect(deckItemKey({ entrySeq: null, noteId: 'abc' })).toBe('n:abc');
		const api = fakeApi({});
		await toggleMembership(api, 'd1', { entrySeq: 12, noteId: 'abc' }, false);
		await toggleMembership(api, 'd1', { entrySeq: null, noteId: 'abc' }, false);
		expect(api.add.mock.calls.map((c) => (c[0] as { deckItemsAdd: unknown }).deckItemsAdd)).toEqual([
			{ items: [{ entrySeq: 12 }] },
			{ items: [{ noteId: 'abc' }] }
		]);
	});
});

describe('picker', () => {
	it('lists only live user decks, priority ascending then name', () => {
		const out = pickableDecks([
			deck({ id: 'a', name: 'b', priority: 1 }),
			deck({ id: 'm', type: 'media' }),
			deck({ id: 'g', type: 'list' }),
			deck({ id: 'x', deletedAt: new Date() }),
			deck({ id: 'c', name: 'z', priority: 0 }),
			deck({ id: 'd', name: 'a', priority: 1 })
		]);
		expect(out.map((d) => d.id)).toEqual(['c', 'd', 'a']);
	});

	it('shows membership and toggling off removes the item', async () => {
		const api = fakeApi({ d1: ['e:5'], d2: ['e:6'] });
		const target = { entrySeq: 5, noteId: null };
		const held = await loadMembership(api, [deck({ id: 'd1' }), deck({ id: 'd2' })], target);
		expect([...held]).toEqual(['d1']);
		await toggleMembership(api, 'd1', target, true);
		expect(api.rm).toHaveBeenCalledWith({ deckId: 'd1', itemKey: 'e:5' });
		expect(api.add).not.toHaveBeenCalled();
	});
});

describe('add to my notes with a deck', () => {
	it('creates the note and the deck item in one createNote body', () => {
		expect(wordNoteCreate('nt', 7, 'd1')).toEqual({ noteTypeId: 'nt', entrySeq: 7, origin: 'manual', deckId: 'd1' });
		expect(wordNoteCreate('nt', 7, null)).toEqual({ noteTypeId: 'nt', entrySeq: 7, origin: 'manual' });
	});
});
