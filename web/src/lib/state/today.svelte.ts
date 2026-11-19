// Today (home) hero (ADR-037): the phase and every number come from GET /review/queue `loop`, the same numbers
// the review screen, the phone and the widget show. Keep going is the persisted per-client flag (rule C2), so
// Home and the review screen send the same `keep_going`. Not from /stats/summary, which is slow and may 504.
import { quietApiConfig } from '../api/client';
import { ReviewApi } from '../api/generated';
import { fetchWithKeepGoing, startKeepGoing } from '../review/keepGoingDay';
import { loopView, type LoopView } from '../review/loop';

export type { LoopPhase, LoopView, ReviewLoop } from '../review/loop';
export { countdown, minutesUntil, loopParts, keepGoingAvailable } from '../review/loop';

const api = new ReviewApi(quietApiConfig);

/** C4 timing: a waiting refetch happens this long after the card falls due, and never sooner than the gap. */
const WAKE_SLACK_MS = 1000;
const WAKE_MIN_MS = 3000;

export class TodayQueue {
	view = $state<LoopView | null>(null);
	loaded = $state(false);
	failed = $state(false);
	#abort = new AbortController();
	#timer: ReturnType<typeof setTimeout> | null = null;
	#lastWake = 0;

	readonly loop = $derived(this.view?.loop ?? null);
	readonly phase = $derived(this.view?.phase ?? null);

	/** Fetch the loop (reads no state). Resolves when settled, failed or not. */
	async load(): Promise<void> {
		this.failed = false;
		try {
			// limit 1: only `loop` is read here.
			const q = await fetchWithKeepGoing((keepGoing) => api.getReviewQueue({ limit: 1, keepGoing }, { signal: this.#abort.signal }));
			this.view = loopView(q);
			this.#schedule();
		} catch {
			if (!this.#abort.signal.aborted) this.failed = true; // shown inline with a retry, no global error
		} finally {
			this.loaded = true;
		}
	}

	/** Keep going pressed on Home: persisted for the shown study day (C2); the caller then opens the review. */
	keepGoing(): void {
		if (this.view) startKeepGoing(this.view.studyDay);
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
