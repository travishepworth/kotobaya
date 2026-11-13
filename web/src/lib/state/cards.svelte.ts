// Card browser state (/cards): search, paging, selection, bulk actions and the preview pane.
import type { Card, CardBulkResult, Deck, NoteType } from '../api/generated';
import { ResponseError, problemMessage, readProblem } from '../api/client';
import { cardsApi, decksApi, fetchHeadwords, fetchNoteKinds, noteTypesApi } from '../cards/api';
import { tc } from '../cards/messages';
import { buildRequest, PAGE_SIZE, type BulkKind, type BulkParams, type Selector } from '../cards/model';
import { EMPTY_VOCABULARY, loadVocabulary } from '../decks/vocabulary';
import type { SearchVocabulary } from '../core/index';
import { DictRef, renderPreview, sortKinds, type CardSide, type Rendered } from './preview.svelte';

export type { Card, CardBulkResult, Deck, NoteType } from '../api/generated';
export type { SearchVocabulary } from '../core/index';
export { BULK_KINDS, ROW_HEIGHT, rowLabel, windowRange } from '../cards/model';
export type { BulkKind, BulkParams, Selector } from '../cards/model';
export { completeQuery, parseQuery } from './search';

/** The /cards screen. */
export class CardsPage {
	draft = $state('');
	/** The query the shown results (and "all matching") belong to. */
	appliedQuery = $state('');
	cards = $state<Card[]>([]);
	cursor = $state<string | null>(null);
	loading = $state(false);
	loadedOnce = $state(false);
	serverError = $state<string | null>(null);
	vocabulary = $state<SearchVocabulary>(EMPTY_VOCABULARY);
	userDecks = $state<Deck[]>([]);
	noteTypes = $state<Map<string, NoteType>>(new Map());
	selected = $state<Set<string>>(new Set());
	allMatching = $state(false);
	activeId = $state<string | null>(null);
	/** Headwords of dictionary notes, filled lazily from the preview's entry fetches. */
	headwords = $state<Map<number, string>>(new Map());
	/** Bumped for every new search so a slow page of an older query is dropped. */
	private generation = 0;

	readonly active = $derived(this.cards.find((c) => c.id === this.activeId) ?? null);
	readonly selector = $derived<Selector>(this.allMatching ? { query: this.appliedQuery } : { ids: [...this.selected] });
	readonly hasSelection = $derived(this.allMatching || this.selected.size > 0);
	readonly scopeText = $derived(
		this.allMatching ? tc('bulk.scope.query') : tc('bulk.scope.ids', { n: this.selected.size })
	);

	/** Call once on mount: loads the completion vocabulary and the first page. */
	start(): void {
		void (async () => {
			try {
				this.vocabulary = await loadVocabulary();
				this.userDecks = (await decksApi.listDecks({ type: 'user' })).items;
				this.noteTypes = new Map((await noteTypesApi.listNoteTypes({})).items.map((n) => [n.id, n]));
			} catch {
				/* toast shown; the page still works without completions */
			}
		})();
		this.search();
	}

	async loadPage(reset: boolean): Promise<void> {
		if (this.loading && !reset) return;
		if (!reset && this.cursor === null) return;
		const gen = reset ? ++this.generation : this.generation;
		this.loading = true;
		try {
			const page = await cardsApi.listCards({
				query: this.appliedQuery === '' ? undefined : this.appliedQuery,
				limit: PAGE_SIZE,
				cursor: reset ? undefined : (this.cursor ?? undefined)
			});
			if (gen !== this.generation) return;
			this.serverError = null;
			this.cards = reset ? page.items : [...this.cards, ...page.items];
			this.cursor = page.nextCursor;
			void this.loadHeadwords(page.items);
		} catch (e) {
			if (gen !== this.generation) return;
			if (e instanceof ResponseError && e.response.status === 422) {
				this.serverError = (await readProblem(e.response))?.detail ?? 'invalid_query';
			}
			this.cursor = null;
		} finally {
			if (gen === this.generation) {
				this.loading = false;
				this.loadedOnce = true;
			}
		}
	}

	/** One batch call for the dictionary notes of a freshly loaded page; failures keep the id fallback. */
	private async loadHeadwords(items: Card[]): Promise<void> {
		const seqs = items.flatMap((c) => (c.note?.entrySeq != null ? [c.note.entrySeq] : []));
		await fetchHeadwords(seqs, (found) => {
			const next = new Map(this.headwords);
			for (const h of found) next.set(h.seq, h.headword);
			this.headwords = next;
		});
	}

	search(): void {
		this.appliedQuery = this.draft.trim();
		this.selected = new Set();
		this.allMatching = false;
		this.activeId = null;
		this.cards = [];
		this.cursor = null;
		void this.loadPage(true);
	}

	activate(c: Card): void {
		this.activeId = c.id;
	}

	toggle(c: Card): void {
		const next = new Set(this.selected);
		if (!next.delete(c.id)) next.add(c.id);
		this.selected = next;
	}

	togglePage(): void {
		const all = this.cards.length > 0 && this.cards.every((c) => this.selected.has(c.id));
		this.selected = all ? new Set() : new Set(this.cards.map((c) => c.id));
	}

