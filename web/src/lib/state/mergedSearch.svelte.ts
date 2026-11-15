// One-box search (/search, ADR-026): dictionary words flagged "In my cards", then your notes; a query with
// card-search operators lists cards instead. Syntax detection is the shared core parser's (never re-implemented).
import { ResponseError, readProblem } from '../api/client';
import type { Card, DictEntrySummary, Note } from '../api/generated';
import { cardsApi, dictionaryApi, fetchHeadwords } from '../cards/api';
import { notesApi } from '../notes/api';
import { mergeResults, noteRow, usesCardSyntax, type NoteRow, type WordRow } from '../search/merge';
import { tms } from '../search/messages';
import { parseQuery } from '../core/index';
import { groupByNote } from './noteRows';

export type { NoteRow, WordRow } from '../search/merge';

export const SEARCH_PAGE = 50;
const DEBOUNCE_MS = 300;

export type SearchMode = 'idle' | 'merged' | 'cards';

/** The /search screen. Call `dispose()` on unmount. */
export class MergedSearchPage {
	query = $state('');
	mode = $state<SearchMode>('idle');
	searching = $state(false);
	/** The last search failed (the client already toasted). */
	failed = $state(false);
	invalid = $state<string | null>(null);
	dict = $state<DictEntrySummary[]>([]);
	notes = $state<Note[]>([]);
	cards = $state<Card[]>([]);
	dictCursor = $state<string | null>(null);
	headwords = $state<Map<number, string>>(new Map());
	private gen = 0;
	private timer: ReturnType<typeof setTimeout> | undefined;

	/** Merged list: dictionary in dictionary order, then notes none of the words covers. */
	readonly merged = $derived(mergeResults(this.dict, this.notes, this.headwords));
	readonly words = $derived<WordRow[]>(this.merged.words);
	readonly noteRows = $derived<NoteRow[]>(this.merged.notes);
	/** Card-query results, one row per note. */
	readonly cardRows = $derived<NoteRow[]>(
		groupByNote(this.cards).map((r) =>
			noteRow({ id: r.noteId, ...r.cards[0].note }, r.cards.length, this.headwords)
		)
	);
	readonly empty = $derived(
		this.mode === 'merged' ? this.words.length === 0 && this.noteRows.length === 0 : this.mode === 'cards' && this.cardRows.length === 0
	);

	/** The query box changed: search after a short pause. */
	queryChanged(): void {
		clearTimeout(this.timer);
		this.timer = setTimeout(() => void this.run(), DEBOUNCE_MS);
	}

	searchNow(): void {
		clearTimeout(this.timer);
		void this.run();
	}

	dispose(): void {
		clearTimeout(this.timer);
	}

	async run(): Promise<void> {
		const q = this.query.trim();
		const gen = ++this.gen;
		this.failed = false;
		this.invalid = null;
		if (q === '') {
			this.mode = 'idle';
			this.searching = false;
			this.dict = [];
			this.notes = [];
			this.cards = [];
			this.dictCursor = null;
			return;
		}
		this.searching = true;
		try {
			if (usesCardSyntax(parseQuery(q).tokens)) await this.runCards(q, gen);
			else await this.runMerged(q, gen);
		} finally {
			if (gen === this.gen) this.searching = false;
		}
	}

	private async runCards(q: string, gen: number): Promise<void> {
		try {
			const page = await cardsApi.listCards({ query: q, limit: PAGE_LIMIT });
			if (gen !== this.gen) return;
			this.mode = 'cards';
			this.dict = [];
			this.notes = [];
			this.dictCursor = null;
			this.cards = page.items;
			void this.loadHeadwords(page.items.flatMap((c) => (c.note?.entrySeq != null ? [c.note.entrySeq] : [])), gen);
		} catch (e) {
			if (gen !== this.gen) return;
			if (e instanceof ResponseError && e.response.status === 422) {
				this.invalid = tms('ms.invalid', { detail: (await readProblem(e.response))?.detail ?? '' });
				this.mode = 'cards';
				this.cards = [];
			} else this.failed = true;
		}
	}

	private async runMerged(q: string, gen: number): Promise<void> {
		// The two sources are independent: one failing still shows the other.
		const [words, notes] = await Promise.allSettled([
			dictionaryApi.searchDictionary({ q, limit: SEARCH_PAGE }),
			notesApi.listNotes({ q, limit: SEARCH_PAGE })
		]);
		if (gen !== this.gen) return;
		if (words.status === 'rejected' && notes.status === 'rejected') {
			this.failed = true;
			return;
		}
		this.mode = 'merged';
		this.cards = [];
		this.dict = words.status === 'fulfilled' ? words.value.items : [];
		this.dictCursor = words.status === 'fulfilled' ? words.value.nextCursor : null;
		this.notes = notes.status === 'fulfilled' ? notes.value.items : [];
		void this.loadHeadwords(
			this.notes.flatMap((n) => (n.entrySeq != null ? [n.entrySeq] : [])),
			gen
		);
	}

	/** More dictionary words (the notes list is one page). */
	async moreWords(): Promise<void> {
		if (this.mode !== 'merged' || this.dictCursor === null) return;
		const gen = this.gen;
		this.searching = true;
		try {
			const page = await dictionaryApi.searchDictionary({ q: this.query.trim(), limit: SEARCH_PAGE, cursor: this.dictCursor });
			if (gen !== this.gen) return;
			const seen = new Set(this.dict.map((d) => d.seq));
			this.dict = [...this.dict, ...page.items.filter((d) => !seen.has(d.seq))];
			this.dictCursor = page.nextCursor;
		} catch {
			if (gen === this.gen) this.dictCursor = null;
		} finally {
			if (gen === this.gen) this.searching = false;
		}
	}

	private async loadHeadwords(seqs: number[], gen: number): Promise<void> {
		await fetchHeadwords(seqs, (found) => {
			if (gen !== this.gen) return;
			const next = new Map(this.headwords);
			for (const h of found) next.set(h.seq, h.headword);
			this.headwords = next;
		});
	}
}

const PAGE_LIMIT = 200;
