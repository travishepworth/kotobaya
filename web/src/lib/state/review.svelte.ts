// Review screen state (/review): the queue (or a drill session), grading with core interval previews,
// undo, bury/suspend/never-forget and the inline note edit.
import type {
	DrillSessionRequestSourceEnum,
	ReviewAnswerRequest,
	Note,
	NoteSummary,
	NoteType,
	Preset,
	QueueItem,
	ReviewQueueCounts
} from '../api/generated';
import { ResponseError } from '../api/client';
import { previewIntervals, type IntervalPreview } from '../core/index';
import { pushToast } from '../toasts.svelte';
import { cardsApi, noteTypesApi, notesApi, presetsApi, reviewApi, settingsApi, wordsApi } from '../review/api';
import { tr, type ReviewMessageKey } from '../review/messages';
import { BUTTONS_4, BUTTONS_5, formatInterval, toCorePreset, toSchedule, uuidv7, type GradeButton } from '../review/model';
import { renderPreview, type Rendered } from './preview.svelte';

export type { Note, NoteSummary, NoteType, QueueItem } from '../api/generated';
export type { GradeButton } from '../review/model';
export type { Rendered } from './preview.svelte';

const QUEUE_LIMIT = 20;
/**
 * C-27: refetches when a keep-going response has no item yet but the server still has deck entries to turn into
 * cards (`to_materialize` > 0 after its materialization rounds) or after a refresh that was dropped as stale.
 */
const KEEP_GOING_RETRIES = 3;

/**
 * One tap on a grade button. Created synchronously at the tap (so a second tap on the same card instance is
 * refused by state), sent in tap order in the background, restored on failure.
 */
interface PendingAnswer {
	answerId: string;
	item: QueueItem;
	shownAt: number;
	req: ReviewAnswerRequest;
	/** The page that tapped it: it restores its own queue and history if the send fails. */
	page: ReviewPage;
	state: 'queued' | 'sending' | 'sent' | 'failed' | 'cancelled';
	done: Promise<void>;
	settle: () => void;
}

/**
 * Answers tapped but not yet recorded. A module singleton, so they survive leaving /review and coming back
 * (a new page must not list a card whose answer is still on its way), and so a tab close can be warned about.
 */
class Outbox {
	pending: PendingAnswer[] = [];
	tail: Promise<void> = Promise.resolve();
	count = $state(0);
	/** card id -> answer id of a failed submit, reused on the retry so a request that did land cannot double-log. */
	readonly retryIds = new Map<string, string>();
	private warning = false;

	add(e: PendingAnswer): void {
		this.pending.push(e);
		this.sync();
		if (!this.warning && typeof window !== 'undefined') {
			this.warning = true;
			window.addEventListener('beforeunload', (ev) => {
				if (this.pending.length > 0) ev.preventDefault();
			});
		}
	}

	remove(es: PendingAnswer[]): void {
		this.pending = this.pending.filter((e) => !es.includes(e));
		this.sync();
	}

	hasCard(id: string): boolean {
		return this.pending.some((e) => e.item.card.id === id);
	}

	private sync(): void {
		this.count = this.pending.length;
	}
}

const outbox = new Outbox();

/** Test hook: forget all pending state. */
export function resetOutboxForTests(): void {
	outbox.pending = [];
	outbox.tail = Promise.resolve();
	outbox.count = 0;
	outbox.retryIds.clear();
}

/**
 * The /review screen. Construct during component init (it registers an effect that resets the
 * per-card state whenever another card comes up), then call `start(drillSource)` from an effect.
 */
