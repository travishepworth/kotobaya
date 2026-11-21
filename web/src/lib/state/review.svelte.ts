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
import {
	presentLoop,
	previewIntervals,
	type CardState,
	type IntervalPreview,
	type LoopPresentation,
	type PendingLoopAnswer,
	type QueueItemKind
} from '../core/index';
import { haptic } from '../api/shell/nav';
import { pushToast } from '../toasts.svelte';
import { cardsApi, noteTypesApi, notesApi, presetsApi, reviewApi, settingsApi, speechApi, wordsApi } from '../review/api';
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
	keepGoingNewTarget,
	startKeepGoing,
	touchKeepGoing
} from '../review/keepGoingDay';
import { loopParts, loopView, type LoopView } from '../review/loop';
import {
	defaultOutboxStore,
	nextAddedAt,
	resetAddedAtForTests,
	seedAddedAt,
	type OutboxRecord,
	type OutboxStore
} from '../review/outboxStore';
import { renderPreview, type Rendered } from './preview.svelte';

export type { KanjiPart, Note, NoteSummary, NoteType, QueueItem } from '../api/generated';
export type { GradeButton, ReviewPhase } from '../review/model';
export type { LoopPhase, LoopView, ReviewLoop } from '../review/loop';
export { countdown, minutesUntil, loopParts, keepGoingAvailable } from '../review/loop';
export type { Rendered } from './preview.svelte';

const DUE_SLACK_MS = 250;
/** LF-W2 / design 7(c): ask for this many cards, and refill (merge) when `PREFETCH_LOW` or fewer remain locally. */
const QUEUE_LIMIT = 30;
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
 * `settling` (C7): no card held but one is expected (a response that raced an answer): fetch again after this,
 * doubling up to the cap. Never an end screen while an answer is pending.
 */
const PENDING_BASE_MS = 2000;
/** RC-WEB: when this few cards are left locally, fetch more while answers are still being saved (no drain). */
const PREFETCH_LOW = 10;
const PENDING_MAX_MS = 30_000;
/** ... and stops after this many refetches in a row (the view then offers a quiet Check again): no endless polling. */
const PENDING_MAX_TRIES = 5;
/** C5: `to_materialize` > 0 with nothing served: fetch again every 5 s while the screen is open. */
const MATERIALIZE_POLL_MS = 5000;

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


/** C8: the first retry of a failed send waits this long, doubling up to RETRY_MAX_MS. */
const RETRY_BASE_MS = 1000;
const RETRY_MAX_MS = 30_000;

/**
 * C8: what a failed send means.
 * - 'clock': 422 on `/reviewed_at` (this device's clock is off, e.g. `in_future`): resent at once without
 *   `reviewed_at` (the server's now); from then on no answer of this page life carries it.
 * - 'permanent': refused for good (card deleted 410, suspended 409, unknown 404, other 422) and every other 4xx
 *   not listed below (400, 413, ...: this record will never be accepted, and retrying it would hold every later
 *   answer behind it for ever): dropped with a toast.
 * - 'auth': 401, and 403 (the session, not the record, is refused: every entry would be): sending stops and the
 *   outbox is kept until the user is back (page started, online, visible).
 * - 'retry': timeouts, network errors, 408, 429, 5xx and anything else: the entry stays and is retried.
 */
async function classify(e: unknown): Promise<'clock' | 'permanent' | 'auth' | 'retry'> {
	if (!(e instanceof ResponseError)) return 'retry';
	const status = e.response.status;
	if (status === 401 || status === 403) return 'auth';
	if (status === 422) {
		const p = await readProblem(e.response);
		if (p?.errors?.some((f) => f.pointer === '/reviewed_at')) return 'clock';
	}
	return status >= 400 && status < 500 && status !== 408 && status !== 429 ? 'permanent' : 'retry';
}

/**
 * One outbox entry: a grade tap (or an undo of an answer the server may have) in tap order. The record is what
 * the store keeps; the rest lives only as long as the page.
 */
interface OutboxEntry {
	rec: OutboxRecord;
	/** The page that tapped it; null for an entry read back from the store after a reload. */
	page: ReviewPage | null;
	state: 'queued' | 'sending' | 'done';
	/** The store write of the tap; the first send waits for it. */
	persisted: Promise<void>;
	done: Promise<void>;
	settle: () => void;
}

function newEntry(rec: OutboxRecord, page: ReviewPage | null): OutboxEntry {
	let settle!: () => void;
	const done = new Promise<void>((r) => (settle = r));
	return { rec, page, state: 'queued', persisted: Promise.resolve(), done, settle };
}

/** One tap as the presenter needs it. `acked`: its request has been answered (or refused) and left the outbox. */
interface LoggedAnswer extends PendingLoopAnswer {
	seq: number;
	answerId: string;
	acked: boolean;
}

/** The presenter log keeps at most this many entries (when it can drop some, see `trimAnswerLog`). */
export const ANSWER_LOG_MAX = 200;

/**
 * Trim the presenter log to `max` entries, oldest first, dropping only entries that are acked and that every live
 * page's applied response already reflects (`seq <= floor`). Unacked entries are never dropped, so the log may
 * stay longer than `max`.
 */
