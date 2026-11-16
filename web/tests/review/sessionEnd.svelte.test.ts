// @vitest-environment jsdom
// RC-WEB: the review screen reaches the end screen only when the server says nothing is left. The user report
// (web 0.1.22): Start on 3 learning cards due in ~2 min, a ~6 s queue fetch, then the session-over screen.
import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { QueueItem, ReviewQueue } from '../../src/lib/api/generated';

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

import { QUEUE_TIMEOUT_MS, ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';
import { reviewPhase, type PhaseInput } from '../../src/lib/review/model';

const card = (id: string): QueueItem => ({ card: { id, presetId: 'p' }, reviewType: 'schedule' }) as unknown as QueueItem;
const good = { rating: 3, label: 'good' } as never;
const counts = (learning: number, review = 0, n = 0) => ({ _new: n, learning, review });

function queue(over: Partial<ReviewQueue>): ReviewQueue {
	return {
		items: [],
		counts: counts(0),
		studyDay: new Date(0),
		dayEndsAt: new Date(Date.now() + 10 * 3600_000),
		generatedAt: new Date(),
		moreAvailable: false,
		toMaterialize: 0,
		...over
	} as ReviewQueue;
}

/** Resolve after `ms` of (fake) time, like the slow live server. */
const slow = <T>(ms: number, v: T) => new Promise<T>((r) => setTimeout(() => r(v), ms));

function mount(): { page: ReviewPage; dispose: () => void } {
	let page!: ReviewPage;
	const root = $effect.root(() => {
		page = new ReviewPage();
	});
	return {
		page,
		dispose: () => {
			page.dispose();
			root();
		}
	};
}

const tick = async (ms = 0) => {
	await vi.advanceTimersByTimeAsync(ms);
	flushSync();
};

describe('review session end (RC-WEB)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it('user report: slow fetch of 3 learning cards due in 2 min shows loading, then waiting, then the card', async () => {
		const due = new Date(Date.now() + 120_000);
		api.getReviewQueue
			.mockImplementationOnce(() =>
				slow(6000, queue({ counts: counts(3), moreAvailable: true, nextLearningDueAt: due }))
			)
			.mockImplementation(async () =>
				Date.now() >= due.getTime()
					? queue({ items: [card('l1')], counts: counts(3) })
					: queue({ counts: counts(3), moreAvailable: true, nextLearningDueAt: due })
			);
		const { page, dispose } = mount();
		page.start(null);
		await tick(3000);
		expect(page.phase).toBe('loading');
		await tick(3500);
		expect(page.phase).toBe('waiting');
		await tick(60_000);
		expect(page.phase).toBe('waiting');
		await tick(62_000);
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('l1');
		dispose();
	});

	it('items [] with counts > 0 and nothing to wait for is pending and fetched again, never done', async () => {
		const due = new Date(Date.now() + 5000);
		let calls = 0;
		api.getReviewQueue.mockImplementation(async () => {
			calls++;
			if (calls === 1) return queue({ counts: counts(3), nextLearningDueAt: due });
			if (calls < 4) return queue({ counts: counts(3) }); // server says 3 left but serves none, no due instant
			return queue({ items: [card('l1')], counts: counts(3) });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('waiting');
		await tick(6100);
		expect(calls).toBe(2);
		expect(page.phase).toBe('pending');
		for (let i = 0; i < 20 && page.phase !== 'card'; i++) {
			expect(page.phase).toBe('pending');
			await tick(5000);
		}
		expect(page.phase).toBe('card');
		dispose();
	});

	it('a dropped stale refresh with counts left schedules a refetch instead of ending', async () => {
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		let answered = false;
		api.getReviewQueue.mockImplementation(async () =>
			answered ? queue({ items: [card('b')], counts: counts(0, 1) }) : queue({ items: [card('a')], counts: counts(0, 2) })
		);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.current?.card.id).toBe('a');
		// The refresh after the answer fails once: the screen offers a retry (not done) and does not end.
		api.getReviewQueue.mockRejectedValueOnce(new Error('504'));
		page.flip();
		answered = true;
		await page.grade(good);
		await tick();
		expect(page.phase).toBe('error');
		await page.reload();
		await tick();
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('queue fetch rejects (504) → error with retry, not done; retry loads the cards', async () => {
		api.getReviewQueue.mockRejectedValueOnce(new Error('504 Gateway Timeout'));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')], counts: counts(0, 1) }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('error');
		page.retry();
		await tick();
		expect(page.phase).toBe('card');
		dispose();
	});

	it('a queue fetch that never answers times out into the error state', async () => {
		api.getReviewQueue.mockImplementationOnce(() => new Promise(() => {}));
		const { page, dispose } = mount();
		page.start(null);
		await tick(QUEUE_TIMEOUT_MS - 100);
		expect(page.phase).toBe('loading');
		await tick(200);
		expect(page.phase).toBe('error');
		dispose();
	});

	it('a retry while the first load is still running ignores the late first answer', async () => {
		api.getReviewQueue
			.mockImplementationOnce(() => slow(10_000, queue({ counts: counts(0) })))
			.mockImplementationOnce(async () => queue({ items: [card('a')], counts: counts(0, 1) }));
		const { page, dispose } = mount();
		page.start(null);
		await tick(1000);
		page.retry();
		await tick();
		expect(page.phase).toBe('card');
		await tick(10_000);
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('a');
		dispose();
	});

	it('counts all 0 and no next_learning_due_at → done', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ counts: counts(0) }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('done');
		await tick(120_000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(1); // no polling once done
		dispose();
	});

	it('more_available with nothing left under the limits → done for now (keep going offered)', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ counts: counts(0), moreAvailable: true }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('doneForNow');
		dispose();
	});

	it('a failed answer comes back on its back with a notice; retry resends it with the same id', async () => {
		const ids: string[] = [];
		api.answerReview
			.mockImplementationOnce(async (r: { reviewAnswerRequest: { id: string } }) => {
				ids.push(r.reviewAnswerRequest.id);
				throw new Error('500');
			})
			.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => {
				ids.push(r.reviewAnswerRequest.id);
				return { review: { id: r.reviewAnswerRequest.id } };
			});
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')], counts: counts(0, 2) }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b')], counts: counts(0, 1) }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		expect(page.current?.card.id).toBe('a');
		expect(page.flipped).toBe(true);
		expect(page.notice).toBe('answerFailed');
		await page.retryAnswer();
		await tick();
		expect(ids).toHaveLength(2);
		expect(ids[1]).toBe(ids[0]);
		expect(page.notice).toBeNull();
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('an answer that hangs times out and the card comes back (never silently dropped)', async () => {
		api.answerReview.mockImplementationOnce(() => new Promise(() => {}));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')], counts: counts(0, 1) }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		await tick();
		expect(page.phase).toBe('loading'); // settling: the answer is on its way
		await tick(QUEUE_TIMEOUT_MS);
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('a');
		expect(page.notice).toBe('answerFailed');
		dispose();
	});

	it('a failed undo is surfaced and can be retried', async () => {
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		let undone = false;
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')], counts: counts(0, 2) }));
		api.getReviewQueue.mockImplementation(async () =>
			undone ? queue({ items: [card('a'), card('b')], counts: counts(0, 2) }) : queue({ items: [card('b')], counts: counts(0, 1) })
		);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		api.undoReview.mockRejectedValueOnce(new Error('500'));
		await page.undo();
		expect(page.notice).toBe('undoFailed');
		expect(page.current?.card.id).toBe('b');
		api.undoReview.mockImplementation(async () => {
			undone = true;
			return {};
		});
		await page.undo();
		expect(page.notice).toBeNull();
		expect(page.current?.card.id).toBe('a');
		dispose();
	});
});