async function Outbox_send(entry: PendingAnswer): Promise<void> {
	if (entry.state !== 'queued') return; // withdrawn after an earlier failure, or undone before it was sent
	entry.state = 'sending';
	try {
		const r = await reviewApi.answerReview({ reviewAnswerRequest: entry.req });
		entry.state = 'sent';
		outbox.remove([entry]);
		entry.page.sent(entry, r.review.id);
	} catch {
		// The client toasted the error. Withdraw this tap and those queued behind it, each page puts its own back.
		entry.state = 'failed';
		const later = outbox.pending.slice(outbox.pending.indexOf(entry) + 1).filter((e) => e.state === 'queued');
		for (const e of later) {
			e.state = 'cancelled';
			e.settle();
		}
		outbox.remove([entry, ...later]);
		outbox.retryIds.set(entry.item.card.id, entry.answerId);
		for (const pg of new Set([entry.page, ...later.map((e) => e.page)])) {
			const mine = [entry, ...later].filter((e) => e.page === pg);
			pg.restore(mine, mine.includes(entry) ? entry : null);
		}
	}
	await entry.page.afterSend(); // no-op while more taps are queued behind this one
	entry.settle();
}

export class ReviewPage {
	loaded = $state(false);
	/** The settings or the queue could not be loaded; the view offers `retry`. */
	loadFailed = $state(false);
	items = $state<QueueItem[]>([]);
	counts = $state<ReviewQueueCounts | null>(null);
	dayEndsAt = $state<Date | null>(null);
	/**
	 * Keep going: UI state of this one visit only (never persisted). Fetches with keep_going=true; dropped when
	 * the study day changes from the day it started.
	 */
	keepGoing = $state(false);
	/** `more_available` of the last scheduled fetch. */
	moreAvailable = $state(false);
	private keepGoingDay: number | null = null;
	noteTypes = $state<Map<string, NoteType>>(new Map());
	presets = $state<Map<string, Preset>>(new Map());
	fiveButtons = $state(false);
	furigana = $state(true);
	flipped = $state(false);
	editing = $state(false);
	busy = $state(false);
	drillUnavailable = $state(false);
	answerId = $state(uuidv7());
	/** Answered reviews of this session, newest last (for undo). In drill mode the item comes back. */
	history = $state<{ reviewId: string; item: QueueItem }[]>([]);
	/** Drill source of the running session; null for the scheduled queue. Set by `start`. */
	drillSource = $state<string | null>(null);
	/** Answers tapped but not yet recorded by the server (the screen has already moved on). */
	/** The queue could not be refreshed after the answers were recorded and nothing is left to show. */
	refreshFailed = $state(false);
	private shownAt = Date.now();
	private lastCardId = '';
	/** Bumped on every local change to the queue; a queue fetch that started before it is stale and dropped. */
	private epoch = 0;
	private readonly utcOffsetMin = -new Date().getTimezoneOffset();

	readonly isDrill = $derived(this.drillSource !== null);
	readonly buttons = $derived<GradeButton[]>(this.fiveButtons ? BUTTONS_5 : BUTTONS_4);
	readonly current = $derived<QueueItem | undefined>(this.items[0]);
	/** Nothing to show yet, but answers are still being recorded: the queue will be refreshed when they are. */
	readonly settling = $derived(outbox.count > 0 && !this.current && !this.refreshFailed);
	readonly gradeKeys = $derived(this.fiveButtons ? '1-5' : '1-4');
	readonly reviewTypeKey = $derived<ReviewMessageKey | null>(
		this.current?.reviewType === 'drill'
			? 'review.type.drill'
			: this.current?.reviewType === 'cram'
				? 'review.type.cram'
				: null
	);

	readonly rendered = $derived.by((): Rendered | null => {
		const item = this.current;
		if (!item) return null;
		const note = item.card.note;
		const nt = note ? this.noteTypes.get(note.noteTypeId) : undefined;
		if (!note || !nt) return { ok: false, detail: tr('review.missingNoteType') };
		return renderPreview({
			noteType: nt,
			kind: item.card.kind,
			fields: note.fields,
			mnemonic: note.mnemonic,
			entry: item.entry,
			kanji: item.kanji,
			// C-15: the rotated example sentence of a grammar card; the span is blanked/highlighted by core.
			cloze: item.grammarSentence
				? {
						sentenceId: item.grammarSentence.sentenceId,
						text: item.grammarSentence.text,
						start: item.grammarSentence.startOffset,
						end: item.grammarSentence.endOffset,
						translation: item.grammarSentence.translation ?? null
					}
				: null,
			side: this.flipped ? 'back' : 'front'
		});
	});

