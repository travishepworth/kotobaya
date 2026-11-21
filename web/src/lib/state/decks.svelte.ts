// Deck screens (/decks, /decks/new, /decks/[id]): list, create/edit form, items of user/list decks.
import { untrack } from 'svelte';
import { goto } from '$app/navigation';
import type { Coverage, Deck, DeckItem, DeckListCatalogueEntry, WordStatusValue } from '../api/generated';
import { readProblem, ResponseError } from '../api/client';
import { NoteTypesApi, NotesApi, WordsApi } from '../api/generated';
import { apiConfig, quietApiConfig } from '../api/client';
import { DecksApi } from '../api/generated';
import { isUncached, revalidateOn } from './swr';
import { decksApi } from '../decks/api';
import { emptyDraft, parseNumbers, toCreate, toDraft, toPatch, type DeckDraft } from '../decks/draft';
import { td, type DeckMessageKey } from '../decks/messages';
import { EMPTY_VOCABULARY, loadVocabulary } from '../decks/vocabulary';
import type { SearchVocabulary } from '../core/index';
import { pushToast } from '../toasts.svelte';
import { wordNoteCreate } from '../ui/deckPicker';
import { parseQuery } from './search';
import {
	applyBulk,
	applyStatusAction,
	filterStatusParam,
	matchesFilter,
	type DeckFilter,
	type DeckSort,
	type StatusAction,
	type WordStatusApi
} from './deckInspect';

export type { Coverage, Deck, DeckItem, DeckListCatalogueEntry } from '../api/generated';
export type { DeckDraft } from '../decks/draft';
export type { SearchVocabulary } from '../core/index';
export { parseNumbers } from '../decks/draft';
export { DECK_FILTERS, type DeckFilter, type DeckSort, type SettableStatus, type StatusAction } from './deckInspect';
export { completeQuery, parseQuery } from './search';

const wordsApi = new WordsApi(apiConfig);
const notesApi = new NotesApi(apiConfig);
const noteTypesApi = new NoteTypesApi(apiConfig);

const wordStatusApi: WordStatusApi = {
	set: async (seq, status) => (await wordsApi.setWordStatus({ seq, wordStatusSet: { status } })).status,
	clear: async (seq) => (await wordsApi.clearWordStatus({ seq })).status
};

export const DECK_TYPE_KEYS: Record<string, DeckMessageKey> = {
	user: 'decks.type.user',
	smart: 'decks.type.smart',
	list: 'decks.type.list',
	media: 'decks.type.media'
};

/** Draw order of decks: lower priority first, then by name (equal priorities take turns). */
export function compareDeckDrawOrder(a: Pick<Deck, 'priority' | 'name'>, b: Pick<Deck, 'priority' | 'name'>): number {
	return a.priority - b.priority || a.name.localeCompare(b.name);
}

/**
 * The raw coverage of a media (reading) deck for the progress block. Server-only: a failed or offline fetch leaves
 * `coverage` null and the block is simply not shown. `load` only writes this object's state (safe in an $effect).
 */
export class DeckCoverageState {
	coverage = $state<Coverage | null>(null);
	private gen = 0;

	load(deckId: string): void {
		const mine = ++this.gen;
		this.coverage = null;
		void decksApi
			.getDeckCoverage({ deckId })
			.then((c) => {
				if (mine === this.gen) this.coverage = c;
			})
			.catch(() => {});
	}
}

// A background list: failures show inline (and `503 shell_uncached` as a placeholder), never as a toast.
const quietDecksApi = new DecksApi(quietApiConfig);

/** /decks. Keeps its items while it refetches (stale-while-revalidate): the skeleton shows on the first load only. */
export class DecksListPage {
	items = $state<Deck[]>([]);
	loaded = $state(false);
	/** The last load failed; the view shows the error state and offers `retry`. Never set while items are shown. */
	loadFailed = $state(false);
	/** `503 shell_uncached` and nothing to show yet: "not downloaded yet", resolved by `cache.updated`. */
	uncached = $state(false);
	#hasData = false;

