// Review screen state (/review): the queue (or a drill session), grading with core interval previews,
// undo, bury/suspend/never-forget and the inline note edit.
import type {
	Card,
	DrillSessionRequestSourceEnum,
	ReviewAnswerRequest,
	Note,
	NoteSummary,
	NoteType,
	Preset,
	QueueItem,
	ReviewQueue
} from '../api/generated';
import { ResponseError, readProblem } from '../api/client';
import { previewIntervals, type IntervalPreview } from '../core/index';
import { haptic } from '../api/shell/nav';
import { ReviewBackPolicy } from '../api/shell/reviewBackPolicy';
import { pushToast } from '../toasts.svelte';
import { cardsApi, noteTypesApi, notesApi, presetsApi, reviewApi, settingsApi, wordsApi } from '../review/api';
import { tr, type ReviewMessageKey } from '../review/messages';
import {
	BUTTONS_4,
	BUTTONS_5,
	formatInterval,
	reviewPhase,
	toCorePreset,
	toSchedule,
	uuidv7,
	type GradeButton,
	type ReviewPhase
} from '../review/model';
import {
	clearKeepGoing,
	expireKeepGoingIfIdle,
	fetchWithKeepGoing,
	startKeepGoing,
	touchKeepGoing
} from '../review/keepGoingDay';
import { loopView, type LoopView } from '../review/loop';
import { renderPreview, type Rendered } from './preview.svelte';

export type { KanjiPart, Note, NoteSummary, NoteType, QueueItem } from '../api/generated';
export type { GradeButton, ReviewPhase } from '../review/model';
export type { LoopPhase, LoopView, ReviewLoop } from '../review/loop';
export { countdown, minutesUntil } from '../review/loop';
export type { Rendered } from './preview.svelte';

const DUE_SLACK_MS = 250;
const QUEUE_LIMIT = 20;
/** C4: a waiting-state refetch happens this long after the next learning card falls due (server clock slack). */
const WAKE_SLACK_MS = 1000;
/** ... and never sooner than this after the previous one, so a clock skew cannot turn waiting into polling. */
const WAKE_MIN_MS = 3000;
/**
 * C5 (C-27): `reviewing` with no item yet while the server still has deck entries to turn into cards
 * (`to_materialize` > 0 after its rounds) is fetched again at most this many times in a row; also the retries of
 * a refresh that was dropped as stale.
 */
const MATERIALIZE_RETRIES = 3;
/**
 * RC-WEB: a queue fetch (or an answer/undo) that has not answered by then is abandoned: the screen offers a retry
 * instead of a spinner that never ends. The live server took ~6 s per queue fetch.
 */
export const QUEUE_TIMEOUT_MS = 30_000;
const ANSWER_TIMEOUT_MS = 30_000;
/**
 * The server says `reviewing` but served no card (a response that raced an answer): fetch again after this,
 * doubling up to the cap. Never an end screen while the loop says cards are ready.
 */
const PENDING_BASE_MS = 2000;
/** RC-WEB: when this few cards are left locally, fetch more while answers are still being saved (no drain). */
const PREFETCH_LOW = 3;
const PENDING_MAX_MS = 30_000;
/** ... and stops after this many refetches in a row (the view then offers Refresh): no endless polling. */
const PENDING_MAX_TRIES = 5;

/**
 * RC-WEB: what a failed answer means.
 * - 'clock': 422 on `/reviewed_at` (this device's clock is off, e.g. `in_future`): the card stays, the retry is
 *   sent without `reviewed_at` (the server's now).
 * - 'permanent': refused for good (card deleted 410, suspended 409, unknown 404, other 422): resending the same
 *   answer cannot succeed, so the card is dropped instead of pinned.
 * - 'retry': timeouts, network errors, 401, 408, 429 and 5xx.
 */