export function trimAnswerLog<T extends { seq: number; acked: boolean }>(log: T[], floor: number, max = ANSWER_LOG_MAX): T[] {
	let extra = log.length - max;
	if (extra <= 0) return log;
	return log.filter((l) => !(extra > 0 && l.acked && l.seq <= floor && extra-- > 0));
}

/** The header part a card is counted in (core `QueueItemKind`), from what the wire item says. */
function itemKind(item: QueueItem): QueueItemKind {
	if (item.isNew ?? item.card.state === 'new') return 'new';
	return item.card.state === 'learning' || item.card.state === 'relearning' ? 'learning' : 'review';
}

/**
 * C8 persisted outbox: answers tapped but not yet recorded. A module singleton, so they survive leaving /review
 * and coming back (a new page must not list a card whose answer is still on its way). Every entry is in the store
 * (`outboxStore`) from the tap until a `2xx` or a final refusal, so a reload sends it then. One request at a time,
 * in tap order; a retryable failure keeps the entry at the head and retries with backoff while later taps wait
 * behind it. Nothing is ever put back in front of the user.
 */
class Outbox {
	pending: OutboxEntry[] = [];
	count = $state(0);
	/** Count of answers ever added, and the card ids of the latest ones (to drop them from stale fetches). */
	seq = 0;
	private added: { seq: number; id: string }[] = [];
	/** Reactive record of the latest answers for the presenter (C7 `pending`): see `LoggedAnswer`. */
	log = $state<LoggedAnswer[]>([]);
	/** Applied seq per live page: an entry is only dropped once acked and no page's response may lack it. */
	readonly applied = new Map<object, number>();
	/** C8: a clock 422 was seen; no answer of this page life is sent with `reviewed_at` any more. */
	clockSkewed = false;
	private store: OutboxStore | null = null;
	/** Reading back what an earlier page life left; once per page life. */
	private loading: Promise<void> | null = null;
	/** Bumped by a reset (logout, tests): work begun under an older generation is ignored. */
	private gen = 0;
	private running = false;
	/** 401: no sending until `kick`. */
	private paused = false;
	private retryTimer: ReturnType<typeof setTimeout> | null = null;
	private backoff = RETRY_BASE_MS;
	private idleWaiters: (() => void)[] = [];
	private listening = false;
	private warning = false;
	/** Ids whose store write failed: only memory holds them. */
	private unsaved = new Set<string>();

	private storeRef(): OutboxStore {
		return (this.store ??= defaultOutboxStore());
	}

	setStore(s: OutboxStore | null): void {
		this.store = s;
	}

	/** Card ids answered after fetch number `seq` began, plus those whose answer is still in the outbox. */
	answeredSince(seq: number): Set<string> {
		const ids = this.answerCards();
		for (const a of this.added) if (a.seq > seq) ids.add(a.id);
		return ids;
	}

	/** Card ids with an answer in the outbox (a fetch must not serve them again: the server has not seen it yet). */
	answerCards(): Set<string> {
		return new Set(this.pending.filter((e) => e.rec.op === 'answer').map((e) => e.rec.cardId));
	}

	find(id: string): OutboxEntry | undefined {
		return this.pending.find((e) => e.rec.id === id);
	}

	/** Answers left by an earlier page life are read back (in `addedAt` order, ahead of any tap since). */
	load(): Promise<void> {
		if (!this.loading) {
			const gen = this.gen;
			this.listen();
			this.loading = this.storeRef()
				.all()
				.then(
					(recs) => {
						if (gen !== this.gen) return;
						// The clock may have gone back since these were stored: later taps must still sort after them.
						seedAddedAt(Math.max(...recs.map((r) => r.addedAt)));
						const known = new Set(this.pending.map((e) => e.rec.id));
						const back = recs.filter((r) => !known.has(r.id)).map((r) => newEntry(r, null));
						// Taps made before this read finished go after what it found, in the store too.
						const floor = Math.max(...back.map((e) => e.rec.addedAt));
						for (const e of this.pending)
							if (e.rec.addedAt <= floor) {
								e.rec.addedAt = nextAddedAt();
								const write = e.persisted.then(() => this.storeRef().put(e.rec));
								e.persisted = write.catch(() => {});
							}
						for (const e of back) this.logAnswer(e);
						this.pending = [...back, ...this.pending];
						this.sync();
					},
					() => {
						/* nothing readable: nothing to replay */
					}
				);
		}
		return this.loading;
	}

	/** A tap: stored first, then sent behind everything before it. */
	add(e: OutboxEntry): void {
		this.listen();
		e.persisted = this.storeRef()
			.put(e.rec)
			.catch(() => {
				// The in-memory entry is still sent, but a tab close would lose it: warn (beforeunload) until it is.
				this.unsaved.add(e.rec.id);
			});
		this.pending.push(e);
		this.logAnswer(e);
		this.sync();
		if (!this.warning && typeof window !== 'undefined') {
			this.warning = true;
			window.addEventListener('beforeunload', (ev) => {
				// Only when a tab close would lose them (no IndexedDB).
				if (this.pending.length > 0 && (!this.storeRef().persistent || this.unsaved.size > 0)) ev.preventDefault();
			});
		}
		void this.pump();
	}

