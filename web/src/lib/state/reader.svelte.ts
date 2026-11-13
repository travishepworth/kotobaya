// Reader screens (/media/[id]/read, /media/[id]/sentences): sentences with word status, token popup with
// parse overrides and mining, sentence cards.
import { untrack } from 'svelte';
import {
	DecksApi,
	DictionaryApi,
	MediaApi,
	NotesApi,
	ParsingApi,
	WordsApi,
	type Deck,
	type DictEntry,
	type DictEntrySummary,
	type MediaSegment,
	type ParseOverride,
	type Sentence,
	type WordStatusValue
} from '../api/generated';
import { apiConfig, API_BASE, ResponseError } from '../api/client';
import { formatMs } from '../reader/format';
import { tr } from '../reader/messages';
import { buildOverridePut, changeEdit, joinEdit, splitEdit, type OverrideEdit, type OverrideScope } from '../reader/overrides';
import { cachedStatus, loadStatus, setCachedStatus } from '../reader/status';
import { uuidv7 } from '../review/model';
import { ts } from '../sentences/messages';
import { buildSentenceNote, buildVocabNote, sentenceReading } from '../sentences/noteRequest';
import { pushToast } from '../toasts.svelte';

export type { Deck, DictEntry, MediaSegment, ParseOverride, Sentence } from '../api/generated';
export type { OverrideEdit, OverrideScope } from '../reader/overrides';
export { formatMs, pieces } from '../reader/format';
export { changeEdit, scopeOf } from '../reader/overrides';

const mediaApi = new MediaApi(apiConfig);
const dictApi = new DictionaryApi(apiConfig);
const wordsApi = new WordsApi(apiConfig);
const notesApi = new NotesApi(apiConfig);
const parsingApi = new ParsingApi(apiConfig);
const decksApi = new DecksApi(apiConfig);

/* ---------- /media/[id]/read ---------- */

/** Construct during component init with the route's media id (getter). */
export class ReaderPage {
	segments = $state<MediaSegment[]>([]);
	segmentId = $state('');
	sentences = $state<Sentence[]>([]);
	cursor = $state<string | null>(null);
	loaded = $state(false);
	furigana = $state(false);
	capturing = $state<string | null>(null);
	selected = $state<{ sentenceId: string; index: number } | null>(null);
	overridesVersion = $state(0);
	showBanner = $state(false);
	statuses = $state<Record<number, WordStatusValue>>({});
	private busy = false;
	private gen = 0;
	readonly #mediaId: () => string;

	readonly selSentence = $derived(this.sentences.find((s) => s.id === this.selected?.sentenceId) ?? null);

	constructor(mediaId: () => string) {
		this.#mediaId = mediaId;
		$effect(() => {
			const id = mediaId();
			mediaApi
				.getMedia({ mediaId: id })
				.then((m) => {
					this.segments = m.segments ?? [];
				})
				.catch(() => {});
		});
		$effect(() => {
			void mediaId();
			void this.segmentId;
			this.loaded = false;
			this.selected = null;
			untrack(() => void this.load(false));
		});
	}

	get mediaId(): string {
		return this.#mediaId();
	}

