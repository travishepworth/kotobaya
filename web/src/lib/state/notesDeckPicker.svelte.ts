// State of the shared "Add to deck" picker (note view, dictionary entry, search rows): the user's live decks,
// which of them hold the word or note, and add/remove/create. All writes are deck.item_put / deck.item_removed via the API.
import type { Deck } from '../api/generated';
import { decksApi } from '../decks/api';
import { pushToast } from '../toasts.svelte';
import { ut } from '../ui/messages';
import { deckItemKey, loadMembership, pickableDecks, toggleMembership, type DeckTarget } from '../ui/deckPicker';

export type { Deck } from '../api/generated';
export type { DeckTarget } from '../ui/deckPicker';

/** One picker per word or note. `load(target)` again when the target changes. */
export class DeckPickerState {
	decks = $state<Deck[]>([]);
	/** Ids of the decks that hold the target. */
	held = $state<Set<string>>(new Set());
	loaded = $state(false);
	failed = $state(false);
	/** Deck id with a write in flight (its row is disabled). */
	busy = $state<string | null>(null);
	newName = $state('');
	creating = $state(false);
	private gen = 0;

	readonly heldDecks = $derived(this.decks.filter((d) => this.held.has(d.id)));

	async load(target: DeckTarget): Promise<void> {
		const gen = ++this.gen;
		this.failed = false;
		try {
			const decks = pickableDecks((await decksApi.listDecks({})).items);
			const held = deckItemKey(target) === null ? new Set<string>() : await loadMembership(decksApi, decks, target);
			if (gen !== this.gen) return;
			this.decks = decks;
			this.held = held;
		} catch {
			if (gen === this.gen) this.failed = true; // the client already toasted
		} finally {
			if (gen === this.gen) this.loaded = true;
		}
	}

	private mark(deckId: string, isIn: boolean): void {
		const next = new Set(this.held);
		if (isIn) next.add(deckId);
		else next.delete(deckId);
		this.held = next;
	}

	/** Toggle membership of `deck`. */
	async toggle(deck: Deck, target: DeckTarget): Promise<void> {
		const isIn = this.held.has(deck.id);
		this.busy = deck.id;
		try {
			await toggleMembership(decksApi, deck.id, target, isIn);
			this.mark(deck.id, !isIn);
			pushToast(ut(isIn ? 'ui.deck.removed' : 'ui.deck.added', { deck: deck.name }), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.busy = null;
		}
	}

	/** Create a user deck named `newName` and put the target in it. */
	async createAndAdd(target: DeckTarget): Promise<void> {
		const name = this.newName.trim();
		if (name === '') return;
		this.creating = true;
		try {
			const deck = await decksApi.createDeck({ deckCreate: { type: 'user', name } });
			this.decks = pickableDecks([...this.decks, deck]);
			this.newName = '';
			pushToast(ut('ui.deck.created', { deck: deck.name }), 'info');
			await this.toggle(deck, target);
		} catch {
			/* toast shown */
		} finally {
			this.creating = false;
		}
	}
}

/** Live user decks for a chooser (dictionary "Add to my notes in…"). */
export async function loadUserDecks(): Promise<Deck[]> {
	return pickableDecks((await decksApi.listDecks({})).items);
}
