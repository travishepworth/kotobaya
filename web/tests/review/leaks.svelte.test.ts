// @vitest-environment jsdom
// RC-WEB slowdown: nothing a screen started outlives it. Before the fix, a queue fetch still in flight when /review
// was left re-armed the ADR-033 wake timer on the dead page (a zombie refetch loop per visit), and every visit to
// Today fired six stats requests (14-76 s each on the live server) that were never cancelled.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReviewQueue } from '../../src/lib/api/generated';

const api = vi.hoisted(() => ({
	answerReview: vi.fn(),
	undoReview: vi.fn(),
	getReviewQueue: vi.fn()
}));
vi.mock('../../src/lib/review/api', () => ({
	reviewApi: api,
	cardsApi: {},
	notesApi: {},
	wordsApi: {},
	presetsApi: { listPresets: async () => ({ items: [] }) },
	noteTypesApi: { listNoteTypes: async () => ({ items: [] }) },
	settingsApi: { listSettings: async () => ({ items: [] }) }
}));

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';
import { StatsPage } from '../../src/lib/state/stats.svelte';

const waitingQueue = (): ReviewQueue =>
	({
		items: [],
		counts: { _new: 0, learning: 3, review: 0 },
		studyDay: new Date(0),
		dayEndsAt: new Date(Date.now() + 10 * 3600_000),
		generatedAt: new Date(),
		nextLearningDueAt: new Date(Date.now() + 60_000)
	}) as ReviewQueue;

describe('review page leaves nothing running', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		vi.useFakeTimers();
	});
	afterEach(() => vi.useRealTimers());

	function mount(): { page: ReviewPage; destroy: () => void } {
		let page!: ReviewPage;
		const root = $effect.root(() => {
			page = new ReviewPage();
		});
		return {
			page,
			destroy: () => {
				page.dispose(); // what onDestroy does
				root();
			}
		};
	}

	it('left during the first (slow) fetch: no timer, no further fetches', async () => {
		api.getReviewQueue.mockImplementation(() => new Promise((r) => setTimeout(() => r(waitingQueue()), 6000)));
		const { page, destroy } = mount();
		page.start(null);
		await vi.advanceTimersByTimeAsync(1000);
		destroy();
		await vi.advanceTimersByTimeAsync(30 * 60_000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(1);
		expect(vi.getTimerCount()).toBe(0);
	});

	it('left while a wake refetch is in flight: the wake loop stops', async () => {
		let calls = 0;
		api.getReviewQueue.mockImplementation(() => {
			calls++;
			return calls === 1 ? Promise.resolve(waitingQueue()) : new Promise((r) => setTimeout(() => r(waitingQueue()), 6000));
		});
		const { page, destroy } = mount();
		page.start(null);
		await vi.advanceTimersByTimeAsync(62_000); // wake fired, its fetch is running
		expect(calls).toBe(2);
		destroy();
		await vi.advanceTimersByTimeAsync(30 * 60_000);
		expect(calls).toBe(2);
		expect(vi.getTimerCount()).toBe(0);
	});

	it('ten visits to a waiting screen leave no timers behind', async () => {
		api.getReviewQueue.mockImplementation(async () => waitingQueue());
		for (let i = 0; i < 10; i++) {
			const { page, destroy } = mount();
			page.start(null);
			await vi.advanceTimersByTimeAsync(100);
			destroy();
		}
		expect(vi.getTimerCount()).toBe(0);
	});
});

describe('stats requests', () => {
	let signals: AbortSignal[] = [];
	beforeEach(() => {
		signals = [];
		vi.stubGlobal(
			'fetch',
			vi.fn((_url: string, init?: RequestInit) => {
				const signal = init?.signal;
				if (signal) signals.push(signal);
				return new Promise((_r, reject) => signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
			})
		);
	});
	afterEach(() => vi.unstubAllGlobals());

	it('home loads only the panels it shows, and leaving aborts them', async () => {
		let page!: StatsPage;
		const root = $effect.root(() => {
			page = new StatsPage();
		});
		page.start(['summary', 'heatmap', 'retention']);
		await new Promise((r) => setTimeout(r, 0));
		expect(signals).toHaveLength(3);
		page.dispose();
		root();
		expect(signals.every((s) => s.aborted)).toBe(true);
		await new Promise((r) => setTimeout(r, 0));
		// An aborted load is not a failure (no "couldn't load" flashes on the way out).
		expect(page.summaryP.failed).toBe(false);
	});
});
