// Browse › Cards shows one row per note: a vocab note's reading and meaning cards collapse into one row that keeps
// every card (so per-card state and per-card actions stay available). Pure helpers; no domain logic.

/** The card properties grouping and the row summaries need. */
export interface GroupableCard {
	id: string;
	noteId: string;
	dueAt: Date | null;
	lapses: number;
}

/** One browse row: a note and its loaded cards, in the order the server returned them. */
export interface NoteRow<C extends GroupableCard> {
	noteId: string;
	cards: C[];
}

/**
 * Groups the loaded cards (every page so far, concatenated in load order) by note.
 *
 * - Rows appear in the order of their note's first card; cards within a row keep load order.
 * - Paging boundary: grouping runs over all loaded pages, never one page. A sibling that arrives on a later page joins
 *   the row its note already has (no second row); until it arrives the row lists only the cards loaded so far.
 * - A card id seen twice (keyset paging may return a row again, API section 4) keeps its first position with the
 *   last occurrence's values.
 */
export function groupByNote<C extends GroupableCard>(cards: readonly C[]): NoteRow<C>[] {
	const rows = new Map<string, NoteRow<C>>();
	const where = new Map<string, NoteRow<C>>();
	for (const c of cards) {
		const seen = where.get(c.id);
		if (seen) {
			seen.cards[seen.cards.findIndex((x) => x.id === c.id)] = c;
			continue;
		}
		let row = rows.get(c.noteId);
		if (!row) {
			row = { noteId: c.noteId, cards: [] };
			rows.set(c.noteId, row);
		}
		row.cards.push(c);
		where.set(c.id, row);
	}
	return [...rows.values()];
}

/** The earliest due instant of the row's cards; null when none is scheduled. */
export function earliestDue(row: NoteRow<GroupableCard>): Date | null {
	let best: Date | null = null;
	for (const c of row.cards) if (c.dueAt && (best === null || c.dueAt.getTime() < best.getTime())) best = c.dueAt;
	return best;
}

/** The most lapses of any card in the row. */
export function maxLapses(row: NoteRow<GroupableCard>): number {
	return row.cards.reduce((m, c) => Math.max(m, c.lapses), 0);
}

/** How much of the row is in the card selection. */
export function rowSelection(row: NoteRow<GroupableCard>, selected: ReadonlySet<string>): 'all' | 'some' | 'none' {
	const n = row.cards.filter((c) => selected.has(c.id)).length;
	return n === 0 ? 'none' : n === row.cards.length ? 'all' : 'some';
}

/** Toggling a row's checkbox: a fully selected row is deselected, otherwise every card of the row is selected. */
export function toggleRow(row: NoteRow<GroupableCard>, selected: ReadonlySet<string>): Set<string> {
	const next = new Set(selected);
	if (rowSelection(row, selected) === 'all') for (const c of row.cards) next.delete(c.id);
	else for (const c of row.cards) next.add(c.id);
	return next;
}