	start(): void {
		if (!this.#hasData) {
			this.loaded = false;
			this.loadFailed = false;
		}
		void (async () => {
			try {
				const all = (await quietDecksApi.listDecks({})).items;
				this.items = [...all].sort(compareDeckDrawOrder);
				this.#hasData = true;
				this.loadFailed = false;
				this.uncached = false;
			} catch (e) {
				if (await isUncached(e)) this.uncached = !this.#hasData;
				else if (!this.#hasData) this.loadFailed = true;
			} finally {
				this.loaded = true;
			}
		})();
	}
	/** Load again after a failure. */
	retry(): void {
		this.start();
	}
	/** Refetch on `sync.changed` (debounced) and on `cache.updated` for `listDecks`. Returns the unsubscribe. */
	watch(): () => void {
		return revalidateOn(['listDecks'], () => this.start());
	}
}

/** The server rejected a smart-deck query (422 invalid_query): its detail, else null. */
async function invalidQueryDetail(e: unknown): Promise<string | null> {
	if (e instanceof ResponseError && e.response.status === 422) {
		const p = await readProblem(e.response);
		if (p?.code === 'invalid_query') return p.detail ?? p.code;
	}
	return null;
}

/** /decks/new */
export class DeckNewPage {
	draft = $state(emptyDraft());
	busy = $state(false);
	lists = $state<DeckListCatalogueEntry[]>([]);
	vocabulary = $state<SearchVocabulary>(EMPTY_VOCABULARY);
	serverError = $state<string | null>(null);

	start(): void {
		void (async () => {
			try {
				this.vocabulary = await loadVocabulary();
				this.lists = (await decksApi.listDeckLists()).items;
			} catch {
				/* toast shown; the form still works without suggestions */
			}
		})();
	}

	async create(): Promise<void> {
		const n = parseNumbers(this.draft);
		if (!n.ok) return;
		this.busy = true;
		this.serverError = null;
		try {
			const deck = await decksApi.createDeck({ deckCreate: toCreate(this.draft, n) });
			pushToast(td('decks.created'), 'info');
			await goto(`/decks/${deck.id}`, { replaceState: true });
		} catch (e) {
			const detail = await invalidQueryDetail(e);
			if (detail !== null) this.serverError = detail;
		} finally {
			this.busy = false;
		}
	}
}

/** /decks/[id] */
export class DeckEditPage {
	id = $state('');
	deck = $state<Deck | null>(null);
	draft = $state<DeckDraft | null>(null);
	vocabulary = $state<SearchVocabulary>(EMPTY_VOCABULARY);
	missing = $state(false);
	busy = $state(false);
	serverError = $state<string | null>(null);

	/** Load deck `deckId` (call from an effect on the route param; reads no state). */
	load(deckId: string): void {
		this.id = deckId;
		void (async () => {
			try {
				const d = await decksApi.getDeck({ deckId });
				this.deck = d;
				this.draft = toDraft(d);
				if (d.type === 'smart') this.vocabulary = await loadVocabulary();
			} catch (e) {
				if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
					this.missing = true;
				}
			}
		})();
	}

	async save(): Promise<void> {
		const deck = this.deck;
		const draft = this.draft;
		if (!deck || !draft) return;
		const n = parseNumbers(draft);
		if (!n.ok) return;
		this.busy = true;
		this.serverError = null;
		try {
			const d = await decksApi.updateDeck({ deckId: this.id, deckPatch: toPatch(draft, deck.type, n) });
			this.deck = d;
			this.draft = toDraft(d);
			pushToast(td('decks.saved'), 'info');
		} catch (e) {
			const detail = await invalidQueryDetail(e);
			if (detail !== null) this.serverError = detail;
		} finally {
			this.busy = false;
		}
	}

	/** Delete (the view asks for confirmation first). */
	async remove(): Promise<void> {
		try {
			await decksApi.deleteDeck({ deckId: this.id });
			pushToast(td('decks.deleted'), 'info');
			await goto('/decks', { replaceState: true });
		} catch {
			/* toast shown (media decks: 409 deck_read_only) */
		}
	}
}

