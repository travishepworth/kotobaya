import { describe, expect, it } from 'vitest';
import { earliestDue, groupByNote, maxLapses, rowSelection, toggleRow } from '../../src/lib/state/noteRows';

interface TestCard {
	id: string;
	noteId: string;
	kind: string;
	dueAt: Date | null;
	lapses: number;
}
const card = (id: string, noteId: string, kind: string, due: string | null = null, lapses = 0): TestCard => ({
	id,
	noteId,
	kind,
	dueAt: due ? new Date(due) : null,
	lapses
});

describe('groupByNote', () => {
	it('collapses the reading and meaning card of one vocab note into one row with both card states', () => {
		const reading = card('c1', 'n1', 'reading', '2026-11-20T00:00:00.000Z', 1);
		const meaning = card('c2', 'n1', 'meaning', '2026-11-15T00:00:00.000Z', 3);
		const rows = groupByNote([reading, meaning]);
		expect(rows).toHaveLength(1);
		expect(rows[0].noteId).toBe('n1');
		expect(rows[0].cards.map((c) => c.kind)).toEqual(['reading', 'meaning']);
		expect(earliestDue(rows[0])?.toISOString()).toBe('2026-11-15T00:00:00.000Z');
		expect(maxLapses(rows[0])).toBe(3);
	});

	it('orders rows by their first card and keeps other notes separate', () => {
		const rows = groupByNote([card('a', 'n1', 'meaning'), card('b', 'n2', 'keyword'), card('c', 'n1', 'reading')]);
		expect(rows.map((r) => r.noteId)).toEqual(['n1', 'n2']);
		expect(rows[0].cards.map((c) => c.id)).toEqual(['a', 'c']);
	});

	it('paging boundary: a sibling on the next page joins the existing row instead of adding one', () => {
		const page1 = [card('a', 'n1', 'meaning'), card('b', 'n2', 'meaning')];
		expect(groupByNote(page1)[0].cards).toHaveLength(1);
		const page2 = [card('c', 'n1', 'reading')];
		const rows = groupByNote([...page1, ...page2]);
		expect(rows.map((r) => r.noteId)).toEqual(['n1', 'n2']);
		expect(rows[0].cards.map((c) => c.id)).toEqual(['a', 'c']);
	});

	it('a card returned again by keyset paging appears once, with its latest values', () => {
		const rows = groupByNote([card('a', 'n1', 'meaning', null, 0), card('a', 'n1', 'meaning', null, 2)]);
		expect(rows[0].cards).toHaveLength(1);
		expect(rows[0].cards[0].lapses).toBe(2);
	});

	it('no scheduled card means no due date', () => {
		expect(earliestDue(groupByNote([card('a', 'n1', 'meaning')])[0])).toBeNull();
	});
});

describe('row selection', () => {
	const row = groupByNote([card('a', 'n1', 'meaning'), card('b', 'n1', 'reading')])[0];

	it('reports none, some and all', () => {
		expect(rowSelection(row, new Set())).toBe('none');
		expect(rowSelection(row, new Set(['a']))).toBe('some');
		expect(rowSelection(row, new Set(['a', 'b']))).toBe('all');
	});

	it('toggling selects every card of a partly selected row, and clears a fully selected one', () => {
		expect([...toggleRow(row, new Set(['a', 'x']))].sort()).toEqual(['a', 'b', 'x']);
		expect([...toggleRow(row, new Set(['a', 'b', 'x']))]).toEqual(['x']);
	});
});
