import type { Deck, DeckCreate, DeckPatch } from '../api/generated';

export interface DeckDraft {
	name: string;
	type: 'user' | 'smart' | 'list';
	query: string;
	listKey: string;
	enabled: boolean;
	priority: string;
	newPerDay: string;
	reviewsPerDay: string;
}

export function emptyDraft(): DeckDraft {
	return {
		name: '',
		type: 'user',
		query: '',
		listKey: '',
		enabled: true,
		priority: '0',
		newPerDay: '',
		reviewsPerDay: ''
	};
}

export function toDraft(d: Deck): DeckDraft {
	return {
		name: d.name,
		type: d.type === 'smart' || d.type === 'list' ? d.type : 'user',
		query: d.query ?? '',
		listKey: d.listKey ?? '',
		enabled: d.enabled,
		priority: String(d.priority),
		newPerDay: d.newCardsPerDay === null ? '' : String(d.newCardsPerDay),
		reviewsPerDay: d.reviewsPerDay === null ? '' : String(d.reviewsPerDay)
	};
}

/** `''` → null (use the global limit); whole non-negative number → number; else undefined (invalid). */
export function parseLimit(s: string): number | null | undefined {
	const v = s.trim();
	if (v === '') return null;
	return /^\d+$/.test(v) ? Number(v) : undefined;
}

export function parsePriority(s: string): number | undefined {
	const v = s.trim();
	return /^-?\d+$/.test(v) ? Number(v) : undefined;
}

export type NumbersResult =
	| { ok: true; priority: number; newPerDay: number | null; reviewsPerDay: number | null }
	| { ok: false; field: 'priority' | 'newPerDay' | 'reviewsPerDay' };

export function parseNumbers(d: DeckDraft): NumbersResult {
	const priority = parsePriority(d.priority);
	if (priority === undefined) return { ok: false, field: 'priority' };
	const newPerDay = parseLimit(d.newPerDay);
	if (newPerDay === undefined) return { ok: false, field: 'newPerDay' };
	const reviewsPerDay = parseLimit(d.reviewsPerDay);
	if (reviewsPerDay === undefined) return { ok: false, field: 'reviewsPerDay' };
	return { ok: true, priority, newPerDay, reviewsPerDay };
}

export function toCreate(d: DeckDraft, n: { priority: number; newPerDay: number | null; reviewsPerDay: number | null }): DeckCreate {
	const body: DeckCreate = {
		type: d.type,
		enabled: d.enabled,
		priority: n.priority,
		newCardsPerDay: n.newPerDay,
		reviewsPerDay: n.reviewsPerDay
	};
	if (d.type === 'list') body.listKey = d.listKey;
	if (d.name.trim() !== '') body.name = d.name.trim();
	if (d.type === 'smart') body.query = d.query.trim();
	return body;
}

export function toPatch(d: DeckDraft, type: Deck['type'], n: { priority: number; newPerDay: number | null; reviewsPerDay: number | null }): DeckPatch {
	const body: DeckPatch = {
		name: d.name.trim(),
		enabled: d.enabled,
		priority: n.priority,
		newCardsPerDay: n.newPerDay,
		reviewsPerDay: n.reviewsPerDay
	};
	if (type === 'smart') body.query = d.query.trim();
	return body;
}