	/** A grammar card (not production) with no mined example sentence yet: explain the empty cloze. */
	readonly noExample = $derived(
		!!this.current &&
			this.current.card.note?.type === 'grammar' &&
			this.current.card.kind !== 'production' &&
			!this.current.grammarSentence
	);

	readonly previews = $derived.by((): IntervalPreview[] | null => {
		const item = this.current;
		const preset = item ? this.presets.get(item.card.presetId) : undefined;
		if (!item || !preset) return null;
		try {
			return previewIntervals(toSchedule(item.card), toCorePreset(preset), Date.now(), {
				reviewId: this.answerId,
				utcOffsetMin: this.utcOffsetMin
			});
		} catch {
			return null;
		}
	});

	constructor() {
		// Another card on screen (queue refetch, restore): fresh state for it.
		$effect(() => {
			this.syncCard();
		});
	}

	/** If the card on screen is not the one the per-card state belongs to, reset that state. */
	private syncCard(): void {
		const id = this.current?.card.id ?? '';
		if (id !== this.lastCardId) this.showCard(id, outbox.retryIds.get(id) ?? uuidv7(), false, Date.now());
	}

	private showCard(cardId: string, answerId: string, flipped: boolean, shownAt: number): void {
		this.lastCardId = cardId;
		this.answerId = answerId;
		this.flipped = flipped;
		this.editing = false;
		this.shownAt = shownAt;
	}

	/** The card now at the front is a new press: fresh answer id, back hidden. */
	private newPress(): void {
		this.showCard(this.current?.card.id ?? '', uuidv7(), false, Date.now());
	}

	previewText(b: GradeButton): string {
		const p = this.previews?.find((x) => x.rating === b.rating);
		return p ? formatInterval(p.intervalMs) : '';
	}

	noteTypeOf(note: NoteSummary): NoteType | undefined {
		return this.noteTypes.get(note.noteTypeId);
	}

	/* ---------- loading ---------- */

	private async loadStatic(): Promise<void> {
		const [nts, ps, st] = await Promise.all([
			noteTypesApi.listNoteTypes(),
			presetsApi.listPresets(),
			settingsApi.listSettings()
		]);
		this.noteTypes = new Map(nts.items.map((n) => [n.id, n]));
		this.presets = new Map(ps.items.map((p) => [p.id, p]));
		this.fiveButtons = st.items.find((s) => s.key === 'scheduler.grading_buttons')?.value === 5;
	}