async function classify(e: unknown): Promise<'clock' | 'permanent' | 'retry'> {
	if (!(e instanceof ResponseError)) return 'retry';
	const status = e.response.status;
	if (status === 422) {
		const p = await readProblem(e.response);
		if (p?.errors?.some((f) => f.pointer === '/reviewed_at')) return 'clock';
	}
	return [404, 409, 410, 422].includes(status) ? 'permanent' : 'retry';
}

/** C5: `reviewing` with no item served yet but deck entries still to turn into cards. */
function materializing(q: ReviewQueue): boolean {
	return q.items.length === 0 && (q.toMaterialize ?? 0) > 0 && loopView(q).phase === 'reviewing';
}

/** The request did not answer in time (it was aborted). */
export class RequestTimeout extends Error {
	constructor() {
		super('request timed out');
	}
}

/**
 * Run a generated-client call with an abort signal that fires after `ms` (or when `outer` aborts). Rejects with
 * RequestTimeout on the timer even if the call ignores the signal.
 */
function timed<T>(ms: number, run: (init: RequestInit) => Promise<T>, outer?: AbortSignal): Promise<T> {
	const ctl = new AbortController();
	const onAbort = () => ctl.abort();
	outer?.addEventListener('abort', onAbort);
	return new Promise<T>((resolve, reject) => {
		const t = setTimeout(() => {
			ctl.abort();
			reject(new RequestTimeout());
		}, ms);
		run({ signal: ctl.signal })
			.then(resolve, reject)
			.finally(() => {
				clearTimeout(t);
				outer?.removeEventListener('abort', onAbort);
			});
	});
}

/**
 * One tap on a grade button. Created synchronously at the tap (so a second tap on the same card instance is
 * refused by state), sent in tap order in the background, restored on failure.
 */
interface PendingAnswer {
	answerId: string;
	item: QueueItem;
	button: GradeButton;
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
	/** Count of answers ever added, and the card ids of the latest ones (to drop them from stale fetches). */
	seq = 0;
	private added: { seq: number; id: string }[] = [];

	/** Card ids answered after fetch number `seq` began, plus those still in flight. */
	answeredSince(seq: number): Set<string> {
		const ids = new Set(this.pending.map((e) => e.item.card.id));
		for (const a of this.added) if (a.seq > seq) ids.add(a.id);
		return ids;
	}

