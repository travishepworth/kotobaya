// Today (home) hero: the due counts and the Start button come from GET /review/queue `counts` with keep_going=false,
// the same numbers the review screen, the phone and the widget show (ADR-033, RC-WEB). Not from /stats/summary,
// which is slow and may 504.
import type { ReviewQueueCounts } from '../api/generated';
import { quietApiConfig } from '../api/client';
import { ReviewApi } from '../api/generated';

const api = new ReviewApi(quietApiConfig);

export class TodayQueue {
	counts = $state<ReviewQueueCounts | null>(null);
	moreAvailable = $state(false);
	nextLearningDueAt = $state<Date | null>(null);
	loaded = $state(false);
	failed = $state(false);
	#abort = new AbortController();

	readonly due = $derived(this.counts ? this.counts._new + this.counts.learning + this.counts.review : 0);

	/** Fetch the counts (reads no state). Resolves when settled, failed or not. */
	async load(): Promise<void> {
		this.failed = false;
		try {
			// limit 1: only counts and the end-state flags are read here.
			const q = await api.getReviewQueue({ limit: 1, keepGoing: false }, { signal: this.#abort.signal });
			this.counts = q.counts;
			this.moreAvailable = q.moreAvailable ?? false;
			this.nextLearningDueAt = q.nextLearningDueAt ?? null;
		} catch {
			if (!this.#abort.signal.aborted) this.failed = true; // shown inline with a retry, no global error
		} finally {
			this.loaded = true;
		}
	}

	dispose(): void {
		this.#abort.abort();
	}
}