	/** Undo of an answer that may have reached the server: an undo entry, sent in order behind it. */
	addUndo(reviewId: string, cardId: string, page: ReviewPage): void {
		const rec: OutboxRecord = { id: `undo:${reviewId}`, op: 'undo', cardId, addedAt: nextAddedAt(), attempts: 0, req: { reviewId } };
		const existing = this.find(rec.id);
		if (existing) return; // already on its way
		this.add(newEntry(rec, page));
	}

	/** Undo of an answer never sent: it leaves the outbox and the store, no request at all. */
	withdraw(e: OutboxEntry): void {
		this.finish(e);
		this.forget([e.rec.id]);
		e.settle();
	}

	private logAnswer(e: OutboxEntry): void {
		const r = e.rec;
		if (r.op !== 'answer') return;
		this.seq++;
		this.log.push({ seq: this.seq, answerId: r.id, kind: r.kind, rating: r.req.rating, resultingState: r.resultingState, dueAt: r.dueAt, acked: false });
		this.log = trimAnswerLog(this.log, Math.min(...this.applied.values(), this.seq));
		this.added.push({ seq: this.seq, id: r.cardId });
		if (this.added.length > ANSWER_LOG_MAX) this.added.splice(0, this.added.length - ANSWER_LOG_MAX);
	}

	/** The answer is no longer an answer (undone, withdrawn, dropped): the presenter forgets it. */
	forget(answerIds: string[]): void {
		this.log = this.log.filter((l) => !answerIds.includes(l.answerId));
	}

	/** The server's own result for a recorded answer replaces the scheduler preview. */
	setResult(answerId: string, state: CardState | null, dueAt: number | null): void {
		const l = this.log.find((x) => x.answerId === answerId);
		if (l) {
			l.resultingState = state;
			l.dueAt = dueAt;
		}
	}

	/** Resolves once nothing is left to send (at once when empty). */
	idle(): Promise<void> {
		return this.pending.length === 0 ? Promise.resolve() : new Promise((r) => this.idleWaiters.push(r));
	}

	/** Send now: the browser is online / visible again, the page was (re)started, or the user asked. */
	kick(): void {
		this.paused = false;
		if (this.retryTimer !== null) {
			clearTimeout(this.retryTimer);
			this.retryTimer = null;
		}
		void this.pump();
	}

	/** Logout: every unsent answer is forgotten, here and in the store. */
	async clear(): Promise<void> {
		const s = this.storeRef();
		this.reset();
		await s.clear();
	}

	/** Forget the page life's state (the store is kept): a reload. */
	reset(): void {
		this.gen++;
		for (const e of this.pending) e.settle();
		this.pending = [];
		this.unsaved.clear();
		this.sync();
		this.log = [];
		this.added = [];
		this.applied.clear();
		this.loading = null;
		this.running = false;
		this.paused = false;
		this.clockSkewed = false;
		this.backoff = RETRY_BASE_MS;
		if (this.retryTimer !== null) clearTimeout(this.retryTimer);
		this.retryTimer = null;
		this.drained();
	}

	private listen(): void {
		if (this.listening || typeof window === 'undefined') return;
		this.listening = true;
		window.addEventListener('online', () => this.kick());
		document.addEventListener('visibilitychange', () => {
			if (document.visibilityState === 'visible' && this.pending.length > 0) this.kick();
		});
	}

	/** Send the head of the outbox, one at a time, until it is empty, a send must wait (backoff) or 401. */
	private async pump(): Promise<void> {
		if (this.running || this.paused || this.retryTimer !== null) return;
		this.running = true;
		const gen = this.gen;
		try {
			await this.load();
			while (gen === this.gen && !this.paused) {
				const e = this.pending[0];
				if (!e) break;
				const r = await this.sendOne(e, gen);
				if (gen !== this.gen) return;
				if (r === 'auth') this.paused = true;
				else if (r === 'retry') {
					this.retryTimer = setTimeout(() => {
						this.retryTimer = null;
						void this.pump();
					}, this.backoff);
					this.backoff = Math.min(this.backoff * 2, RETRY_MAX_MS);
					break;
				}
			}
		} finally {
			if (gen === this.gen) {
				this.running = false;
				if (this.pending.length === 0) this.drained();
			}
		}
	}

