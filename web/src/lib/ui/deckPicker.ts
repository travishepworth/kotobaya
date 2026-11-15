// "Add to deck": which user decks hold a word or note, and toggling membership (deck.item_put / deck.item_removed).
// Deck membership is per word or note, never per card: a note with an entry_seq is stored as e:<seq>, a custom note as n:<id>.
import type { Deck, DeckItem, DeckItemsAdd, DeckList, DeckItemPage, NoteCreate } from '../api/generated';

/** What is being added: a dictionary word (`entrySeq`), or a note (`noteId`, plus its `entrySeq` when it has one). */
export interface DeckTarget {
	entrySeq: number | null;
	noteId: string | null;
}

/** The slice of the generated DecksApi this module uses (injectable for tests). */
export interface DeckPickerApi {
	listDecks(req: Record<string, never>): Promise<DeckList>;
	listDeckItems(req: { deckId: string; limit?: number; cursor?: string }): Promise<DeckItemPage>;
	addDeckItems(req: { deckId: string; deckItemsAdd: DeckItemsAdd }): Promise<unknown>;
	removeDeckItem(req: { deckId: string; itemKey: string }): Promise<unknown>;
}

/** Item key of a target: `e:<entry_seq>` whenever there is an entry, else `n:<note id>`. */
export function deckItemKey(t: DeckTarget): string | null {
	if (t.entrySeq !== null) return `e:${t.entrySeq}`;
	if (t.noteId !== null) return `n:${t.noteId.toLowerCase()}`;
	return null;
}

/** The element `addDeckItems` takes for a target (entry form wins, as the server stores it). */
export function deckItemElement(t: DeckTarget): DeckItemsAdd['items'][number] | null {
	if (t.entrySeq !== null) return { entrySeq: t.entrySeq };
	if (t.noteId !== null) return { noteId: t.noteId };
	return null;
}

/** Live user decks only (media, list and smart decks take no manual items), in draw order: priority ascending, then name. */
export function pickableDecks(decks: readonly Deck[]): Deck[] {
	return decks
		.filter((d) => d.type === 'user' && d.deletedAt === null)
		.sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name));
}

/** Body of "Add to my notes" for a dictionary word: the note and, with `deckId`, its deck item in one createNote call. */
export function wordNoteCreate(noteTypeId: string, entrySeq: number, deckId: string | null): NoteCreate {
	const body: NoteCreate = { noteTypeId, entrySeq, origin: 'manual' };
	if (deckId !== null && deckId !== '') body.deckId = deckId;
	return body;
}

const PAGE = 500;
const MAX_PAGES = 20;

async function deckHolds(api: DeckPickerApi, deckId: string, key: string): Promise<boolean> {
	let cursor: string | undefined;
	for (let i = 0; i < MAX_PAGES; i++) {
		const page = await api.listDeckItems({ deckId, limit: PAGE, cursor });
		if (page.items.some((x: DeckItem) => x.itemKey === key)) return true;
		if (page.nextCursor === null) return false;
		cursor = page.nextCursor;
	}
	return false;
}

/** Ids of the given decks that hold the target as a live item. (The API has no "decks of this word" read, so this scans each deck.) */
export async function loadMembership(api: DeckPickerApi, decks: readonly Deck[], target: DeckTarget): Promise<Set<string>> {
	const key = deckItemKey(target);
	if (key === null) return new Set();
	const held = await Promise.all(decks.map(async (d) => ((await deckHolds(api, d.id, key)) ? d.id : null)));
	return new Set(held.filter((x): x is string => x !== null));
}

/** Add the target to a deck (isIn = false) or remove it (isIn = true). */
export async function toggleMembership(api: DeckPickerApi, deckId: string, target: DeckTarget, isIn: boolean): Promise<void> {
	if (isIn) {
		const key = deckItemKey(target);
		if (key !== null) await api.removeDeckItem({ deckId, itemKey: key });
		return;
	}
	const el = deckItemElement(target);
	if (el !== null) await api.addDeckItems({ deckId, deckItemsAdd: { items: [el] } });
}
