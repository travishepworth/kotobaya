// Pure helpers for the card browser: windowing, row text, bulk request building. No domain logic
// about the search language or card rendering (those come from ../core/index).
import type { CardBulkAction, CardBulkRequest, CardBulkScheduleRequest } from '../api/generated';

export const ROW_HEIGHT = 32;
export const OVERSCAN = 6;
export const PAGE_SIZE = 500;

export interface WindowRange {
	start: number;
	end: number;
	padTop: number;
	padBottom: number;
}

/** The rows to render for a scroll position: [start, end) plus spacer heights. */
export function windowRange(
	total: number,
	scrollTop: number,
	viewportHeight: number,
	rowHeight = ROW_HEIGHT,
	overscan = OVERSCAN
): WindowRange {
	const first = Math.min(total, Math.max(0, Math.floor(scrollTop / rowHeight) - overscan));
	const last = Math.min(total, Math.ceil((scrollTop + viewportHeight) / rowHeight) + overscan);
	const end = Math.max(first, last);
	return { start: first, end, padTop: first * rowHeight, padBottom: (total - end) * rowHeight };
}

/** Plain text of an HTML fragment (note fields are HTML); for display via text interpolation only. */
export function plainText(html: string): string {
	return html
		.replace(/<[^>]*>/g, '')
		.replace(/\s+/g, ' ')
		.trim();
}

/** First non-empty field value of a note, as a one-line summary. */
export function noteSummary(fields: Record<string, string>): string {
	for (const v of Object.values(fields)) {
		const s = plainText(v);
		if (s) return s;
	}
	return '';
}

export type BulkKind =
	| 'add_tags'
	| 'remove_tags'
	| 'move'
	| 'suspend'
	| 'unsuspend'
	| 'bury'
	| 'unbury'
	| 'flag'
	| 'reschedule'
	| 'reset'
	| 'delete';

export const BULK_KINDS: BulkKind[] = [
	'add_tags',
	'remove_tags',
	'move',
	'suspend',
	'unsuspend',
	'bury',
	'unbury',
	'flag',
	'reschedule',
	'reset',
	'delete'
];

export interface BulkParams {
	tags: string;
	deckId: string;
	flag: number;
	/** `datetime-local` value, local time. */
	dueLocal: string;
}

export type Selector = { ids: string[] } | { query: string };

export function splitTags(s: string): string[] {
	return [...new Set(s.split(/\s+/).filter((x) => x !== ''))];
}

export type BulkRequest =
	| { schedule: false; body: CardBulkRequest }
	| { schedule: true; body: CardBulkScheduleRequest };

/** Null when the parameters of [kind] are incomplete. reschedule and reset use /cards/bulk/schedule. */
export function buildRequest(kind: BulkKind, p: BulkParams, sel: Selector, dryRun: boolean): BulkRequest | null {
	// The generated client types `cardIds` as a Set.
	const selection = 'ids' in sel ? { cardIds: new Set(sel.ids) } : { query: sel.query };
	const plain = (action: CardBulkAction): BulkRequest => ({
		schedule: false,
		body: { ...selection, dryRun, action }
	});
	const sched = (action: CardBulkScheduleRequest['action']): BulkRequest => ({
		schedule: true,
		body: { ...selection, dryRun, action }
	});
	switch (kind) {
		case 'add_tags':
		case 'remove_tags': {
			const tags = splitTags(p.tags);
			return tags.length === 0 ? null : plain({ type: kind, tags } as CardBulkAction);
		}
		case 'move':
			return p.deckId ? plain({ type: 'move', toDeckId: p.deckId } as CardBulkAction) : null;
		case 'flag':
			return plain({ type: 'flag', flags: p.flag } as CardBulkAction);
		case 'reschedule': {
			const d = new Date(p.dueLocal);
			if (p.dueLocal === '' || Number.isNaN(d.getTime())) return null;
			return sched({ type: 'reschedule', dueAt: d } as CardBulkScheduleRequest['action']);
		}
		case 'reset':
			return sched({ type: 'reset' } as CardBulkScheduleRequest['action']);
		default:
			return plain({ type: kind } as CardBulkAction);
	}
}