	add(e: PendingAnswer): void {
		this.seq++;
		this.added.push({ seq: this.seq, id: e.item.card.id });
		if (this.added.length > 200) this.added.splice(0, this.added.length - 200);
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
		const r = await timed(ANSWER_TIMEOUT_MS, (init) => reviewApi.answerReview({ reviewAnswerRequest: entry.req }, init));
		entry.state = 'sent';
		outbox.remove([entry]);
		entry.page.sent(entry, r.review.id, r.card);
	} catch (e) {
		const kind = await classify(e);
		if (kind === 'permanent') {
			// Never recordable: drop this card (with a toast) and keep sending the taps behind it.
			entry.state = 'failed';
			outbox.remove([entry]);
			outbox.retryIds.delete(entry.item.card.id);
			entry.page.dropped(entry);
			await entry.page.afterSend();
			entry.settle();
			return;
		}
		// Worth a retry (timeout, network, 5xx; the client toasted all but a timeout, which the page's notice
		// shows). Withdraw this tap and those queued behind it, each page puts its own back.
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
			pg.restore(mine, mine.includes(entry) ? entry : null, kind === 'clock');
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
	dayEndsAt = $state<Date | null>(null);
	/** ADR-037: `loop` of the last applied queue fetch (phase and every number shown); null before the first. */
	view = $state<LoopView | null>(null);
	/** The `keep_going` the last applied fetch was sent with (the persisted per-client flag, rule C2). */
	keepGoing = $state(false);
	/**
	 * C1 due first: the instant from which the local page may be stale because a learning card has come due:
	 * min(`later_today.next_due_at` of the last fetch, `due_at` of each learning answer recorded since it began).
	 */
	private dueFirstAt: number | null = null;
	/** Learning answers recorded (`recordedAt`) with the card's next `due_at`, for C1 across a fetch in flight. */
	private learningDues: { recordedAt: number; dueAt: number }[] = [];
	/** `to_materialize` of the last applied fetch (C5: retries ran out when it is still > 0 with nothing served). */
	private toMaterialize = 0;
	private dueTimer: ReturnType<typeof setTimeout> | null = null;
	private wakeTimer: ReturnType<typeof setTimeout> | null = null;
	private lastWake = 0;
	/** Refetches in a row in the pending phase (backoff). */
	private pendingTries = 0;
	/** Pending polling gave up after PENDING_MAX_TRIES; the view offers Refresh (`reload`), which resumes it. */
	pendingStopped = $state(false);
	/** Bumped whenever a full (non-prefetch) queue fetch is applied; a prefetch begun before it is stale. */
	private applied = 0;
	/** Bumped by start/dispose: a load or fetch begun under an older generation is dropped. */
	private gen = 0;
	/** The screen was left: nothing (refresh, wake, fetch) runs on this page any more. Never reset. */
	private disposed = false;
	/** A prefetch (refill while answers are pending) is in flight. */
	private prefetching = false;
	/**
	 * RC-WEB: the card just graded when that emptied the local queue. Shown, inert, while its answer is saved
	 * (`settling`), so the stage does not flash a skeleton between the last card and what comes next.
	 */
	held = $state<QueueItem | null>(null);
	/** Aborts this page's queue fetches when it is left. */
	private abort = new AbortController();
	/**
	 * RC-WEB: an action failed and the user should know (beside the client's toast, which a timeout does not get).
	 * answerFailed: the card is back on its back and `retryAnswer` sends the same answer again.
	 * clockSkew: the same, but the server refused this device's time (`reviewed_at`); the retry uses the server's.
	 */
	notice = $state<'answerFailed' | 'clockSkew' | 'undoFailed' | null>(null);
	private failedAnswer: { cardId: string; button: GradeButton; serverTime: boolean } | null = null;
	private failedCardId = $state<string | null>(null);
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
	/** The card on screen cannot be graded until the failed answer is retried (same answer id). */
	readonly gradeLocked = $derived(
		(this.notice === 'answerFailed' || this.notice === 'clockSkew') && !!this.current && this.failedCardId === this.current.card.id
	);
	/** Nothing to show yet, but answers are still being recorded: the queue will be refreshed when they are. */
	readonly settling = $derived(outbox.count > 0 && !this.current && !this.refreshFailed);
	/** What the stage shows: the current card, or the held one while answers settle. */
	readonly display = $derived<QueueItem | undefined>(this.current ?? (this.settling ? (this.held ?? undefined) : undefined));
	/** The numbers on screen: `loop` of the last applied fetch (null in a drill and before the first fetch). */
	readonly loop = $derived(this.isDrill ? null : (this.view?.loop ?? null));
	/** What the screen shows; with no card it is `loop.phase` only (C4). */
	readonly phase = $derived<ReviewPhase>(
		reviewPhase({
			loaded: this.loaded,
			loadFailed: this.loadFailed,
			refreshFailed: this.refreshFailed,
			settling: this.settling,
			hasCurrent: !!this.current,
			isDrill: this.isDrill,
			drillUnavailable: this.drillUnavailable,
			loopPhase: this.view?.phase ?? null
		})
	);
	readonly gradeKeys = $derived(this.fiveButtons ? '1-5' : '1-4');
	readonly reviewTypeKey = $derived<ReviewMessageKey | null>(
		this.current?.reviewType === 'drill'
			? 'review.type.drill'
			: this.current?.reviewType === 'cram'
				? 'review.type.cram'
				: null
	);

	readonly rendered = $derived.by((): Rendered | null => {
		const item = this.display;
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
			frontFurigana: item.frontFurigana ?? false,
			// A held card was graded on its back and stays so.
			side: this.flipped || !this.current ? 'back' : 'front'
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
		if (this.current && this.held) this.held = null; // a card is on screen: nothing to hold any more
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
	 * `keep_going` is the persisted per-client flag (C2, dropped on a new study day). A `reviewing` response with
	 * no item yet while the server reports entries still to materialize is fetched again (C5, at most 3 times).
	 */
	private async fetchQueue(guard = false, append = false): Promise<boolean> {
		if (this.isDrill || this.disposed) return false;
		const gen = this.gen;
		const epoch = this.epoch;
		const appliedAtStart = this.applied;
		const outboxSeq = outbox.seq;
		const startedAt = Date.now();
		const pendingAtStart = new Set(outbox.pending.map((e) => e.item.card.id));
		let sent = false;
		const get = (keepGoing: boolean): Promise<ReviewQueue> => {
			sent = keepGoing;
			return this.getQueue(keepGoing);
		};
		let q = await fetchWithKeepGoing(get);
		for (let i = 0; i < MATERIALIZE_RETRIES && !append && materializing(q); i++) {
			if (gen !== this.gen) return false;
			q = await get(sent);
		}
		// A newer start (retry) or leaving the screen supersedes this fetch.
		if (gen !== this.gen || this.disposed) return false;
		if (append && this.applied !== appliedAtStart) return false; // stale prefetch: see below
		if (guard && (epoch !== this.epoch || outbox.pending.length > 0)) return false;
		// Cards whose answer is still on its way are not owed again. The card on screen stays where it is
		// (with its flip state); only what comes after it is replaced.
		// Also those answered while this request was in flight: the server may have built the queue before it
		// applied them, and an unguarded fetch (start, reload) is not dropped by the epoch.
		const answered = outbox.answeredSince(outboxSeq);
		const fresh = q.items.filter((i) => !answered.has(i.card.id) && !pendingAtStart.has(i.card.id));
		const cur = this.current;
		if (append) {
			// A full fetch applied while this prefetch was in flight is newer (it may already reflect an answer that
			// buried a sibling, or a card suspended elsewhere): drop this one.
			if (this.applied !== appliedAtStart) return false;
			// C1: asked before a learning card came due, so it may not list that card first: the refresh after the
			// answers fetches again.
			if (this.dueFirstAt !== null && startedAt < this.dueFirstAt && this.dueFirstPassed()) return false;
			// Prefetch: the card on screen, then the server's order, then any local card the server did not list
			// (an undo or restore may have put one back meanwhile): never removes a local card. The loop numbers
			// wait for the refresh after the answers.
			const merged = cur ? [cur, ...fresh.filter((i) => i.card.id !== cur.card.id)] : [...fresh];
			const ids = new Set(merged.map((i) => i.card.id));
			this.items = [...merged, ...this.items.filter((i) => !ids.has(i.card.id))];
			this.noteDueFirst(q, startedAt);
			this.syncCard();
			return true;
		}
		// A card whose answer failed stays in front until it is retried (never advanced past).
		const keepCur = cur && (fresh.some((i) => i.card.id === cur.card.id) || this.failedAnswer?.cardId === cur.card.id);
		this.items =
			keepCur
				? [cur, ...fresh.filter((i) => i.card.id !== cur.card.id)]
				: fresh;
		this.applied++;
		this.view = loopView(q);
		this.keepGoing = sent;
		this.toMaterialize = q.toMaterialize ?? 0;
		this.dayEndsAt = q.dayEndsAt;
		this.noteDueFirst(q, startedAt);
		this.refreshFailed = false;
		this.syncCard();
		this.scheduleWake();
		return true;
	}

	/** C1: T = the response's next due learning card, and any learning answer recorded since the fetch began. */
	private noteDueFirst(q: ReviewQueue, startedAt: number): void {
		this.learningDues = this.learningDues.filter((d) => d.recordedAt >= startedAt);
		const next = q.loop ? q.loop.laterToday.nextDueAt : (q.nextLearningDueAt ?? null);
		const all = [...(next ? [next.getTime()] : []), ...this.learningDues.map((d) => d.dueAt)];
		this.dueFirstAt = all.length > 0 ? Math.min(...all) : null;
		this.scheduleDueFetch();
	}

	/** C1: a learning card has come due since the page was fetched, so the next card must come from a new fetch. */
	private dueFirstPassed(): boolean {
		return !this.isDrill && this.dueFirstAt !== null && Date.now() >= this.dueFirstAt;
	}

	private getQueue(keepGoing: boolean) {
		if (this.disposed) return Promise.reject(new Error('review page disposed'));
		return timed(
			QUEUE_TIMEOUT_MS,
			(init) => reviewApi.getReviewQueue({ limit: QUEUE_LIMIT, keepGoing }, init),
			this.abort.signal
		);
	}

	/**
	 * When the screen shows no card, the instant a learning card falls due (C4: `waiting`; also on the
	 * goal-reached / done-for-today screens, where a card coming due makes the loop `reviewing` again).
	 */
	private wakeTarget(): Date | null {
		const v = this.view;
		if (this.isDrill || this.current || !v) return null;
		return v.phase === 'waiting' || v.phase === 'goal_reached' || v.phase === 'done_for_today' ? v.loop.laterToday.nextDueAt : null;
	}

	/**
	 * C4: one timer at the next learning card's due instant while no card is on screen (no polling loop).
	 * `reviewing` with nothing served: fetch again with backoff (C5 entries still to materialize: retries ran out
	 * in fetchQueue, so the view offers Refresh instead).
	 */
	private scheduleWake(): void {
		this.clearWake();
		if (this.isDrill || this.disposed) return;
		const due = this.wakeTarget();
		let at: number;
		if (due) {
			this.pendingTries = 0;
			at = due.getTime() + WAKE_SLACK_MS;
		} else if (!this.current && this.view?.phase === 'reviewing') {
			if (this.toMaterialize > 0 || this.pendingTries >= PENDING_MAX_TRIES) {
				this.pendingStopped = true; // the view offers Refresh
				return;
			}
			at = Date.now() + Math.min(PENDING_MAX_MS, PENDING_BASE_MS * 2 ** this.pendingTries);
			this.pendingTries++;
		} else {
			this.pendingTries = 0;
			this.pendingStopped = false;
			return;
		}
		const delay = Math.max(at - Date.now(), this.lastWake + WAKE_MIN_MS - Date.now(), 0);
		this.wakeTimer = setTimeout(() => void this.wake(), delay);
	}

	private clearWake(): void {
		if (this.wakeTimer !== null) clearTimeout(this.wakeTimer);
		this.wakeTimer = null;
	}

	private async wake(): Promise<void> {
		this.wakeTimer = null;
		this.lastWake = Date.now();
		const gen = this.gen;
		await this.refreshWhenIdle();
		// A fetch that failed or was dropped leaves no timer behind: keep the schedule alive (not after dispose).
		if (gen === this.gen && !this.disposed && this.wakeTimer === null) this.scheduleWake();
	}

	/** The tab became visible again: timers are throttled while hidden, so catch up at once if the card is due. */
	onVisible(): void {
		expireKeepGoingIfIdle(); // ADR-039 idle amendment: a page left open for hours has gone idle
		const due = this.wakeTarget();
		if (this.isDrill || !due || this.busy || this.disposed) return;
		if (Date.now() >= due.getTime() && Date.now() - this.lastWake >= WAKE_MIN_MS) {
			this.clearWake();
			void this.wake();
		} else if (this.wakeTimer === null) this.scheduleWake();
	}

	/** Leaving the screen: no timer or queue fetch outlives it (answers in the outbox still go out). */
	dispose(): void {
		this.disposed = true;
		this.gen++;
		this.clearWake();
		if (this.dueTimer !== null) clearTimeout(this.dueTimer);
		this.dueTimer = null;
		clearKeepGoing(); // ADR-039: keep going lasts one review visit
		this.abort.abort();
	}

	/**
	 * Refresh the queue and counts once no answer is in flight. A refresh that was dropped as stale while nothing
	 * is left on screen and nothing is in flight is tried again (in any mode): no later answer would trigger
	 * another one, and the screen would show an end state while the server has cards.
	 */
	private async refresh(): Promise<void> {
		if (this.isDrill || this.disposed) return;
		if (outbox.pending.length > 0) {
			// Answers are still being saved: refill instead, so the local queue does not drain meanwhile.
			void this.prefetch();
			return;
		}
		const gen = this.gen;
		try {
			for (let i = 0; i <= MATERIALIZE_RETRIES; i++) {
				const applied = await this.fetchQueue(true);
				if (applied || this.current || outbox.pending.length > 0 || gen !== this.gen) break;
			}
		} catch {
			if (gen === this.gen && !this.disposed) this.refreshFailed = true; // inline notice, or the error screen
		}
		// Every refresh dropped as stale while the counts say cards remain: fetch again later, never end here.
		if (gen === this.gen && !this.disposed && this.wakeTimer === null) this.scheduleWake();
	}

	/**
	 * Fetch more cards while answers are still on their way, once few are left locally. Not epoch-guarded: the
	 * cards answered before or during it are filtered out by fetchQueue (outbox), the card on screen is kept.
	 */
	private async prefetch(): Promise<void> {
		if (this.isDrill || this.disposed || this.prefetching || this.items.length > PREFETCH_LOW) return;
		this.prefetching = true;
		try {
			await this.fetchQueue(false, true);
		} catch {
			/* the refresh after the answers are saved tries again */
		} finally {
			this.prefetching = false;
		}
	}

	/**
	 * Seamless due-card slot-in: fetch in the background (no loading state) and splice the server's order behind
	 * the card on screen. Used when a learning card has come due (never before: the server lists it only then).
	 */
	private async spliceFetch(): Promise<void> {
		if (this.isDrill || this.disposed || this.prefetching) return;
		this.prefetching = true;
		try {
			await this.fetchQueue(false, true);
		} catch {
			/* the next advance or the refresh after the answers tries again */
		} finally {
			this.prefetching = false;
		}
	}

	/** One timer at the next learning card's due instant while a card is on screen: splice it in behind it. */
	private scheduleDueFetch(): void {
		if (this.dueTimer !== null) clearTimeout(this.dueTimer);
		this.dueTimer = null;
		if (this.isDrill || this.disposed || this.dueFirstAt === null) return;
		const delay = Math.max(this.dueFirstAt + DUE_SLACK_MS - Date.now(), 0);
		this.dueTimer = setTimeout(() => {
			this.dueTimer = null;
			if (this.disposed || this.isDrill) return;
			if (this.current && outbox.pending.length === 0) void this.spliceFetch();
		}, delay);
	}

	/** Refresh once every queued answer (also those of an earlier visit) has been sent. */
	private async refreshWhenIdle(): Promise<void> {
		await outbox.tail;
		await this.refresh();
	}

	private async fetchDrill(source: string): Promise<void> {
		const gen = this.gen;
		try {
			const s = await reviewApi.createDrillSession({
				drillSessionRequest: { source: source as DrillSessionRequestSourceEnum, mode: 'drill' }
			});
			if (gen === this.gen) this.items = s.items;
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 501) this.drillUnavailable = true;
			else throw e;
		}
	}

	/** Load settings and the queue (or a drill session for `source`). Reads no state synchronously. */
	start(source: string | null): void {
		if (this.disposed) return;
		const gen = ++this.gen;
		// A retry during a slow load: the old fetch is abandoned (it would hold a connection for nothing).
		this.abort.abort();
		this.abort = new AbortController();
		this.drillSource = source;
		this.view = null;
		this.dueFirstAt = null;
		this.toMaterialize = 0;
		this.pendingTries = 0;
		this.pendingStopped = false;
		this.clearWake();
		this.loaded = false;
		this.loadFailed = false;
		this.refreshFailed = false;
		this.drillUnavailable = false;
		void (async () => {
			try {
				// In parallel: the queue is the slow call, nothing about it needs the settings.
				await Promise.all([this.loadStatic(), source !== null ? this.fetchDrill(source) : this.fetchQueue()]);
				if (gen !== this.gen) return;
				if (source === null && outbox.pending.length > 0) void this.refreshWhenIdle();
			} catch {
				if (gen === this.gen) this.loadFailed = true; // the client toasted (a timeout shows the error state only)
			} finally {
				if (gen === this.gen) this.loaded = true;
			}
		})();
	}

	/** Load again after a failed start (same drill source). */
	retry(): void {
		this.start(this.drillSource);
	}

	/**
	 * Keep going (C2): persisted for the shown study day, so every later fetch of this client (Home, review, after
	 * a restart) sends it until the study day changes; then fetch the next batch.
	 */
	async keepGoingOn(): Promise<void> {
		const v = this.view;
		if (!v || this.busy) return;
		startKeepGoing(v.studyDay);
		this.busy = true;
		this.refreshFailed = false;
		try {
			await this.fetchQueue();
		} catch {
			clearKeepGoing(); // nothing was shown with it: back to the screen it was pressed on
			this.refreshFailed = true; // toast shown
		} finally {
			this.busy = false;
		}
	}

	async reload(): Promise<void> {
		this.busy = true;
		this.refreshFailed = false;
		// A manual Refresh resumes pending polling with a fresh backoff.
		this.pendingTries = 0;
		this.pendingStopped = false;
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
		if (!this.flipped) haptic('tick');
		this.flipped = true;
	}

	/** ADR-030 / SHELL.md 6.2 step 2: the 3 s rule of back-undo (port of Android's `ReviewBackPolicy`). */
	private readonly backPolicy = new ReviewBackPolicy();

	/** Would a back press undo now? No side effects. */
	canBackUndo(): boolean {
		return this.backPolicy.wouldUndo(this.current !== null || this.history.length > 0, this.history.length > 0 && !this.busy);
	}

	/** Back pressed: undo once through the `u` key's path, with the toast. False when back should fall through. */
	backUndo(): boolean {
		if (!this.backPolicy.shouldUndo(this.current !== null || this.history.length > 0, this.history.length > 0 && !this.busy)) {
			return false;
		}
		void this.undo().then(() => {
			if (this.notice !== 'undoFailed') pushToast(tr('review.undone'));
		});
		return true;
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
		if (/^[1-5]$/.test(k) && this.flipped && !this.gradeLocked && Number(k) <= this.buttons.length) {
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
		this.clearNotice();
		this.items = this.items.slice(1); // seamless: the next local card is shown at once; a due card is spliced in
		this.epoch++;
		this.newPress();
		if (this.dueFirstPassed() && this.items.length > 0) void this.spliceFetch();
		await this.refresh();
	}

	/**
	 * Grade the current card. Accepted once per card instance and only on the back: the card is taken off
	 * the queue and the next one shown at once, the answer is sent in the background in tap order. The
	 * returned promise settles when this answer has been sent (or has failed and the card was restored).
	 */
	grade(b: GradeButton): Promise<void> {
		const item = this.current;
		if (!item || !this.flipped || this.busy || this.editing || this.gradeLocked) return Promise.resolve();
		haptic(b.rating === 1 ? 'reject' : 'confirm');
		return this.send(item, b);
	}

	/** Set after the first clock-skew 422: no more `reviewed_at` for the rest of this page visit. */
	private clockSkewed = false;

	private send(item: QueueItem, b: GradeButton, serverTime = false): Promise<void> {
		let settle!: () => void;
		const done = new Promise<void>((r) => (settle = r));
		this.clearNotice();
		this.backPolicy.answered();
		touchKeepGoing(); // ADR-039 idle amendment: an answer restarts the keep-going idle clock
		const entry: PendingAnswer = {
			answerId: this.answerId,
			item,
			button: b,
			shownAt: this.shownAt,
			req: {
				id: this.answerId,
				cardId: item.card.id,
				...(item.grammarSentence ? { sentenceId: item.grammarSentence.sentenceId } : {}),
				rating: b.rating,
				...(b.button ? { button: b.button } : {}),
				mode: item.reviewType === 'drill' || item.reviewType === 'cram' ? item.reviewType : 'schedule',
				// After a clock error the server's now is used (reviewed_at omitted).
				...(serverTime || this.clockSkewed ? {} : { reviewedAt: new Date() }),
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
		this.items = this.items.slice(1); // seamless: never blank; a due card is spliced in by spliceFetch
		this.held = this.items.length === 0 ? item : null;
		this.epoch++;
		this.newPress();
		outbox.tail = outbox.tail.then(() => Outbox_send(entry));
		if (!this.isDrill) {
			if (this.dueFirstPassed() && this.items.length > 0) void this.spliceFetch();
			else void this.prefetch();
		}
		return done;
	}

	afterSend(): Promise<void> {
		return this.refresh();
	}

	/** The answer was recorded: its history entry now carries the server's review id. */
	sent(entry: PendingAnswer, reviewId: string, card?: Card): void {
		this.history = this.history.map((h) => (h.reviewId === entry.answerId ? { ...h, reviewId } : h));
		// C1: an answer that left the card in (re)learning brings T forward to its due instant.
		if (card?.dueAt && (card.state === 'learning' || card.state === 'relearning')) {
			const dueAt = card.dueAt.getTime();
			this.learningDues.push({ recordedAt: Date.now(), dueAt });
			this.dueFirstAt = this.dueFirstAt === null ? dueAt : Math.min(this.dueFirstAt, dueAt);
			this.scheduleDueFetch();
		}
	}

	/**
	 * A send failed: put these cards (the failed one first, then the taps withdrawn behind it) back at the
	 * front in order. The failed card comes back on its back so the retry is one tap.
	 */
	restore(withdrawn: PendingAnswer[], failed: PendingAnswer | null, clockSkew = false): void {
		if (clockSkew) this.clockSkewed = true;
		const ids = new Set(withdrawn.map((e) => e.answerId));
		this.history = this.history.filter((h) => !ids.has(h.reviewId));
		this.items = [...withdrawn.map((e) => e.item), ...this.items];
		this.epoch++;
		if (failed) {
			this.showCard(failed.item.card.id, failed.answerId, true, failed.shownAt);
			this.failedAnswer = { cardId: failed.item.card.id, button: failed.button, serverTime: clockSkew };
			this.failedCardId = failed.item.card.id;
			this.notice = clockSkew ? 'clockSkew' : 'answerFailed';
		} else this.syncCard();
	}

	private clearNotice(): void {
		this.notice = null;
		this.failedAnswer = null;
		this.failedCardId = null;
	}

	/** The server refused this answer for good (card deleted or suspended elsewhere): it is gone from the session. */
	dropped(entry: PendingAnswer): void {
		this.history = this.history.filter((h) => h.reviewId !== entry.answerId);
		this.items = this.items.filter((i) => i.card.id !== entry.item.card.id);
		if (this.held?.card.id === entry.item.card.id) this.held = null;
		this.epoch++;
		this.syncCard();
		pushToast(tr('review.answerDropped'));
	}

	/** Send the failed answer again: same card, same button, same answer id (the server deduplicates). */
	retryAnswer(): Promise<void> {
		const f = this.failedAnswer;
		const cur = this.current;
		if (!f || !cur || cur.card.id !== f.cardId) {
			this.clearNotice();
			return Promise.resolve();
		}
		this.flipped = true;
		this.clearNotice();
		return this.send(cur, f.button, f.serverTime);
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
			const reviewId = last.reviewId;
			await timed(ANSWER_TIMEOUT_MS, (init) => reviewApi.undoReview({ reviewUndoRequest: { reviewId } }, init));
			this.clearNotice();
			this.history = this.history.slice(0, -1);
			this.items = [last.item, ...this.items];
			this.epoch++;
			this.newPress();
			await this.refresh();
		} catch {
			this.notice = 'undoFailed'; // beside the client's toast; the view offers Undo again
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