/** Items of one (user or list) deck, with add/remove for user decks. Construct during component init. */
export class DeckItemsState {
	items = $state<DeckItem[]>([]);
	cursor = $state<string | null>(null);
	loaded = $state(false);
	entrySeq = $state('');
	noteId = $state('');
	busy = $state(false);
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	private retryMore = false;
	filter = $state<DeckFilter>('all');
	sort = $state<DeckSort>('position');
	/** Selected item keys (bulk actions). */
	selected = $state<Set<string>>(new Set());
	/** Bulk progress while running; null otherwise. */
	progress = $state<{ done: number; total: number } | null>(null);
	/** Keys of rows with a call in flight. */
	pending = $state<Set<string>>(new Set());
	private req = 0;
	private vocabTypeId: string | null = null;

	readonly #deck: () => Deck;
	readonly editable = $derived.by(() => this.#deck().type === 'user');
	readonly selectedItems = $derived(this.items.filter((i) => this.selected.has(i.itemKey) && i.entrySeq != null));

	constructor(deck: () => Deck) {
		this.#deck = deck;
		$effect(() => {
			const d = deck();
			// Smart decks have no items (the server answers 409 not_applicable).
			if (d.type !== 'smart') untrack(() => void this.fetch(d.id, null));
			else this.loaded = true;
		});
	}

	/** A newer request supersedes an older one (filter switches while a page is in flight). */
	private async fetch(deckId: string, cursor: string | null): Promise<void> {
		const mine = ++this.req;
		try {
			const page = await decksApi.listDeckItems({
				deckId,
				limit: 50,
				cursor: cursor ?? undefined,
				status: filterStatusParam(this.filter),
				sort: this.sort
			});
			if (mine !== this.req) return;
			this.items = cursor !== null ? [...this.items, ...page.items] : page.items;
			this.cursor = page.nextCursor;
			this.loadFailed = false;
			if (cursor === null) this.selected = new Set();
		} catch {
			if (mine !== this.req) return;
			this.loadFailed = true; // the client already toasted
			this.retryMore = cursor !== null;
		} finally {
			if (mine === this.req) this.loaded = true;
		}
	}

	retry(): void {
		void this.load(this.retryMore);
	}

	async load(more: boolean): Promise<void> {
		await this.fetch(this.#deck().id, more ? this.cursor : null);
	}

	/** Switching filter or sort restarts from the first page (cursors are only valid for the same parameters). */
	async setView(filter: DeckFilter, sort: DeckSort): Promise<void> {
		this.filter = filter;
		this.sort = sort;
		this.cursor = null;
		await this.fetch(this.#deck().id, null);
	}

	/** Items with a word entry carry a status; custom-note rows (`n:`) do not. */
	hasStatus(item: DeckItem): boolean {
		return item.entrySeq != null;
	}

	toggle(key: string): void {
		const next = new Set(this.selected);
		if (!next.delete(key)) next.add(key);
		this.selected = next;
	}

	/** Select every status-bearing row on the loaded pages, or clear when all already are. */
	toggleAll(): void {
		const keys = this.items.filter((i) => this.hasStatus(i)).map((i) => i.itemKey);
		this.selected = keys.length > 0 && keys.every((k) => this.selected.has(k)) ? new Set() : new Set(keys);
	}

	/** Write the new status into the row, or drop the row when it no longer matches the filter. */
	private applyStatus(key: string, status: WordStatusValue): void {
		this.items = this.items.flatMap((i) => {
			if (i.itemKey !== key) return [i];
			if (!matchesFilter(this.filter, status)) return [];
			return [{ ...i, status, entry: i.entry ? { ...i.entry, status } : i.entry }];
		});
		if (!this.items.some((i) => i.itemKey === key) && this.selected.has(key)) {
			const next = new Set(this.selected);
			next.delete(key);
			this.selected = next;
		}
	}

	private setPending(key: string, on: boolean): void {
		const next = new Set(this.pending);
		if (on) next.add(key);
		else next.delete(key);
		this.pending = next;
	}

	/** Set or reset one word's status (PUT/DELETE /words/{seq}/status). */
	async act(item: DeckItem, action: StatusAction): Promise<void> {
		if (item.entrySeq == null) return;
		this.setPending(item.itemKey, true);
		try {
			this.applyStatus(item.itemKey, await applyStatusAction(wordStatusApi, item.entrySeq, action));
			pushToast(td('decks.statusUpdated'), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.setPending(item.itemKey, false);
		}
	}

	/** One status call per selected entry; failures are counted and reported, never dropped silently. */
	async bulk(action: StatusAction): Promise<void> {
		const items = this.selectedItems;
		if (items.length === 0 || this.progress) return;
		this.progress = { done: 0, total: items.length };
		try {
			const r = await applyBulk(wordStatusApi, items, action, (done, total) => (this.progress = { done, total }));
			for (const [key, status] of r.updated) this.applyStatus(key, status);
			if (r.failed.length > 0) {
				pushToast(td('decks.bulkFailed', { failed: r.failed.length, total: items.length }), 'error');
				this.selected = new Set(r.failed.map((f) => f.itemKey));
			} else {
				pushToast(td('decks.bulkDone', { n: r.updated.size }), 'info');
			}
		} finally {
			this.progress = null;
		}
	}

	/** "Add to my notes" for a word row (Vocab note type, no deck). A duplicate note counts as already added. */
	async mine(item: DeckItem): Promise<void> {
		const seq = item.entrySeq;
		if (seq == null) return;
		this.setPending(item.itemKey, true);
		try {
			if (this.vocabTypeId === null) {
				const types = await noteTypesApi.listNoteTypes({});
				this.vocabTypeId = types.items.find((n) => n.baseType === 'vocab' && n.deletedAt === null)?.id ?? null;
			}
			if (this.vocabTypeId === null) {
				pushToast(td('decks.noVocabType'), 'error');
				return;
			}
			const note = await notesApi.createNote({ noteCreate: wordNoteCreate(this.vocabTypeId, seq, null) });
			this.items = this.items.map((i) => (i.itemKey === item.itemKey && i.entry ? { ...i, entry: { ...i.entry, noteId: note.id } } : i));
			pushToast(td('decks.mined'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) pushToast(td('decks.mined'), 'info');
		} finally {
			this.setPending(item.itemKey, false);
		}
	}

	async add(): Promise<void> {
		const deck = this.#deck();
		const seq = this.entrySeq.trim();
		const note = this.noteId.trim();
		if (seq === '' && note === '') return;
		if (seq !== '' && !/^\d+$/.test(seq)) {
			pushToast(td('decks.invalidNumber', { field: td('decks.itemsAddEntry') }), 'error');
			return;
		}
		this.busy = true;
		try {
			await decksApi.addDeckItems({
				deckId: deck.id,
				deckItemsAdd: {
					items: [seq !== '' ? { entrySeq: Number(seq) } : { noteId: note }]
				}
			});
			this.entrySeq = '';
			this.noteId = '';
			pushToast(td('decks.itemsAdded'), 'info');
			await this.load(false);
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async remove(item: DeckItem): Promise<void> {
		try {
			await decksApi.removeDeckItem({ deckId: this.#deck().id, itemKey: item.itemKey });
			this.items = this.items.filter((x) => x.itemKey !== item.itemKey);
		} catch {
			/* toast shown */
		}
	}
}

const FIELD_LABEL = {
	priority: 'decks.priorityLabel',
	newPerDay: 'decks.newPerDay',
	reviewsPerDay: 'decks.reviewsPerDay'
} as const;

/** Validate a deck form before submit; toasts the problem and returns false when it may not be sent. */
export function checkDeckDraft(draft: DeckDraft): boolean {
	const n = parseNumbers(draft);
	if (!n.ok) {
		pushToast(td('decks.invalidNumber', { field: td(FIELD_LABEL[n.field]) }), 'error');
		return false;
	}
	if (draft.type === 'smart' && !parseQuery(draft.query).ok) {
		pushToast(td('decks.queryInvalid'), 'error');
		return false;
	}
	return true;
}

/** Display label of a deck item. */
export function deckItemLabel(item: DeckItem): string {
	if (item.entry) return `${item.entry.headword} (${item.entry.headwordReading}): ${item.entry.glosses.slice(0, 3).join('; ')}`;
	if (item.noteId) return td('decks.itemNote', { id: item.noteId });
	return td('decks.itemEntry', { seq: item.entrySeq ?? '' });
}