	selectAllMatching(): void {
		this.allMatching = true;
	}

	clearSelection(): void {
		this.selected = new Set();
		this.allMatching = false;
	}

	/** After a bulk call: reload the list, keep the selection (so the result stays on screen). */
	applied(): void {
		void this.loadPage(true);
	}

	/** The preview pane learned a headword. */
	setHeadword(seq: number, h: string): void {
		if (this.headwords.get(seq) !== h) this.headwords = new Map(this.headwords).set(seq, h);
	}

	noteTypeOf(c: Card): NoteType | undefined {
		return c.note ? this.noteTypes.get(c.note.noteTypeId) : undefined;
	}
}

/**
 * Preview pane of one card. Construct during component init: it registers its own effects, which read
 * only the props passed as getters and never the state they write.
 */
export class CardPreviewState {
	readonly ref = new DictRef();
	side = $state<CardSide>('front');
	kind = $state('');
	noteKinds = $state<string[]>([]);

	readonly #card: () => Card;
	readonly #noteType: () => NoteType | undefined;

	/** The note's live card kinds, in note-type template order; the clicked card's kind is always included. */
	readonly kinds = $derived.by(() => {
		const live = new Set(this.noteKinds);
		live.add(this.#card().kind);
		return sortKinds([...live], this.#noteType());
	});
	readonly activeKind = $derived.by(() => (this.kinds.includes(this.kind) ? this.kind : this.#card().kind));
	readonly rendered = $derived.by((): Rendered => {
		const note = this.#card().note;
		const nt = this.#noteType();
		if (!note || !nt) return { ok: false, detail: tc('cards.previewMissingType') };
		return renderPreview({
			noteType: nt,
			kind: this.activeKind,
			fields: note.fields,
			mnemonic: note.mnemonic,
			entry: this.ref.entry,
			kanji: this.ref.kanji,
			side: this.side
		});
	});

	constructor(
		card: () => Card,
		noteType: () => NoteType | undefined,
		onheadword: () => ((seq: number, headword: string) => void) | undefined
	) {
		this.#card = card;
		this.#noteType = noteType;

		// Referenced notes render from the dictionary: fetch (cached) whenever the card changes.
		$effect(() => {
			const c = card();
			return this.ref.load(c.note?.entrySeq ?? null, c.note?.kanjiLiteral ?? null, (seq, e) =>
				onheadword()?.(seq, e.headword)
			);
		});

		// A newly chosen card starts on its front, on the kind that was clicked.
		$effect(() => {
			const c = card();
			this.kind = c.kind;
			this.side = 'front';
			this.noteKinds = [];
			let stale = false;
			fetchNoteKinds(c.noteId).then(
				(ks) => {
					if (!stale) this.noteKinds = ks;
				},
				() => {
					/* switcher just shows the clicked card */
				}
			);
			return () => {
				stale = true;
			};
		});
	}
}

type BulkPhase = 'idle' | 'checking' | 'confirm' | 'applying' | 'done';

/**
 * Bulk toolbar. Every action is first run with dry_run and the counts shown; only a second,
 * explicit click applies it. The bulk call is synchronous, so "progress" is the applying state.
 */
export class BulkBarState {
	kind = $state<BulkKind>('add_tags');
	params = $state<BulkParams>({ tags: '', deckId: '', flag: 0, dueLocal: '' });
	phase = $state<BulkPhase>('idle');
	dry = $state<CardBulkResult | null>(null);
	result = $state<CardBulkResult | null>(null);
	error = $state<string | null>(null);

	readonly #selector: () => Selector;
	readonly #onapplied: () => void;

	constructor(selector: () => Selector, onapplied: () => void) {
		this.#selector = selector;
		this.#onapplied = onapplied;
		// Any change of action, parameters or selection invalidates a dry run that was already shown.
		$effect(() => {
			void this.kind;
			void this.params.tags;
			void this.params.deckId;
			void this.params.flag;
			void this.params.dueLocal;
			void selector();
			this.reset();
		});
	}

	reset(): void {
		this.phase = 'idle';
		this.dry = null;
		this.result = null;
		this.error = null;
	}

	private async run(dryRun: boolean): Promise<CardBulkResult | null> {
		const req = buildRequest(this.kind, this.params, this.#selector(), dryRun);
		if (!req) {
			this.error = tc('bulk.invalidInput');
			return null;
		}
		try {
			return req.schedule
				? await cardsApi.bulkScheduleCards({ cardBulkScheduleRequest: req.body })
				: await cardsApi.bulkCards({ cardBulkRequest: req.body });
		} catch (e) {
			const detail =
				e instanceof ResponseError ? problemMessage(await readProblem(e.response), e.response.status) : String(e);
			this.error = tc('bulk.failed', { detail });
			return null;
		}
	}

	async check(): Promise<void> {
		this.error = null;
		this.phase = 'checking';
		this.dry = await this.run(true);
		this.phase = this.dry ? 'confirm' : 'idle';
	}

	async apply(): Promise<void> {
		this.phase = 'applying';
		this.result = await this.run(false);
		this.phase = this.result ? 'done' : 'confirm';
		if (this.result) this.#onapplied();
	}
}
