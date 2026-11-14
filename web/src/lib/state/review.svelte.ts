// Review screen state (/review): the queue (or a drill session), grading with core interval previews,
// undo, bury/suspend/never-forget and the inline note edit.
import type {
	DrillSessionRequestSourceEnum,
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
	private shownAt = Date.now();
	private lastCardId = '';
	private readonly utcOffsetMin = -new Date().getTimezoneOffset();

	readonly isDrill = $derived(this.drillSource !== null);
	readonly buttons = $derived<GradeButton[]>(this.fiveButtons ? BUTTONS_5 : BUTTONS_4);
	readonly current = $derived<QueueItem | undefined>(this.items[0]);
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
		// A new card on screen: fresh answer id (reused on retry so a double submit cannot double-log).
		$effect(() => {
			const id = this.current?.card.id ?? '';
			if (id !== this.lastCardId) {
				this.lastCardId = id;
				this.answerId = uuidv7();
				this.flipped = false;
				this.editing = false;
				this.shownAt = Date.now();
			}
		});
	}

	/** A new press: fresh answer id and flip state. Only called after a recorded answer or undo, never on retry. */
	private newPress(): void {
		this.answerId = uuidv7();
		this.flipped = false;
		this.editing = false;
		this.shownAt = Date.now();
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

	private async fetchQueue(): Promise<void> {
		if (this.isDrill) return;
		const q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT });
		this.items = q.items;
		this.counts = q.counts;
		this.dayEndsAt = q.dayEndsAt;
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

	flip(): void {
		this.flipped = true;
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

	/** Remove the current card locally (drill) or refetch the queue (scheduled). */
	private async advance(): Promise<void> {
		if (this.isDrill) this.items = this.items.slice(1);
		else await this.fetchQueue();
	}

	async grade(b: GradeButton): Promise<void> {
		const item = this.current;
		if (!item || !this.flipped || this.busy || this.editing) return;
		this.busy = true;
		try {
			const r = await reviewApi.answerReview({
				reviewAnswerRequest: {
					id: this.answerId,
					cardId: item.card.id,
					...(item.grammarSentence ? { sentenceId: item.grammarSentence.sentenceId } : {}),
					rating: b.rating,
					...(b.button ? { button: b.button } : {}),
					mode: item.reviewType === 'drill' || item.reviewType === 'cram' ? item.reviewType : 'schedule',
					durationMs: Math.max(0, Date.now() - this.shownAt),
					utcOffsetMin: this.utcOffsetMin
				}
			});
			this.history = [...this.history, { reviewId: r.review.id, item }];
		} catch {
			/* toast shown; answerId is kept so a retry is idempotent */
			this.busy = false;
			return;
		}
		// Recorded: drop the graded card locally so it is never shown as gradeable again, even if the
		// refetch fails. The next grade is a new press even if the same card comes back.
		this.items = this.items.slice(1);
		try {
			if (!this.isDrill) await this.fetchQueue();
		} catch {
			/* toast shown; the graded card is already gone, the rest of the old queue stays */
		} finally {
			this.newPress();
			this.busy = false;
		}
	}

	async undo(): Promise<void> {
		const last = this.history[this.history.length - 1];
		if (!last) {
			pushToast(tr('review.nothingToUndo'));
			return;
		}
		if (this.busy) return;
		this.busy = true;
		try {
			await reviewApi.undoReview({ reviewUndoRequest: { reviewId: last.reviewId } });
			this.history = this.history.slice(0, -1);
			this.newPress();
			if (this.isDrill) this.items = [last.item, ...this.items];
			else await this.fetchQueue();
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
