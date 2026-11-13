import { CardsApi, DecksApi, DictionaryApi, NoteTypesApi, NotesApi, type DictEntry, type DictHeadword, type Kanji } from '../api/generated';
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

const HEADWORD_BATCH = 500;
const headwordsDone = new Set<number>();

/**
 * Batch label lookup (listDictHeadwords) for the entry seqs of a loaded page, in chunks of 500.
 * Seqs already fetched in this page life are skipped; a failed chunk is dropped silently (rows keep their
 * id fallback) and may be retried by a later page. Unknown seqs come back omitted.
 */
export async function fetchHeadwords(seqs: Iterable<number>, onItems: (items: DictHeadword[]) => void): Promise<void> {
	const todo = [...new Set(seqs)].filter((s) => !headwordsDone.has(s)).sort((a, b) => a - b);
	for (let i = 0; i < todo.length; i += HEADWORD_BATCH) {
		const chunk = todo.slice(i, i + HEADWORD_BATCH);
		try {
			const res = await dictionaryApi.listDictHeadwords({ seq: chunk });
			for (const s of chunk) headwordsDone.add(s);
			onItems(res.items);
		} catch {
			// keep the id fallback
		}
	}
}
