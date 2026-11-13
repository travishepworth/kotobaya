import { CardsApi, DecksApi, DictionaryApi, NoteTypesApi, NotesApi, type DictEntry, type Kanji } from '../api/generated';
import { apiConfig } from '../api/client';

export const cardsApi = new CardsApi(apiConfig);
export const decksApi = new DecksApi(apiConfig);
export const noteTypesApi = new NoteTypesApi(apiConfig);
export const dictionaryApi = new DictionaryApi(apiConfig);

// Per-page-life caches of in-flight/settled fetches; failures are evicted so a retry can succeed.
const entries = new Map<number, Promise<DictEntry>>();
const kanji = new Map<string, Promise<Kanji>>();

function cached<K, V>(m: Map<K, Promise<V>>, k: K, load: () => Promise<V>): Promise<V> {
	let p = m.get(k);
	if (!p) {
		p = load();
		m.set(k, p);
		p.catch(() => m.delete(k));
	}
	return p;
}

export const fetchEntry = (seq: number): Promise<DictEntry> =>
	cached(entries, seq, () => dictionaryApi.getDictEntry({ seq }));
export const fetchKanji = (literal: string): Promise<Kanji> =>
	cached(kanji, literal, () => dictionaryApi.getKanji({ literal }));

const notesApi = new NotesApi(apiConfig);
const noteKinds = new Map<string, Promise<string[]>>();
/** Live card kinds of a note (cached); the card list only carries a NoteSummary. */
export const fetchNoteKinds = (noteId: string): Promise<string[]> =>
	cached(noteKinds, noteId, async () => (await notesApi.getNote({ noteId })).cards.map((c) => c.kind));