	async load(more: boolean): Promise<void> {
		if (this.busy) return;
		this.busy = true;
		const mine = more ? this.gen : ++this.gen;
		try {
			const p = await mediaApi.listMediaSentences({
				mediaId: this.#mediaId(),
				segmentId: this.segmentId || undefined,
				limit: 50,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			if (mine !== this.gen) return;
			this.sentences = more ? [...this.sentences, ...p.items] : p.items;
			this.cursor = p.nextCursor;
			for (const s of p.items) {
				for (const t of s.tokens) {
					if (t.seq != null && t._class === 'content') void this.resolve(t.seq);
				}
			}
		} catch {
			/* toast shown */
		} finally {
			this.loaded = true;
			this.busy = false;
		}
	}

	/** The sentinel at the end of the list became visible. */
	nearEnd(): void {
		if (this.cursor) void this.load(true);
	}

	async resolve(seq: number): Promise<void> {
		const s = cachedStatus(seq) ?? (await loadStatus(seq));
		if (s) this.statuses[seq] = s;
	}

	statusClass(seq: number | null): string {
		if (seq == null) return 'unmapped';
		return this.statuses[seq] ?? 'pending';
	}

	select(sentenceId: string, index: number): void {
		this.selected = { sentenceId, index };
	}

	clearSelection(): void {
		this.selected = null;
	}

	toggleCapture(id: string): void {
		this.capturing = this.capturing === id ? null : id;
	}

	closeCapture(): void {
		this.capturing = null;
	}

	/** A parse override was saved from the popup. */
	overridden(): void {
		this.overridesVersion++;
		this.showBanner = true;
		this.selected = null;
	}

	dismissBanner(): void {
		this.showBanner = false;
	}

	async reparse(): Promise<void> {
		try {
			await mediaApi.reparseMedia({ mediaId: this.#mediaId() });
			this.showBanner = false;
			pushToast(tr('rd.reparseStarted'), 'info');
		} catch {
			/* toast shown */
		}
	}
}

export function locationLabel(s: Sentence): string {
	if (s.startMs != null) return tr('rd.at', { time: formatMs(s.startMs) });
	const p = s.location?.['page'];
	if (typeof p === 'number' || typeof p === 'string') return tr('rd.page', { page: p });
	return '';
}

/* ---------- token popup ---------- */

/** Built-in Vocab note type (contracts/db). */
const VOCAB_NOTE_TYPE = '00000000-0000-7000-8000-000000000101';

export const STATUS_ACTIONS = ['rd.markKnown', 'rd.markIgnore', 'rd.markNeverForget'] as const;
export const STATUS_VALUE = {
	'rd.markKnown': 'known',
	'rd.markIgnore': 'ignored',
	'rd.markNeverForget': 'never_forget'
} as const;

export interface TokenPopupProps {
	sentence: Sentence;
	tokenIndex: number;
	onoverride: () => void;
	onstatus: (seq: number) => void;
}

/** Construct during component init; props are passed as getters. */
export class TokenPopupState {
	entry = $state<DictEntry | null>(null);
	scope = $state<OverrideScope>('sentence');
	splitOffset = $state(1);
	query = $state('');
	results = $state<DictEntrySummary[] | null>(null);
	mineId = $state(uuidv7());
	readonly #sentence: () => Sentence;
	readonly #tokenIndex: () => number;
	readonly #cb: () => Pick<TokenPopupProps, 'onoverride' | 'onstatus'>;

	readonly token = $derived.by(() => this.#sentence().tokens[this.#tokenIndex()]);
	readonly splitPreview = $derived.by(() => splitEdit(this.#sentence().tokens, this.#tokenIndex(), this.splitOffset));
	readonly joinPreview = $derived.by(() => joinEdit(this.#sentence().tokens, this.#tokenIndex()));

	constructor(
		sentence: () => Sentence,
		tokenIndex: () => number,
		callbacks: () => Pick<TokenPopupProps, 'onoverride' | 'onstatus'>
	) {
		this.#sentence = sentence;
		this.#tokenIndex = tokenIndex;
		this.#cb = callbacks;
		$effect(() => {
			const seq = this.token?.seq ?? null;
			this.entry = null;
			this.results = null;
			this.query = '';
			this.splitOffset = 1;
			this.mineId = uuidv7();
			if (seq == null) return;
			let live = true;
			dictApi
				.getDictEntry({ seq })
				.then((e) => {
					if (live) this.entry = e;
				})
				.catch(() => {});
			return () => {
				live = false;
			};
		});
	}

	async save(edit: OverrideEdit | null): Promise<void> {
		if (!edit) return;
		const sentence = this.#sentence();
		try {
			await parsingApi.putParseOverride({
				overrideId: uuidv7(),
				parseOverridePut: buildOverridePut(edit, this.scope, {
					mediaId: sentence.mediaId,
					sentenceId: sentence.id
				})
			});
			pushToast(tr('rd.saved'), 'info');
			this.#cb().onoverride();
		} catch {
			/* toast shown */
		}
	}

	/** Re-tokenise the selected token as dictionary entry `r`. */
	pick(r: DictEntrySummary): Promise<void> {
		return this.save(
			changeEdit(this.#sentence().tokens, this.#tokenIndex(), {
				seq: r.seq,
				lemma: r.headword,
				reading: r.headwordReading
			})
		);
	}

	async search(): Promise<void> {
		if (!this.query.trim()) return;
		try {
			this.results = (await dictApi.searchDictionary({ q: this.query.trim(), limit: 10 })).items;
		} catch {
			/* toast shown */
		}
	}

	async mark(status: 'known' | 'ignored' | 'never_forget'): Promise<void> {
		const seq = this.token?.seq;
		if (seq == null) return;
		try {
			await wordsApi.setWordStatus({ seq, wordStatusSet: { status } });
			setCachedStatus(seq, status);
			if (this.entry) this.entry = { ...this.entry, status };
			this.#cb().onstatus(seq);
			pushToast(tr('rd.marked'), 'info');
		} catch {
			/* toast shown */
		}
	}

	async mine(): Promise<void> {
		const seq = this.token?.seq;
		if (seq == null) return;
		const sentence = this.#sentence();
		try {
			await notesApi.createNote({
				noteCreate: {
					id: this.mineId,
					noteTypeId: VOCAB_NOTE_TYPE,
					entrySeq: seq,
					sourceSentence: sentence.text,
					sourceSentenceId: sentence.id,
					sourceMediaId: sentence.mediaId
				}
			});
			pushToast(tr('rd.mined'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) {
				pushToast(tr('rd.duplicate'), 'info');
			}
		}
	}
}

export function showEdit(e: OverrideEdit | null): string {
	return e ? e.tokens.map((x) => x.surface).join(' | ') : '';
}

/* ---------- override list ---------- */

export class OverrideListState {
	items = $state<ParseOverride[]>([]);
	readonly #mediaId: () => string;

	constructor(mediaId: () => string, version: () => number) {
		this.#mediaId = mediaId;
		$effect(() => {
			void version();
			const id = mediaId();
			untrack(() => void this.load(id));
		});
	}

	private async load(mediaId: string): Promise<void> {
		try {
			this.items = (await parsingApi.listParseOverrides({ mediaId, limit: 200 })).items;
		} catch {
			/* toast shown */
		}
	}

	async remove(o: ParseOverride): Promise<void> {
		try {
			await parsingApi.deleteParseOverride({ overrideId: o.id });
			this.items = this.items.filter((x) => x.id !== o.id);
			pushToast(tr('rd.overrides.deleted'), 'info');
		} catch {
			/* toast shown */
		}
	}
}

/* ---------- /media/[id]/sentences ---------- */

export type SentenceFilter = 'all' | 'i+1' | 'i+0';

export class SentencesPage {
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	private retryMore = false;
	filter = $state<SentenceFilter>('all');
	segmentId = $state('');
	q = $state('');
	segments = $state<MediaSegment[]>([]);
	decks = $state<Deck[]>([]);
	rows = $state<Sentence[]>([]);
	cursor = $state<string | null>(null);
	loaded = $state(false);
	unknown = $state<Record<string, number | undefined>>({});
	private gen = 0;

	// Card form state.
	editing = $state<Sentence | null>(null);
	capturing = $state<string | null>(null);
	formSentence = $state('');
	formReading = $state('');
	formTranslation = $state('');
	formDeck = $state('');
	alsoVocab = $state(false);
	noteId = $state(uuidv7());
	vocabId = $state(uuidv7());
	readonly #mediaId: () => string;

	constructor(mediaId: () => string) {
		this.#mediaId = mediaId;
		$effect(() => {
			const id = mediaId();
			mediaApi
				.getMedia({ mediaId: id })
				.then((m) => {
					this.segments = m.segments ?? [];
				})
				.catch(() => {});
			decksApi
				.listDecks({ type: 'user' })
				.then((d) => {
					this.decks = d.items;
				})
				.catch(() => {});
		});
		$effect(() => {
			void mediaId();
			void this.filter;
			void this.segmentId;
			void this.q;
			this.loaded = false;
			const h = setTimeout(() => void this.load(false), 250);
			return () => clearTimeout(h);
		});
	}

	private async findUnknown(s: Sentence): Promise<void> {
		for (const t of s.tokens) {
			if (t.seq == null || t._class !== 'content') continue;
			const st = cachedStatus(t.seq) ?? (await loadStatus(t.seq));
			if (st === 'new') {
				this.unknown[s.id] = t.seq;
				return;
			}
		}
	}

	/** Load the failed page again. */
	retry(): void {
		void this.load(this.retryMore);
	}

	async load(more: boolean): Promise<void> {
		const mine = more ? this.gen : ++this.gen;
		try {
			const p = await mediaApi.listMediaSentences({
				mediaId: this.#mediaId(),
				// The generated client URL-encodes the query value, so "i+1" goes out as i%2B1.
				filter: this.filter,
				segmentId: this.segmentId || undefined,
				q: this.q.trim() || undefined,
				limit: 50,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			if (mine !== this.gen) return;
			this.rows = more ? [...this.rows, ...p.items] : p.items;
			this.cursor = p.nextCursor;
			this.loadFailed = false;
			for (const s of p.items) void this.findUnknown(s);
		} catch {
			if (mine === this.gen) {
				this.loadFailed = true; // the client already toasted
				this.retryMore = more;
			}
		} finally {
			if (mine === this.gen) this.loaded = true;
		}
	}

	open(s: Sentence): void {
		this.editing = s;
		this.formSentence = s.text;
		this.formReading = sentenceReading(s);
		this.formTranslation = '';
		this.formDeck = '';
		this.alsoVocab = false;
		this.noteId = uuidv7();
		this.vocabId = uuidv7();
	}

	cancel(): void {
		this.editing = null;
	}

	toggleCapture(id: string): void {
		this.capturing = this.capturing === id ? null : id;
	}

	closeCapture(): void {
		this.capturing = null;
	}

	async create(): Promise<void> {
		const s = this.editing;
		if (!s) return;
		const edited = { ...s, text: this.formSentence };
		try {
			await notesApi.createNote({
				noteCreate: buildSentenceNote(edited, {
					id: this.noteId,
					reading: this.formReading,
					translation: this.formTranslation,
					deckId: this.formDeck || undefined
				})
			});
			pushToast(ts('sn.created'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) {
				pushToast(ts('sn.duplicate'), 'info');
			} else return;
		}
		const seq = this.unknown[s.id];
		if (this.alsoVocab && seq != null) {
			try {
				await notesApi.createNote({
					noteCreate: buildVocabNote(s, seq, this.vocabId, this.formDeck || undefined)
				});
				pushToast(ts('sn.vocabCreated'), 'info');
			} catch (e) {
				if (e instanceof ResponseError && e.response.status === 409) {
					pushToast(ts('sn.duplicate'), 'info');
				}
			}
		}
		this.editing = null;
	}
}

/** Play a sentence's audio blob. */
export function playBlob(hash: string): void {
	void new Audio(`${API_BASE}/blobs/${hash}`).play();
}
