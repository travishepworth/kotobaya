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
 * One tap on a grade button. Created synchronously at the tap (so a second tap on the same card instance is
 * refused by state), sent in tap order in the background, restored on failure.
 */
interface PendingAnswer {
	answerId: string;
	item: QueueItem;
	shownAt: number;
	req: ReviewAnswerRequest;
	state: 'queued' | 'sending' | 'sent' | 'failed' | 'cancelled';
	done: Promise<void>;
	settle: () => void;
}

/**
 * The /review screen. Construct during component init (it registers an effect that resets the
 * per-card state whenever another card comes up), then call `start(drillSource)` from an effect.
 */
export class ReviewPage {
	loaded = $state(false);
	/** The settings or the queue could not be loaded; the view offers `retry`. */
	loadFailed = $state(false);
	items = $state<QueueItem[]>([]);
	counts = $state<ReviewQueueCounts | null>(null);
	dayEndsAt = $state<Date | null>(null);
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
	pendingCount = $state(0);
	private shownAt = Date.now();
	private lastCardId = '';
	private pending: PendingAnswer[] = [];
	private tail: Promise<void> = Promise.resolve();
	/** Bumped on every local change to the queue; a queue fetch that started before it is stale and dropped. */
	private epoch = 0;
	/** card id -> answer id of a failed submit, reused on the retry so a request that did land cannot double-log. */
	private readonly retryIds = new Map<string, string>();
	private readonly utcOffsetMin = -new Date().getTimezoneOffset();

	readonly isDrill = $derived(this.drillSource !== null);
	readonly buttons = $derived<GradeButton[]>(this.fiveButtons ? BUTTONS_5 : BUTTONS_4);
	readonly current = $derived<QueueItem | undefined>(this.items[0]);
	/** Nothing to show yet, but answers are still being recorded: the queue will be refreshed when they are. */
	readonly settling = $derived(this.pendingCount > 0 && !this.current);
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
		if (id !== this.lastCardId) this.showCard(id, this.retryIds.get(id) ?? uuidv7(), false, Date.now());
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
	 * (a tap or undo): applying it would bring back a card that was just answered.
	 */
	private async fetchQueue(guard = false): Promise<void> {
		if (this.isDrill) return;
		const epoch = this.epoch;
		const q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT });
		if (guard && (epoch !== this.epoch || this.pending.length > 0)) return;
		this.items = q.items;
		this.counts = q.counts;
		this.dayEndsAt = q.dayEndsAt;
		this.syncCard();
	}

	/** Refresh the queue and counts once no answer is in flight. Failures are toasted by the client. */
	private async refresh(): Promise<void> {
		if (this.isDrill || this.pending.length > 0) return;
		try {
			await this.fetchQueue(true);
		} catch {
			/* toast shown; the local queue stays */
		}
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
	start(source: string | null): void {
		this.drillSource = source;
		this.loaded = false;
		this.loadFailed = false;
		this.drillUnavailable = false;
		void (async () => {
			try {
				await this.loadStatic();
				if (source !== null) await this.fetchDrill(source);
				else await this.fetchQueue();
			} catch {
				this.loadFailed = true; // the client already toasted
			} finally {
				this.loaded = true;
			}
		})();
	}

	/** Load again after a failed start (same drill source). */
	retry(): void {
		this.start(this.drillSource);
	}

	async reload(): Promise<void> {
		this.busy = true;
		try {
			await this.fetchQueue();
		} catch {
			/* toast shown */
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
				durationMs: Math.max(0, Date.now() - this.shownAt),
				utcOffsetMin: this.utcOffsetMin
			},
			state: 'queued',
			done,
			settle
		};
		// Synchronously, before anything awaits: the card is gone and the next one is on its front.
		this.retryIds.delete(item.card.id);
		this.pending.push(entry);
		this.pendingCount = this.pending.length;
		this.history = [...this.history, { reviewId: entry.answerId, item }];
		this.items = this.items.slice(1);
		this.epoch++;
		this.newPress();
		this.tail = this.tail.then(() => this.send(entry));
		return done;
	}

	private async send(entry: PendingAnswer): Promise<void> {
		if (entry.state !== 'queued') return; // withdrawn after an earlier failure, or undone before it was sent
		entry.state = 'sending';
		try {
			const r = await reviewApi.answerReview({ reviewAnswerRequest: entry.req });
			entry.state = 'sent';
			this.history = this.history.map((h) => (h.reviewId === entry.answerId ? { ...h, reviewId: r.review.id } : h));
			this.dropPending(entry);
		} catch {
			// The client toasted the error. Put this card, and the taps queued behind it, back in order.
			this.fail(entry);
		}
		await this.refresh(); // no-op while more taps are queued behind this one
		entry.settle();
	}

	private dropPending(entry: PendingAnswer): void {
		this.pending = this.pending.filter((e) => e !== entry);
		this.pendingCount = this.pending.length;
	}

	private fail(entry: PendingAnswer): void {
		entry.state = 'failed';
		const later = this.pending.slice(this.pending.indexOf(entry) + 1).filter((e) => e.state === 'queued');
		const withdrawn = [entry, ...later];
		for (const e of later) {
			e.state = 'cancelled';
			e.settle();
		}
		this.pending = this.pending.filter((e) => !withdrawn.includes(e));
		this.pendingCount = this.pending.length;
		const ids = new Set(withdrawn.map((e) => e.answerId));
		this.history = this.history.filter((h) => !ids.has(h.reviewId));
		this.items = [...withdrawn.map((e) => e.item), ...this.items];
		this.epoch++;
		this.retryIds.set(entry.item.card.id, entry.answerId);
		this.showCard(entry.item.card.id, entry.answerId, true, entry.shownAt);
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
			const entry = this.pending.find((e) => e.answerId === last.reviewId);
			if (entry?.state === 'queued') {
				// Never sent: withdraw it, nothing to undo on the server.
				entry.state = 'cancelled';
				entry.settle();
				this.dropPending(entry);
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
		const item = this.current;
		if (!item || this.busy) return;
		const seq = item.entry?.seq ?? item.card.note?.entrySeq ?? null;
		if (seq === null) {
			pushToast(tr('review.noEntry'));
			return;
		}
		this.busy = true;
		try {
			await wordsApi.setWordStatus({ seq, wordStatusSet: { status: 'never_forget' } });
			pushToast(tr('review.neverForgetSet'));
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