	private async sendOne(e: OutboxEntry, gen: number): Promise<'next' | 'retry' | 'auth'> {
		await e.persisted;
		if (gen !== this.gen || this.pending[0] !== e) return 'next'; // withdrawn meanwhile
		const r = e.rec;
		if (r.op === 'answer' && this.clockSkewed && r.req.reviewedAt) this.stripReviewedAt(e);
		e.state = 'sending';
		r.attempts++;
		void this.storeRef()
			.put(r)
			.catch(() => {});
		try {
			if (r.op === 'answer') {
				const res = await timed(ANSWER_TIMEOUT_MS, (init) => reviewApi.answerReview({ reviewAnswerRequest: r.req }, init));
				if (gen !== this.gen) return 'next';
				this.finish(e);
				this.backoff = RETRY_BASE_MS;
				e.page?.sent(e, res.review.id, res.card);
			} else {
				await timed(ANSWER_TIMEOUT_MS, (init) => reviewApi.undoReview({ reviewUndoRequest: r.req }, init));
				if (gen !== this.gen) return 'next';
				this.finish(e);
				this.backoff = RETRY_BASE_MS;
			}
			this.after(e);
			return 'next';
		} catch (err) {
			if (gen !== this.gen) return 'next';
			const kind = await classify(err);
			if (gen !== this.gen) return 'next';
			if (kind === 'clock' && r.op === 'answer' && r.req.reviewedAt) {
				// Not final: the same id again at once, with the server's clock; and so every later answer.
				this.clockSkewed = true;
				this.stripReviewedAt(e);
				e.state = 'queued';
				return 'next';
			}
			if (kind === 'auth') {
				e.state = 'queued';
				return 'auth';
			}
			if (kind === 'permanent' || kind === 'clock') {
				this.finish(e);
				this.forget([r.id]);
				if (r.op === 'answer') {
					// An undo queued behind it has nothing left to undo (it would 404 and say "undo failed").
					const undo = this.find(`undo:${r.id}`);
					if (undo) {
						this.finish(undo);
						undo.settle();
					}
					pushToast(tr('review.answerDropped'));
					e.page?.dropped(e);
				} else e.page?.undoRefused();
				this.after(e);
				return 'next';
			}
			e.state = 'queued';
			return 'retry';
		}
	}

	private stripReviewedAt(e: OutboxEntry): void {
		if (e.rec.op !== 'answer') return;
		const req = { ...e.rec.req };
		delete req.reviewedAt;
		e.rec.req = req;
		void this.storeRef()
			.put(e.rec)
			.catch(() => {});
	}

	/** The entry left the outbox (recorded, refused for good, or withdrawn): gone from memory and store. */
	private finish(e: OutboxEntry): void {
		e.state = 'done';
		this.pending = this.pending.filter((x) => x !== e);
		this.unsaved.delete(e.rec.id);
		for (const l of this.log) if (l.answerId === e.rec.id) l.acked = true;
		void this.storeRef()
			.delete(e.rec.id)
			.catch(() => {});
		this.sync();
		if (this.pending.length === 0) this.drained();
	}

	/** The tapping page refreshes (refill while more are queued, full fetch once empty); then `grade` resolves. */
	private after(e: OutboxEntry): void {
		const p = e.page ? e.page.afterSend() : Promise.resolve();
		void p.then(
			() => e.settle(),
			() => e.settle()
		);
	}

	private drained(): void {
		for (const w of this.idleWaiters.splice(0)) w();
	}

	private sync(): void {
		this.count = this.pending.length;
	}
}

const outbox = new Outbox();

/** Test hook: forget all pending state, and the store (a fresh in-memory one is used next). */
export function resetOutboxForTests(): void {
	outbox.reset();
	outbox.seq = 0;
	outbox.setStore(null);
}

/** Test hook: use this store from now on. */
export function setOutboxStoreForTests(s: OutboxStore): void {
	outbox.setStore(s);
}

/** Test hook: a page reload. Everything in memory is gone (an in-flight request is ignored); the store is kept. */
export function simulateReloadForTests(): void {
	outbox.reset();
	resetAddedAtForTests();
}

/**
 * Logout: unsent answers are dropped here and in the store (they belong to the user who logged out). A `401`
 * does not call this: the outbox then waits for the next login.
 */
export function clearOutbox(): Promise<void> {
	return outbox.clear();
}

/**
 * C8 "on page load the outbox is sent first": read back what an earlier page life stored and send it. Called at
 * app start (the layout), so a reload on Home does not wait for /review. Never throws.
 */
export async function startOutbox(): Promise<void> {
	await outbox.load();
	outbox.kick();
}

/**
 * Resolves when nothing is left to send, or after `maxMs` (a down network must not hold a screen back). Home reads
 * its counts after this, so cards with pending answers are not counted as due.
 */
export async function outboxSettled(maxMs: number): Promise<void> {
	await outbox.load();
	if (outbox.pending.length === 0) return;
	let timer: ReturnType<typeof setTimeout> | undefined;
	await Promise.race([outbox.idle(), new Promise<void>((r) => (timer = setTimeout(r, maxMs)))]);
	clearTimeout(timer);
}

/**
 * A review session for the /review view (ADR-048). The session outlives the component that shows it (it is kept
 * across a dictionary visit), but a `$derived` belongs to the effect it is created under: made during the
 * component's init, `current`/`display`/`phase` went inert when the component was destroyed, and the resumed
 * session stayed on the old card. Its own root keeps them live; `release` ends it.
 */
export function createReviewPage(): ReviewPage {
	let page: ReviewPage | undefined;
	const destroyRoot = $effect.root(() => {
		page = new ReviewPage();
	});
	if (!page) throw new Error('review page not created');
	page.destroyRoot = destroyRoot;
	return page;
}

