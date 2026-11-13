// Deck screens (/decks, /decks/new, /decks/[id]): list, create/edit form, items of user/list decks.
import { goto } from '$app/navigation';
import type { Deck, DeckItem, DeckListCatalogueEntry } from '../api/generated';
import { readProblem, ResponseError } from '../api/client';
import { decksApi } from '../decks/api';
import { emptyDraft, parseNumbers, toCreate, toDraft, toPatch, type DeckDraft } from '../decks/draft';
import { td, type DeckMessageKey } from '../decks/messages';
import { EMPTY_VOCABULARY, loadVocabulary } from '../decks/vocabulary';
import type { SearchVocabulary } from '../core/index';
import { pushToast } from '../toasts.svelte';
import { parseQuery } from './search';

export type { Deck, DeckItem, DeckListCatalogueEntry } from '../api/generated';
export type { DeckDraft } from '../decks/draft';
export type { SearchVocabulary } from '../core/index';
export { parseNumbers } from '../decks/draft';
export { completeQuery, parseQuery } from './search';

export const DECK_TYPE_KEYS: Record<string, DeckMessageKey> = {
	user: 'decks.type.user',
	smart: 'decks.type.smart',
	list: 'decks.type.list',
	media: 'decks.type.media'
};

/** /decks */
export class DecksListPage {
	items = $state<Deck[]>([]);
	loaded = $state(false);
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);

	start(): void {
		this.loaded = false;
		this.loadFailed = false;
		void (async () => {
			try {
				const all = (await decksApi.listDecks({})).items;
				this.items = [...all].sort((a, b) => b.priority - a.priority || a.name.localeCompare(b.name));
			} catch {
				this.loadFailed = true; // the client already toasted
			} finally {
				this.loaded = true;
			}
		})();
	}
	/** Load again after a failure. */
	retry(): void {
		this.start();
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

	readonly #deck: () => Deck;
	readonly editable = $derived.by(() => this.#deck().type === 'user');

	constructor(deck: () => Deck) {
		this.#deck = deck;
		$effect(() => {
			const d = deck();
			// Smart decks have no items (the server answers 409 not_applicable).
			if (d.type !== 'smart') void this.fetch(d.id, null);
			else this.loaded = true;
		});
	}

	/** Reads no state before its first await, so it is safe to start from an effect. */
	private async fetch(deckId: string, cursor: string | null): Promise<void> {
		try {
			const page = await decksApi.listDeckItems({ deckId, limit: 50, cursor: cursor ?? undefined });
			this.items = cursor !== null ? [...this.items, ...page.items] : page.items;
			this.cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			this.loaded = true;
		}
	}

	async load(more: boolean): Promise<void> {
		await this.fetch(this.#deck().id, more ? this.cursor : null);
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
