// Today (home) hero (ADR-037): the phase and every number come from GET /review/queue `loop`, the same numbers
// the review screen, the phone and the widget show. Keep going is the persisted per-client flag (rule C2), so
// Home and the review screen send the same `keep_going`. Not from /stats/summary, which is slow and may 504.
import { quietApiConfig } from '../api/client';
import { ReviewApi } from '../api/generated';
import { fetchWithKeepGoing, startKeepGoing } from '../review/keepGoingDay';
import { loopView, type LoopView } from '../review/loop';
import { QUEUE_LIMIT, outboxSettled, pendingAnswerCards } from './review.svelte';
import { readSnapshot, writeQueueSnapshot } from './snapshot.svelte';
import { isUncached, revalidateOn } from './swr';

export type { LoopPhase, LoopView, ReviewLoop } from '../review/loop';
export { countdown, minutesUntil, loopParts, keepGoingAvailable, presentedParts } from '../review/loop';

const api = new ReviewApi(quietApiConfig);

/** C4 timing: a waiting refetch happens this long after the card falls due, and never sooner than the gap. */
const WAKE_SLACK_MS = 1000;
const WAKE_MIN_MS = 3000;
/** Home waits this long for answers still in the outbox to be recorded before it reads the counts. */
const OUTBOX_WAIT_MS = 3000;

export class TodayQueue {
	view = $state<LoopView | null>(null);
	loaded = $state(false);
	failed = $state(false);
	/** `503 shell_uncached` and nothing to show yet: "not downloaded yet", resolved by `cache.updated`. */
	uncached = $state(false);
	/**
	 * PERF-SNAP: `view` comes from the start-up snapshot and no fetch has replaced it yet (in flight or failed).
	 * The view shows it with a quiet "updating" note: not authoritative.
	 */
	stale = $state(false);
	#abort = new AbortController();
	#timer: ReturnType<typeof setTimeout> | null = null;
	#lastWake = 0;

	/** Drawn at once from the snapshot when there is one (no skeleton after the first-ever load). */
	constructor() {
		const snap = readSnapshot('queue');
		if (snap) {
			this.view = snap.view;
			this.loaded = true;
			this.stale = true;
		}
	}

	readonly loop = $derived(this.view?.loop ?? null);
	readonly phase = $derived(this.view?.phase ?? null);

	/** Refetch on `sync.changed` (debounced) and on `cache.updated` for the queue. Returns the unsubscribe. */
	watch(): () => void {
		return revalidateOn(['getReviewQueue'], () => void this.load());
	}

	/**
	 * Fetch the loop (reads no state). Resolves when settled, failed or not. The view on screen stays while it
	 * refetches or fails (stale-while-revalidate). Answers waiting in the outbox are sent first (briefly), so the
	 * counts do not include cards that were already answered.
	 */
	async load(): Promise<void> {
		try {
			await outboxSettled(OUTBOX_WAIT_MS);
			// The review screen's first page: `loop` is shown here, the items are kept for an instant Start review.
			const q = await fetchWithKeepGoing((keepGoing) => api.getReviewQueue({ limit: QUEUE_LIMIT, keepGoing }, { signal: this.#abort.signal }));
			this.view = loopView(q);
			this.failed = false;
			this.uncached = false;
			this.stale = false;
			const pending = pendingAnswerCards();
			writeQueueSnapshot({ view: this.view, dayEndsAt: q.dayEndsAt, items: q.items.filter((i) => !pending.has(i.card.id)) });
			this.#schedule();
		} catch (e) {
			if (this.#abort.signal.aborted) return;
			if (await isUncached(e)) this.uncached = this.view === null;
			else if (this.view === null) this.failed = true; // shown inline with a retry, no global error
		} finally {
			this.loaded = true;
		}
	}

	/** Keep going pressed on Home: persisted for the shown study day (C2); the caller then opens the review. */
	keepGoing(): void {
		const v = this.view;
		// C2 (C-31): one goal-sized batch per press, from the response on screen (Home holds no pending answers).
		if (v) startKeepGoing(v.studyDay, v.legacy ? null : v.loop._new.today + Math.max(1, v.loop._new.goal));
	}

	/** C6: one refetch when the next learning card falls due, in every phase (null: none). */
	#schedule(): void {
		this.#clear();
		const at = this.view?.loop.laterToday.nextDueAt ?? null;
		if (!at) return;
		const delay = Math.max(at.getTime() + WAKE_SLACK_MS - Date.now(), this.#lastWake + WAKE_MIN_MS - Date.now(), 0);
		this.#timer = setTimeout(() => {
			this.#timer = null;
			this.#lastWake = Date.now();
			void this.load();
		}, delay);
	}

	#clear(): void {
		if (this.#timer !== null) clearTimeout(this.#timer);
		this.#timer = null;
	}

	dispose(): void {
		this.#clear();
		this.#abort.abort();
	}
}