export class ReviewPage {
	/** Set by `createReviewPage`: destroys the reactive root that owns this session's deriveds. */
	destroyRoot: (() => void) | null = null;
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
	/** Refetches in a row while settling (backoff). */
	private pendingTries = 0;
	/** Settling gave up after PENDING_MAX_TRIES; the view offers a quiet Check again (`reload`), which resumes it. */
	pendingStopped = $state(false);
	/** `outbox.seq` when the request of the last applied full fetch was sent: later answers are C7 `pending`. */
	private appliedSeq = $state(0);
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
	 * An action failed and the user should know. Since C8 (LF-W1) a failed answer is never put back (it stays in
	 * the outbox and is retried), so only `undoFailed` is set: an undo the server refused for good.
	 * `answerFailed` / `clockSkew` remain in the type for the view and are no longer set.
	 */
	notice = $state<'answerFailed' | 'clockSkew' | 'undoFailed' | null>(null);
	/** The card the last undo brought back: a full fetch keeps it in front even if the server does not list it. */
	private undoneCardId: string | null = null;
	noteTypes = $state<Map<string, NoteType>>(new Map());
	presets = $state<Map<string, Preset>>(new Map());
	fiveButtons = $state(false);
	furigana = $state(true);
	/** Setting `review.autoplay_audio` (ADR-047 A6; absent = on). */
	autoplay = $state(true);
	/** Bumped each time a card is shown afresh (new card, undo, restore): one showing = one autoplay (A2). */
	showSeq = $state(0);
	/** Which showing and side the autoplay already happened for (kept with the session, so a return does not replay). */
	autoplayedKey: string | null = null;
	/** Hashes resolved through `resolveSpeech` for cards whose `word_audio.blob_hash` was null (A4), by card id. */
	private resolvedAudio = $state<Map<string, string>>(new Map());
	/** Identifies the showing the speech lookup is for; any change ends the lookup. */
	private audioToken = 0;
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
	/** C8: grading is never locked (a failed answer stays in the outbox). Kept for the view. */
	readonly gradeLocked = false;
	/**
	 * C7 `pending`: every answer given since the applied response's request was sent (its effect that response does
	 * not show) and any answer not acknowledged yet, with the scheduler preview (or the server's result) for state/due.
	 */
	readonly pendingAnswers = $derived(
		outbox.log.filter((l) => l.seq > this.appliedSeq || !l.acked).map((l) => ({
			kind: l.kind,
			rating: l.rating,
			resultingState: l.resultingState,
			dueAt: l.dueAt
		}))
	);
	/** The core `LoopPresenter` output (C7): the screen, the header parts and the bar remainder. Null in a drill. */
	readonly presentation = $derived.by((): LoopPresentation | null => {
		const v = this.view;
		if (this.isDrill || !v) return null;
		const head = this.current;
		try {
			return presentLoop({
				state: { phase: v.phase, untilStopParts: loopParts(v.loop) },
				pending: this.pendingAnswers,
				hasLocalCard: !!head,
				localHeadKind: head ? itemKind(head) : null,
				dayEndsAt: this.dayEndsAt?.getTime() ?? 0
			});
		} catch {
			// Invalid host input: never hide a held card and pending answers behind a full-page error.
			return outbox.count > 0 ? { screen: 'settling', parts: { learning: 0, later: 0, review: 0, new: 0 }, untilStop: 0 } : null;
		}
	});
	/** Answers are on their way to the server (the held card is saving). */
	get saving(): boolean {
		return outbox.count > 0;
	}
	/** No card held but one is expected: the header stays, the grade buttons go, the queue is fetched again. */
	readonly settling = $derived(this.presentation?.screen === 'settling');
	/** What the stage shows: the current card, or the held one while answers settle. */
	readonly display = $derived<QueueItem | undefined>(this.current ?? (this.settling ? (this.held ?? undefined) : undefined));
	/** The numbers on screen: `loop` of the last applied fetch (null in a drill and before the first fetch). */
	readonly loop = $derived(this.isDrill ? null : (this.view?.loop ?? null));
	/** What the screen shows: the presenter's `screen` (C7), never derived from `loop.phase` here. */
	readonly phase = $derived<ReviewPhase>(
		reviewPhase({
			loaded: this.loaded,
			loadFailed: this.loadFailed,
			refreshFailed: this.refreshFailed,
			hasCurrent: !!this.current,
			isDrill: this.isDrill,
			drillUnavailable: this.drillUnavailable,
			screen: this.presentation?.screen ?? null
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
			wordAudio: item.wordAudio?.blobHash ?? this.resolvedAudio.get(item.card.id) ?? null,
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

	/**
	 * If the card on screen is not the one the per-card state belongs to, reset that state. The review view calls it
	 * from an effect (another card on screen after a refetch or restore: fresh state for it), so a page kept across a
	 * route change (ADR-048) is re-bound by the next view.
	 */
	syncCard(): void {
		const id = this.current?.card.id ?? '';
		if (this.current && this.held) this.held = null; // a card is on screen: nothing to hold any more
		if (id !== this.lastCardId) this.showCard(id, uuidv7(), false, Date.now());
	}

	private showCard(cardId: string, answerId: string, flipped: boolean, shownAt: number): void {
		this.lastCardId = cardId;
		this.showSeq++;
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
		this.autoplay = st.items.find((s) => s.key === 'review.autoplay_audio')?.value !== false;
	}

	/**
	 * Fetch the scheduled queue. With `guard`, the result is dropped when the local queue changed meanwhile
	 * (a tap or undo): applying it would bring back a card that was just answered. Returns whether it was applied.
	 *
	 * The server turns deck entries into new cards inside this fetch (C-27); the client never creates them.
	 * `keep_going` is the persisted per-client flag (C2, dropped on a new study day). A `reviewing` response with
	 * no item yet while the server reports entries still to materialize is fetched again (C5, at most 3 times).
	 */
	private async fetchQueue(guard = false, append = false, polled = false): Promise<boolean> {
		if (this.isDrill || this.disposed) return false;
		const gen = this.gen;
		// C8: answers stored by an earlier page life are in the outbox (and filtered below) before anything applies.
		await outbox.load();
		if (gen !== this.gen || this.disposed) return false;
		const epoch = this.epoch;
			const outboxSeq = outbox.seq;
		const startedAt = Date.now();
		const pendingAtStart = outbox.answerCards();
		let sent = false;
		const get = (keepGoing: boolean, newTarget: number | null): Promise<ReviewQueue> => {
			sent = keepGoing;
			return this.getQueue(keepGoing, newTarget);
		};
		let q = await fetchWithKeepGoing(get);
		// A timer-driven fetch (`polled`) is one request: C5's three quick retries are spent by the first fetch.
		for (let i = 0; i < MATERIALIZE_RETRIES && !append && !polled && materializing(q); i++) {
			if (gen !== this.gen) return false;
			q = await get(sent, keepGoingNewTarget());
		}
		// A newer start (retry) or leaving the screen supersedes this fetch.
		if (gen !== this.gen || this.disposed) return false;
		if (guard && (epoch !== this.epoch || outbox.pending.length > 0)) return false;
		// Cards whose answer is still on its way are not owed again. The card on screen stays where it is
		// (with its flip state); only what comes after it is replaced.
		// Also those answered while this request was in flight: the server may have built the queue before it
		// applied them, and an unguarded fetch (start, reload) is not dropped by the epoch.
		const answered = outbox.answeredSince(outboxSeq);
		const fresh = q.items.filter((i) => !answered.has(i.card.id) && !pendingAtStart.has(i.card.id));
		const cur = this.current;
		if (append) {
			// LF-W2: a prefetch is merged, never discarded: not because a full fetch applied meanwhile (its cards are
			// kept, answered ones are filtered above) and not because a learning card came due (a card the server lists
			// first, or listed now but not before, is spliced in behind the card on screen, ahead of the rest).
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
		// A card brought back by undo stays in front (the server may still count its answer, or not list it yet).
		const keepCur = cur && (fresh.some((i) => i.card.id === cur.card.id) || this.undoneCardId === cur.card.id);
		this.items =
			keepCur
				? [cur, ...fresh.filter((i) => i.card.id !== cur.card.id)]
				: fresh;
		this.applied++;
		this.appliedSeq = outboxSeq;
		outbox.applied.set(this, outboxSeq);
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

	private getQueue(keepGoing: boolean, newTarget: number | null) {
		if (this.disposed) return Promise.reject(new Error('review page disposed'));
		return timed(
			QUEUE_TIMEOUT_MS,
			// C2 (C-31): the batch's target goes with every fetch of the visit (never an absent-flag one).
			(init) =>
				reviewApi.getReviewQueue(
					{ limit: QUEUE_LIMIT, keepGoing, ...(keepGoing && newTarget !== null ? { keepGoingNewTarget: newTarget } : {}) },
					init
				),
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
	 * `settling` (nothing held, one expected): fetch again with backoff; with entries still to materialize (C5)
	 * every 5 s.
	 */
	private scheduleWake(): void {
		this.clearWake();
		if (this.isDrill || this.disposed) return;
		const due = this.wakeTarget();
		let at: number;
		if (due) {
			this.pendingTries = 0;
			at = due.getTime() + WAKE_SLACK_MS;
		} else if (!this.current && this.settling) {
			if (this.toMaterialize > 0) {
				at = Date.now() + MATERIALIZE_POLL_MS; // C5: every 5 s while the screen is open
			} else if (this.pendingTries >= PENDING_MAX_TRIES) {
				this.pendingStopped = true; // the view offers a quiet Check again
				return;
			} else {
				at = Date.now() + Math.min(PENDING_MAX_MS, PENDING_BASE_MS * 2 ** this.pendingTries);
				this.pendingTries++;
			}
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
		await this.refreshWhenIdle(true);
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

	private keptGoing: Date | null = null;
	private keptTarget: number | null = null;

	/**
	 * Leaving the screen for a page it can come back to (ADR-048): timers and fetches stop, the session state stays.
	 * `resume` picks it up again. A page that is never resumed must be `dispose`d.
	 */
	pauseSession(): void {
		this.keptGoing = this.keepGoing && this.view ? this.view.studyDay : null;
		this.keptTarget = keepGoingNewTarget();
		this.dispose();
	}

	/** The view is back after `suspend`: same card, side and queue; timers run again, nothing is re-fetched. */
	resume(): void {
		if (!this.disposed) return;
		this.disposed = false;
		this.abort = new AbortController();
		outbox.applied.set(this, this.appliedSeq);
		if (this.keptGoing) startKeepGoing(this.keptGoing, this.keptTarget);
		this.keptGoing = null;
		this.keptTarget = null;
		if (!this.isDrill) {
			this.scheduleDueFetch();
			this.scheduleWake();
		}
	}

	/** Leaving the screen: no timer or queue fetch outlives it (answers in the outbox still go out). */
	dispose(): void {
		this.disposed = true;
		outbox.applied.delete(this);
		this.gen++;
		this.clearWake();
		if (this.dueTimer !== null) clearTimeout(this.dueTimer);
		this.dueTimer = null;
		clearKeepGoing(); // ADR-039: keep going lasts one review visit
		this.abort.abort();
	}

	/** The session is dropped for good (never resumed): dispose it and end its reactive root. */
	release(): void {
		this.dispose();
		const destroy = this.destroyRoot;
		this.destroyRoot = null;
		destroy?.();
	}

	/**
	 * Refresh the queue and counts once no answer is in flight. A refresh that was dropped as stale while nothing
	 * is left on screen and nothing is in flight is tried again (in any mode): no later answer would trigger
	 * another one, and the screen would show an end state while the server has cards.
	 */
	private async refresh(polled = false): Promise<void> {
		if (this.isDrill || this.disposed) return;
		if (outbox.pending.length > 0) {
			// Answers are still being saved: refill instead, so the local queue does not drain meanwhile.
			void this.prefetch();
			return;
		}
		const gen = this.gen;
		try {
			for (let i = 0; i <= (polled ? 0 : MATERIALIZE_RETRIES); i++) {
				const applied = await this.fetchQueue(true, false, polled);
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
	private async refreshWhenIdle(polled = false): Promise<void> {
		await outbox.idle();
		await this.refresh(polled);
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
		// C8: answers stored by an earlier page life (or held back by a 401 before this login) go out first.
		outbox.kick();
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
	 * Keep going (C2, C-31): one goal-sized batch for this visit. `T = new.today + pendingNew + max(1, new.goal)` from
	 * the response on screen goes with the flag on every fetch of the visit; then fetch the batch. A server without
	 * `loop` (legacy) does not know the parameter: none is sent.
	 */
	async keepGoingOn(): Promise<void> {
		const v = this.view;
		if (!v || this.busy) return;
		const pendingNew = this.pendingAnswers.filter((a) => a.kind === 'new').length;
		startKeepGoing(v.studyDay, v.legacy ? null : v.loop._new.today + pendingNew + Math.max(1, v.loop._new.goal));
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
		// A manual Check again resumes settling with a fresh backoff.
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

	/** ADR-048: would back undo now? Any answer of this session can be stepped back over; no side effects. */
	canBackUndo(): boolean {
		return this.history.length > 0;
	}

	/**
	 * Back pressed (browser, mouse button or the shell's Back): the same undo as the button and `u`, which shows the
	 * card revealed. False when there is nothing to undo (back then leaves). A press while an undo is in flight is
	 * swallowed so a quick double back cannot leave the screen.
	 */
	backUndo(): boolean {
		if (this.history.length === 0) return false;
		if (!this.busy && !this.editing) void this.undo();
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

	/** The key of what is on screen right now: one autoplay per key (A2). */
	get showKey(): string {
		return `${this.showSeq}:${this.flipped}`;
	}

	/**
	 * ADR-047 A4: the card on screen has speech that is not generated yet: ask for it (urgent), ask again after
	 * `retry_after_ms` doubling, at most 5 calls; any failure ends it for this showing. On `ready` the hash is kept
	 * and the card renders again; an answer for a card the user has moved on from is dropped. Call once per showing.
	 */
	async resolveAudio(): Promise<void> {
		const item = this.current;
		const wa = item?.wordAudio;
		if (!item || !wa || wa.source !== 'tts' || wa.blobHash || !wa.text || this.resolvedAudio.has(item.card.id)) return;
		const token = ++this.audioToken;
		const seq = this.showSeq;
		const cardId = item.card.id;
		let wait = 0;
		for (let call = 0; call < 5; call++) {
			if (wait > 0) await new Promise<void>((resolve) => setTimeout(resolve, wait));
			if (token !== this.audioToken || this.disposed) return;
			let r;
			try {
				const res = await speechApi.resolveSpeech({
					speechResolveRequest: { items: [{ text: wa.text, voice: wa.voice ?? undefined }], urgent: true }
				});
				r = res.items[0];
			} catch {
				return;
			}
			if (token !== this.audioToken || this.disposed || seq !== this.showSeq || this.current?.card.id !== cardId) return;
			if (!r) return;
			if (r.status === 'ready' && r.blobHash) {
				this.resolvedAudio = new Map(this.resolvedAudio).set(cardId, r.blobHash);
				return;
			}
			if (r.status !== 'pending') return;
			wait = (r.retryAfterMs ?? 1000) * 2 ** call;
		}
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
	 * the queue and the next one shown at once, the answer is stored and sent in the background in tap order
	 * (C8). The returned promise settles when this answer has been recorded (or refused for good); with the
	 * network down that can be much later, so the view never awaits it.
	 */
	grade(b: GradeButton): Promise<void> {
		const item = this.current;
		if (!item || !this.flipped || this.busy || this.editing) return Promise.resolve();
		haptic(b.rating === 1 ? 'reject' : 'confirm');
		return this.send(item, b);
	}

	private send(item: QueueItem, b: GradeButton): Promise<void> {
		this.clearNotice();
		touchKeepGoing(); // ADR-039 idle amendment: an answer restarts the keep-going idle clock
		// C7: the scheduler preview stands in for the answer's result until its response arrives.
		const preview = this.previews?.find((p) => p.rating === b.rating);
		const rec: OutboxRecord = {
			id: this.answerId,
			op: 'answer',
			cardId: item.card.id,
			addedAt: nextAddedAt(),
			attempts: 0,
			kind: itemKind(item),
			resultingState: preview?.state ?? null,
			dueAt: preview ? Date.now() + preview.intervalMs : null,
			req: {
				id: this.answerId,
				cardId: item.card.id,
				...(item.grammarSentence ? { sentenceId: item.grammarSentence.sentenceId } : {}),
				rating: b.rating,
				...(b.button ? { button: b.button } : {}),
				mode: item.reviewType === 'drill' || item.reviewType === 'cram' ? item.reviewType : 'schedule',
				// C8: the time of the tap; after a clock error in this page life the server's now (omitted).
				...(outbox.clockSkewed ? {} : { reviewedAt: new Date() }),
				durationMs: Math.max(0, Date.now() - this.shownAt),
				utcOffsetMin: this.utcOffsetMin
			}
		};
		const entry = newEntry(rec, this);
		// Synchronously, before anything awaits: the card is gone and the next one is on its front.
		outbox.add(entry);
		this.history = [...this.history, { reviewId: rec.id, item }];
		this.items = this.items.slice(1); // seamless: never blank; a due card is spliced in by spliceFetch
		this.held = this.items.length === 0 ? item : null;
		if (this.undoneCardId === item.card.id) this.undoneCardId = null;
		this.epoch++;
		this.newPress();
		if (!this.isDrill) {
			if (this.dueFirstPassed() && this.items.length > 0) void this.spliceFetch();
			else void this.prefetch();
		}
		return entry.done;
	}

	afterSend(): Promise<void> {
		return this.refresh();
	}

	/** The answer was recorded: its history entry now carries the server's review id. */
	sent(entry: OutboxEntry, reviewId: string, card?: Card): void {
		this.history = this.history.map((h) => (h.reviewId === entry.rec.id ? { ...h, reviewId } : h));
		if (card) outbox.setResult(entry.rec.id, card.state, card.dueAt ? card.dueAt.getTime() : null);
		// C1: an answer that left the card in (re)learning brings T forward to its due instant.
		if (card?.dueAt && (card.state === 'learning' || card.state === 'relearning')) {
			const dueAt = card.dueAt.getTime();
			this.learningDues.push({ recordedAt: Date.now(), dueAt });
			this.dueFirstAt = this.dueFirstAt === null ? dueAt : Math.min(this.dueFirstAt, dueAt);
			this.scheduleDueFetch();
		}
	}

	private clearNotice(): void {
		this.notice = null;
	}

	/** The server refused this answer for good (card deleted or suspended elsewhere): it is gone from the session. */
	dropped(entry: OutboxEntry): void {
		const cardId = entry.rec.cardId;
		this.history = this.history.filter((h) => h.reviewId !== entry.rec.id);
		this.items = this.items.filter((i) => i.card.id !== cardId);
		if (this.held?.card.id === cardId) this.held = null;
		this.epoch++;
		this.syncCard();
	}

	/** The server refused an undo for good (unknown review): the answer stands. */
	undoRefused(): void {
		if (!this.disposed) this.notice = 'undoFailed';
	}

	/** C8: nothing to retry by hand any more; send whatever is waiting now. Kept for the view. */
	retryAnswer(): Promise<void> {
		this.clearNotice();
		outbox.kick();
		return Promise.resolve();
	}

	/**
	 * Undo the last answer of this session; the card comes back revealed at once (ADR-048), never waiting for the
	 * network. An answer never sent leaves the outbox and the store; one that may have reached the server (sent,
	 * in flight, or tried before) gets an undo queued behind it in the outbox, sent the same way.
	 */
	async undo(): Promise<void> {
		if (this.history.length === 0) {
			pushToast(tr('review.nothingToUndo'));
			return;
		}
		if (this.busy) return;
		const last = this.history[this.history.length - 1];
		const entry = outbox.find(last.reviewId);
		if (entry && entry.state === 'queued' && entry.rec.attempts === 0) outbox.withdraw(entry);
		else {
			outbox.forget([last.reviewId]);
			outbox.addUndo(last.reviewId, last.item.card.id, this);
		}
		this.clearNotice();
		this.history = this.history.slice(0, -1);
		this.items = [last.item, ...this.items];
		this.held = null;
		this.undoneCardId = last.item.card.id;
		this.epoch++;
		this.newPress();
		this.flipped = true; // ADR-048: the undone card comes back revealed, ready to re-grade
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