	/**
	 * Fetch the scheduled queue. With `guard`, the result is dropped when the local queue changed meanwhile
	 * (a tap or undo): applying it would bring back a card that was just answered. Returns whether it was applied.
	 *
	 * The server turns deck entries into new cards inside this fetch (C-27); the client never creates them.
	 * Keep going is an endless stream: a keep-going response that has no item yet while the server reports
	 * entries still to materialize is fetched again, so the end screen shows only for a truly empty keep-going
	 * fetch.
	 */
	private async fetchQueue(guard = false): Promise<boolean> {
		if (this.isDrill) return false;
		const epoch = this.epoch;
		let q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT, keepGoing: this.keepGoing });
		for (let i = 0; i < KEEP_GOING_RETRIES && this.keepGoing && q.items.length === 0 && (q.toMaterialize ?? 0) > 0; i++) {
			q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT, keepGoing: true });
		}
		if (this.keepGoing) {
			const day = q.studyDay.getTime();
			if (this.keepGoingDay === null) this.keepGoingDay = day;
			else if (this.keepGoingDay !== day) {
				// A new study day: keep going ends, back to the normal queue.
				this.keepGoing = false;
				this.keepGoingDay = null;
				q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT, keepGoing: false });
			}
		}
		this.moreAvailable = q.moreAvailable ?? false;
		if (guard && (epoch !== this.epoch || outbox.pending.length > 0)) return false;
		// Cards whose answer is still on its way are not owed again. The card on screen stays where it is
		// (with its flip state); only what comes after it is replaced.
		const fresh = q.items.filter((i) => !outbox.hasCard(i.card.id));
		const cur = this.current;
		this.items =
			cur && fresh.some((i) => i.card.id === cur.card.id)
				? [cur, ...fresh.filter((i) => i.card.id !== cur.card.id)]
				: fresh;
		this.counts = q.counts;
		this.dayEndsAt = q.dayEndsAt;
		this.refreshFailed = false;
		this.syncCard();
		return true;
	}

	/**
	 * Refresh the queue and counts once no answer is in flight. In keep-going mode a refresh that was dropped as
	 * stale while nothing is left on screen and nothing is in flight is tried again: no later answer would trigger
	 * another one, and keep going must not stop while the server has cards.
	 */
	private async refresh(): Promise<void> {
		if (this.isDrill || outbox.pending.length > 0) return;
		try {
			for (let i = 0; i <= KEEP_GOING_RETRIES; i++) {
				const applied = await this.fetchQueue(true);
				if (applied || !this.keepGoing || this.current || outbox.pending.length > 0) break;
			}
		} catch {
			this.refreshFailed = true; // toast shown; the view offers a retry when there is nothing to show
		}
	}

	/** Refresh once every queued answer (also those of an earlier visit) has been sent. */
	private async refreshWhenIdle(): Promise<void> {
		await outbox.tail;
		await this.refresh();
	}

	private async fetchDrill(source: string): Promise<void> {
		try {
			const s = await reviewApi.createDrillSession({
				drillSessionRequest: { source: source as DrillSessionRequestSourceEnum, mode: 'drill' }
			});
			this.items = s.items;
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 501) this.drillUnavailable = true;
			else throw e;
		}
	}

	/** Load settings and the queue (or a drill session for `source`). Reads no state synchronously. */
	start(source: string | null, keepGoing = false): void {
		this.drillSource = source;
		this.keepGoing = keepGoing && source === null;
		this.keepGoingDay = null;
		this.moreAvailable = false;
		this.loaded = false;
		this.loadFailed = false;
		this.drillUnavailable = false;
		void (async () => {
			try {
				await this.loadStatic();
				if (source !== null) await this.fetchDrill(source);
				else await this.fetchQueue();
				if (source === null && outbox.pending.length > 0) void this.refreshWhenIdle();
			} catch {
				this.loadFailed = true; // the client already toasted
			} finally {
				this.loaded = true;
			}
		})();
	}

	/** Load again after a failed start (same drill source). */
	retry(): void {
		this.start(this.drillSource, this.keepGoing);
	}

	/** Switch this visit to keep-going mode and fetch the next batch. */
	async keepGoingOn(): Promise<void> {
		this.keepGoing = true;
		this.keepGoingDay = null;
		this.busy = true;
		this.refreshFailed = false;
		try {
			await this.fetchQueue();
		} catch {
			this.keepGoing = false;
			this.refreshFailed = true; // toast shown
		} finally {
			this.busy = false;
		}
	}

	async reload(): Promise<void> {
		this.busy = true;
		this.refreshFailed = false;
		try {
			await this.fetchQueue();
		} catch {
			this.refreshFailed = true; // toast shown
		} finally {
			this.busy = false;
		}
	}

	/* ---------- actions ---------- */

	/** Show the answer. Idempotent: a second call never moves on. */
	flip(): void {
		if (!this.current) return;
		this.flipped = true;
	}

	/**
	 * Keyboard entry for Space/Enter (reveal) and the grade digits. Auto-repeat is ignored. Returns whether
	 * the key was a review key that applies right now (the caller then prevents the default).
	 */
	key(k: string, repeat: boolean): boolean {
		if (!this.current) return false;
		if (k === ' ' || k === 'Enter') {
			if (!repeat) this.flip();
			return true;
		}
		if (/^[1-5]$/.test(k) && this.flipped && Number(k) <= this.buttons.length) {
			const b = this.buttons[Number(k) - 1];
			if (b && !repeat) void this.grade(b);
			return true;
		}
		return false;
	}

	startEdit(): void {
		this.editing = true;
	}

	cancelEdit(): void {
		this.editing = false;
	}

	toggleFurigana(): void {
		this.furigana = !this.furigana;
	}

	/** Remove the current card locally; a scheduled queue is refreshed behind it. */
	private async advance(): Promise<void> {
		this.items = this.items.slice(1);
		this.epoch++;
		this.newPress();
		await this.refresh();
	}

	/**
	 * Grade the current card. Accepted once per card instance and only on the back: the card is taken off
	 * the queue and the next one shown at once, the answer is sent in the background in tap order. The
	 * returned promise settles when this answer has been sent (or has failed and the card was restored).
	 */
	grade(b: GradeButton): Promise<void> {
		const item = this.current;
		if (!item || !this.flipped || this.busy || this.editing) return Promise.resolve();
		let settle!: () => void;
		const done = new Promise<void>((r) => (settle = r));
		const entry: PendingAnswer = {
			answerId: this.answerId,
			item,
			shownAt: this.shownAt,
			req: {
				id: this.answerId,
				cardId: item.card.id,
				...(item.grammarSentence ? { sentenceId: item.grammarSentence.sentenceId } : {}),
				rating: b.rating,
				...(b.button ? { button: b.button } : {}),
				mode: item.reviewType === 'drill' || item.reviewType === 'cram' ? item.reviewType : 'schedule',
				reviewedAt: new Date(),
				durationMs: Math.max(0, Date.now() - this.shownAt),
				utcOffsetMin: this.utcOffsetMin
			},
			page: this,
			state: 'queued',
			done,
			settle
		};
		// Synchronously, before anything awaits: the card is gone and the next one is on its front.
		outbox.retryIds.delete(item.card.id);
		outbox.add(entry);
		this.history = [...this.history, { reviewId: entry.answerId, item }];
		this.items = this.items.slice(1);
		this.epoch++;
		this.newPress();
		outbox.tail = outbox.tail.then(() => Outbox_send(entry));
		return done;
	}

	afterSend(): Promise<void> {
		return this.refresh();
	}

	/** The answer was recorded: its history entry now carries the server's review id. */
	sent(entry: PendingAnswer, reviewId: string): void {
		this.history = this.history.map((h) => (h.reviewId === entry.answerId ? { ...h, reviewId } : h));
	}

	/**
	 * A send failed: put these cards (the failed one first, then the taps withdrawn behind it) back at the
	 * front in order. The failed card comes back on its back so the retry is one tap.
	 */
	restore(withdrawn: PendingAnswer[], failed: PendingAnswer | null): void {
		const ids = new Set(withdrawn.map((e) => e.answerId));
		this.history = this.history.filter((h) => !ids.has(h.reviewId));
		this.items = [...withdrawn.map((e) => e.item), ...this.items];
		this.epoch++;
		if (failed) this.showCard(failed.item.card.id, failed.answerId, true, failed.shownAt);
		else this.syncCard();
	}

	async undo(): Promise<void> {
		if (this.history.length === 0) {
			pushToast(tr('review.nothingToUndo'));
			return;
		}
		if (this.busy) return;
		this.busy = true;
		try {
			let last = this.history[this.history.length - 1];
			const entry = outbox.pending.find((e) => e.answerId === last.reviewId);
			if (entry?.state === 'queued') {
				// Never sent: withdraw it, nothing to undo on the server.
				entry.state = 'cancelled';
				entry.settle();
				outbox.remove([entry]);
				this.history = this.history.slice(0, -1);
				this.items = [last.item, ...this.items];
				this.epoch++;
				this.newPress();
				return;
			}
			if (entry?.state === 'sending') {
				await entry.done; // no orphaned grade: wait until it is recorded (or has failed and been restored)
				if ((entry.state as PendingAnswer['state']) !== 'sent') return;
				last = this.history[this.history.length - 1];
			}
			await reviewApi.undoReview({ reviewUndoRequest: { reviewId: last.reviewId } });
			this.history = this.history.slice(0, -1);
			this.items = [last.item, ...this.items];
			this.epoch++;
			this.newPress();
			await this.refresh();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async bury(): Promise<void> {
		const item = this.current;
		if (!item || this.busy) return;
		this.busy = true;
		try {
			// Until the end of the study day (falls back to 24 h when the queue gave none).
			const until = this.dayEndsAt ?? new Date(Date.now() + 24 * 3600 * 1000);
			await cardsApi.bulkCards({
				cardBulkRequest: { action: { type: 'bury', until }, cardIds: new Set([item.card.id]) }
			});
			pushToast(tr('review.buried'));
			await this.advance();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async suspend(): Promise<void> {
		const item = this.current;
		if (!item || this.busy) return;
		this.busy = true;
		try {
			await cardsApi.bulkCards({
				cardBulkRequest: { action: { type: 'suspend' }, cardIds: new Set([item.card.id]) }
			});
			pushToast(tr('review.suspended'));
			await this.advance();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async neverForget(): Promise<void> {
		await this.#setWord('never_forget', 'review.neverForgetSet');
	}

	async blacklist(): Promise<void> {
		await this.#setWord('blacklisted', 'review.blacklistSet');
	}

	/** Word-status override on the current card's entry; the word leaves the queue, so show the next card. */
	async #setWord(status: 'never_forget' | 'blacklisted', done: 'review.neverForgetSet' | 'review.blacklistSet'): Promise<void> {
		const item = this.current;
		if (!item || this.busy) return;
		const seq = item.entry?.seq ?? item.card.note?.entrySeq ?? null;
		if (seq === null) {
			pushToast(tr('review.noEntry'));
			return;
		}
		this.busy = true;
		try {
			await wordsApi.setWordStatus({ seq, wordStatusSet: { status } });
			pushToast(tr(done));
			await this.advance();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	noteSaved(n: Note): void {
		const item = this.current;
		if (item?.card.note) {
			item.card.note = { ...item.card.note, fields: n.fields, mnemonic: n.mnemonic, tags: n.tags };
		}
		this.editing = false;
		pushToast(tr('review.edit.saved'));
	}
}

/** Inline note edit on the review screen: the note type's fields and the mnemonic, saved with updateNote. */
export class NoteEditFormState {
	fields = $state<Record<string, string>>({});
	mnemonic = $state('');
	busy = $state(false);

	readonly #note: () => NoteSummary;
	readonly #noteType: () => NoteType | undefined;

	readonly keys = $derived.by(() => {
		const nt = this.#noteType();
		return nt
			? nt.fields.map((f) => ({ key: f.key, label: f.label }))
			: Object.keys(this.#note().fields).map((k) => ({ key: k, label: k }));
	});

	constructor(note: () => NoteSummary, noteType: () => NoteType | undefined) {
		this.#note = note;
		this.#noteType = noteType;
		// The form edits a copy; the props are only the starting point.
		const n = note();
		this.fields = { ...n.fields };
		this.mnemonic = n.mnemonic;
	}

	async save(onsaved: (n: Note) => void): Promise<void> {
		if (this.busy) return;
		const note = this.#note();
		this.busy = true;
		try {
			const changed: Record<string, string> = {};
			for (const { key } of this.keys) {
				if ((this.fields[key] ?? '') !== (note.fields[key] ?? '')) changed[key] = this.fields[key] ?? '';
			}
			const n = await notesApi.updateNote({
				noteId: note.id,
				notePatch: {
					...(Object.keys(changed).length > 0 ? { fields: changed } : {}),
					...(this.mnemonic !== note.mnemonic ? { mnemonic: this.mnemonic } : {})
				}
			});
			onsaved(n);
		} catch {
			/* toast shown by the client */
		} finally {
			this.busy = false;
		}
	}
}
