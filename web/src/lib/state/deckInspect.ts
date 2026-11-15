// Deck inspector: status filter chips -> `status` query lists, and the bulk word-status runner (pure, injectable api).
import type { DeckItem, WordStatusValue } from '../api/generated';

export type DeckFilter = 'all' | 'new' | 'learning' | 'known' | 'ignored';
export const DECK_FILTERS: readonly DeckFilter[] = ['all', 'new', 'learning', 'known', 'ignored'];
export type DeckSort = 'position' | 'freq_rank' | 'occurrence_count';

/** The `status` list each chip sends; null = no filter (also returns items without an entry). */
export const FILTER_STATUSES: Record<DeckFilter, readonly WordStatusValue[] | null> = {
	all: null,
	new: ['new'],
	learning: ['learning'],
	known: ['known', 'never_forget'],
	ignored: ['blacklisted', 'ignored']
};

export function filterStatusParam(f: DeckFilter): Set<WordStatusValue> | undefined {
	const l = FILTER_STATUSES[f];
	return l === null ? undefined : new Set(l);
}

/** Does an item with this status stay in the list under the filter? */
export function matchesFilter(f: DeckFilter, status: WordStatusValue): boolean {
	const l = FILTER_STATUSES[f];
	return l === null || l.includes(status);
}

/** Settable overrides (new/learning are derived and cannot be set). */
export type SettableStatus = 'known' | 'never_forget' | 'ignored' | 'blacklisted';
export type StatusAction = { kind: 'set'; status: SettableStatus } | { kind: 'reset' };

/** The slice of WordsApi used here (injectable for tests). Both return the resulting status. */
export interface WordStatusApi {
	set(seq: number, status: SettableStatus): Promise<WordStatusValue>;
	clear(seq: number): Promise<WordStatusValue>;
}

export function applyStatusAction(api: WordStatusApi, seq: number, a: StatusAction): Promise<WordStatusValue> {
	return a.kind === 'set' ? api.set(seq, a.status) : api.clear(seq);
}

export interface BulkResult {
	/** itemKey -> status after the change, for every success. */
	updated: Map<string, WordStatusValue>;
	/** Items whose call failed (or had no entry); never dropped silently. */
	failed: DeckItem[];
}

/** One call per selected entry, sequentially. */
export async function applyBulk(
	api: WordStatusApi,
	items: readonly DeckItem[],
	action: StatusAction,
	onProgress?: (done: number, total: number) => void
): Promise<BulkResult> {
	const updated = new Map<string, WordStatusValue>();
	const failed: DeckItem[] = [];
	let n = 0;
	for (const it of items) {
		try {
			if (it.entrySeq == null) throw new Error('no entry');
			updated.set(it.itemKey, await applyStatusAction(api, it.entrySeq, action));
		} catch {
			failed.push(it);
		}
		onProgress?.(++n, items.length);
	}
	return { updated, failed };
}