describe('reviewPhase', () => {
	const base: PhaseInput = {
		loaded: true,
		loadFailed: false,
		refreshFailed: false,
		settling: false,
		hasCurrent: false,
		isDrill: false,
		drillUnavailable: false,
		counts: counts(0),
		nextLearningDueAt: null,
		moreAvailable: false,
		keepGoing: false
	};

	it('waiting beats done for now when a learning card falls due later (the live response)', () => {
		expect(reviewPhase({ ...base, counts: counts(3), moreAvailable: true, nextLearningDueAt: new Date() })).toBe('waiting');
	});
	it('done only with counts 0, no due instant, nothing more available', () => {
		expect(reviewPhase(base)).toBe('done');
		expect(reviewPhase({ ...base, counts: null })).toBe('done');
		expect(reviewPhase({ ...base, counts: counts(0, 0, 1) })).toBe('pending');
		expect(reviewPhase({ ...base, moreAvailable: true })).toBe('doneForNow');
		expect(reviewPhase({ ...base, moreAvailable: true, keepGoing: true })).toBe('done');
	});
	it('loading and errors are never done', () => {
		expect(reviewPhase({ ...base, loaded: false })).toBe('loading');
		expect(reviewPhase({ ...base, settling: true })).toBe('loading');
		expect(reviewPhase({ ...base, loadFailed: true })).toBe('error');
		expect(reviewPhase({ ...base, refreshFailed: true })).toBe('error');
		expect(reviewPhase({ ...base, refreshFailed: true, hasCurrent: true })).toBe('card');
	});
});
