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

/** First non-empty field value of a note, as a one-line summary (in the given key order, then in object order). */
export function noteSummary(fields: Record<string, string> | null | undefined, order: readonly string[] = []): string {
	if (!fields) return '';
	for (const k of order) {
		const s = plainText(fields[k] ?? '');
		if (s) return s;
	}
	for (const v of Object.values(fields)) {
		const s = plainText(v ?? '');
		if (s) return s;
	}
	return '';
}

/** Field keys that name the note itself; a stored field object has no reliable key order (jsonb sorts keys by length). */
const WORD_KEYS = ['expression', 'literal', 'word', 'front'];

interface LabelNote {
	fields?: Record<string, string> | null;
	entrySeq?: number | null;
	kanjiLiteral?: string | null;
}

/**
 * The note's name, or null when nothing names it yet (a dictionary note whose headword is still loading).
 * Order: the expression field, the dictionary headword, the kanji literal, the reading, then the first non-empty field
 * in note-type order. Takoboto notes carry only some fields, so the first stored field is often the meaning.
 */
export function noteLabel(
	note: LabelNote | null | undefined,
	headwords?: ReadonlyMap<number, string>,
	fieldOrder: readonly string[] = []
): string | null {
	if (!note) return null;
	for (const k of WORD_KEYS) {
		const v = plainText(note.fields?.[k] ?? '');
		if (v) return v;
	}
	if (note.entrySeq != null) {
		const h = headwords?.get(note.entrySeq);
		if (h) return h;
	}
	if (note.kanjiLiteral) return note.kanjiLiteral;
	const reading = plainText(note.fields?.reading ?? '');
	if (reading) return reading;
	return noteSummary(note.fields, fieldOrder) || null;
}

/** Plain text of one note field ('' when absent). */
export function fieldText(fields: Record<string, string> | null | undefined, key: string): string {
	return plainText(fields?.[key] ?? '');
}

/** Row label: `noteLabel`, else the short note id (last resort; callers show a skeleton while labels load). */
export function rowLabel(
	note: LabelNote | null | undefined,
	noteId: string,
	headwords?: ReadonlyMap<number, string>,
	fieldOrder: readonly string[] = []
): string {
	return noteLabel(note, headwords, fieldOrder) ?? noteId.slice(0, 8);
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
