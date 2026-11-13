// Dictionary screen state (/dictionary): debounced search, entry detail with examples, add-as-note, kanji panel.
import { untrack } from 'svelte';
import type { Deck, DictEntry, DictEntrySummary, EntryExamples, Kanji, KanjiWord } from '../api/generated';
import { decksApi, dictionaryApi, noteTypesApi, notesApi } from '../dictionary/api';
import { td } from '../dictionary/messages';
import { pushToast } from '../toasts.svelte';

export type { Deck, DictEntry, DictEntrySummary, EntryExamples, Kanji, KanjiWord } from '../api/generated';

const PAGE = 50;
const DEBOUNCE_MS = 300;

/** The /dictionary screen. Call `dispose()` on unmount (drops a pending debounced search). */
export class DictionaryPage {
	query = $state('');
	commonOnly = $state(false);
	items = $state<DictEntrySummary[]>([]);
	cursor = $state<string | null>(null);
	searching = $state(false);
	searched = $state(false);
	selectedSeq = $state<number | null>(null);
	entry = $state<DictEntry | null>(null);
	capturing = $state<string | null>(null);
	kanjiOpen = $state<string | null>(null);
	examples = $state<EntryExamples | null>(null);
	loadingEntry = $state(false);
	adding = $state(false);
	private searchGen = 0;
	private entryGen = 0;
	private timer: ReturnType<typeof setTimeout> | undefined;

	async runSearch(reset: boolean): Promise<void> {
		const q = this.query.trim();
		const gen = reset ? ++this.searchGen : this.searchGen;
		if (reset) {
			this.items = [];
			this.cursor = null;
			if (q === '') {
				this.searching = false;
				this.searched = false;
				return;
			}
		} else if (this.cursor === null) return;
		this.searching = true;
		try {
			const page = await dictionaryApi.searchDictionary({
				q,
				commonOnly: this.commonOnly,
				limit: PAGE,
				cursor: reset ? undefined : (this.cursor ?? undefined)
			});
			if (gen !== this.searchGen) return;
			this.items = reset ? page.items : [...this.items, ...page.items];
			this.cursor = page.nextCursor;
			this.searched = true;
		} catch {
			if (gen !== this.searchGen) return;
			this.cursor = null;
			this.searched = true;
		} finally {
			if (gen === this.searchGen) this.searching = false;
		}
	}

	/** The query box changed: search after a short pause. */
	queryChanged(): void {
		clearTimeout(this.timer);
		this.timer = setTimeout(() => void this.runSearch(true), DEBOUNCE_MS);
	}

	/** Search now (form submit). */
	searchNow(): void {
		clearTimeout(this.timer);
		void this.runSearch(true);
	}

	dispose(): void {
		clearTimeout(this.timer);
	}

	async open(seq: number): Promise<void> {
		this.selectedSeq = seq;
		this.kanjiOpen = null;
		const gen = ++this.entryGen;
		this.loadingEntry = true;
		this.entry = null;
		this.examples = null;
		void this.loadExamples(seq, gen);
		try {
			const e = await dictionaryApi.getDictEntry({ seq });
			if (gen !== this.entryGen) return;
			this.entry = e;
		} catch {
			/* error toast shown by the client */
		} finally {
			if (gen === this.entryGen) this.loadingEntry = false;
		}
	}

	// Independent of the entry: a failure or empty result only omits the examples section.
	private async loadExamples(seq: number, gen: number): Promise<void> {
		try {
			const ex = await dictionaryApi.listEntryExamples({ seq, limit: 10 });
			if (gen !== this.entryGen) return;
			this.examples = ex;
		} catch {
			/* examples are optional; section stays hidden */
		}
	}

	async addNote(e: DictEntry): Promise<void> {
		this.adding = true;
		try {
			const types = await noteTypesApi.listNoteTypes({});
			const vocab = types.items.find((n) => n.baseType === 'vocab' && n.deletedAt === null);
			if (!vocab) {
				pushToast(td('dict.noVocabType'), 'error');
				return;
			}
			const note = await notesApi.createNote({
				noteCreate: { noteTypeId: vocab.id, entrySeq: e.seq, origin: 'manual' }
			});
			pushToast(td('dict.added'), 'info');
			this.entry = { ...e, noteId: note.id };
			this.items = this.items.map((i) => (i.seq === e.seq ? { ...i, noteId: note.id } : i));
		} catch {
			/* error toast shown by the client */
		} finally {
			this.adding = false;
		}
	}

	toggleKanji(k: string): void {
		this.kanjiOpen = this.kanjiOpen === k ? null : k;
	}

	closeKanji(): void {
		this.kanjiOpen = null;
	}

	toggleCapture(sentenceId: string): void {
		this.capturing = this.capturing === sentenceId ? null : sentenceId;
	}

	closeCapture(): void {
		this.capturing = null;
	}
}

export function pitchText(positions: number[]): string {
	return positions.map((p) => (p === 0 ? td('dict.pitchHeiban') : td('dict.pitchDrop', { n: p }))).join(', ');
}

/** The kanji side panel: kanji details, words using it (frequent / mine, optional deck filter). */
export class KanjiPanelState {
	kanji = $state<Kanji | null>(null);
	loadingKanji = $state(true);
	tab = $state<'frequent' | 'mine'>('frequent');
	deckId = $state('');
	decks = $state<Deck[]>([]);
	words = $state<KanjiWord[]>([]);
	cursor = $state<string | null>(null);
	loading = $state(false);
	private gen = 0;
	readonly #literal: () => string;

	constructor(literal: () => string) {
		this.#literal = literal;

		$effect(() => {
			const lit = literal();
			this.loadingKanji = true;
			this.kanji = null;
			let live = true;
			void (async () => {
				try {
					const k = await dictionaryApi.getKanji({ literal: lit });
					if (live) this.kanji = k;
				} catch {
					/* error toast shown by the client; words are still listed */
				} finally {
					if (live) this.loadingKanji = false;
				}
			})();
			return () => {
				live = false;
			};
		});

		$effect(() => {
			let live = true;
			void (async () => {
				try {
					const all = (await decksApi.listDecks({})).items;
					if (live) this.decks = all.filter((d) => d.deletedAt === null);
				} catch {
					/* dropdown stays empty */
				}
			})();
			return () => {
				live = false;
			};
		});

		// Reload whenever the kanji, tab or deck filter changes.
		$effect(() => {
			void literal();
			void this.tab;
			void this.deckId;
			untrack(() => void this.load(true));
		});
	}

	async load(reset: boolean): Promise<void> {
		const g = reset ? ++this.gen : this.gen;
		if (reset) {
			this.words = [];
			this.cursor = null;
		} else if (this.cursor === null) return;
		this.loading = true;
		try {
			const page = await dictionaryApi.listKanjiWords({
				literal: this.#literal(),
				mine: this.tab === 'mine' ? true : undefined,
				deckId: this.deckId === '' ? undefined : this.deckId,
				limit: PAGE,
				cursor: reset ? undefined : (this.cursor ?? undefined)
			});
			if (g !== this.gen) return;
			// Pages are not a snapshot: de-duplicate by seq.
			const seen = new Set(reset ? [] : this.words.map((w) => w.seq));
			this.words = [...(reset ? [] : this.words), ...page.items.filter((w) => !seen.has(w.seq))];
			this.cursor = page.nextCursor;
		} catch {
			if (g !== this.gen) return;
			this.cursor = null;
		} finally {
			if (g === this.gen) this.loading = false;
		}
	}
}
