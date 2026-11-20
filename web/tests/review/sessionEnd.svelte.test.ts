// @vitest-environment jsdom
// RC-WEB / ADR-037: the review screen reaches an end screen only when `loop.phase` says so. The user report
// (web 0.1.22): Start on 3 learning cards due in ~2 min, a ~6 s queue fetch, then the session-over screen.
import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { card, queue } from './loopFixture';

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
import { ResponseError } from '../../src/lib/api/generated';

const good = { rating: 3, label: 'good' } as never;
const waiting = (due: Date, count = 3) => queue({ loop: { phase: 'waiting', later: { count, nextDueAt: due } } });
/** `reviewing` with nothing served (a response that raced an answer). */
const reviewingEmpty = () => queue({ loop: { phase: 'reviewing', ready: { review: 2 } } });

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
		localStorage.clear();
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it('user report: slow fetch of 3 learning cards due in 2 min shows loading, then waiting, then the card', async () => {
		const due = new Date(Date.now() + 120_000);
		api.getReviewQueue
			.mockImplementationOnce(() =>
				slow(6000, waiting(due))
			)
			.mockImplementation(async () =>
				Date.now() >= due.getTime()
					? queue({ items: [card('l1')] })
					: waiting(due)
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

	it('reviewing with items [] and nothing to wait for is settling and fetched again, never done', async () => {
		const due = new Date(Date.now() + 5000);
		let calls = 0;
		api.getReviewQueue.mockImplementation(async () => {
			calls++;
			if (calls === 1) return waiting(due);
			if (calls < 4) return reviewingEmpty(); // the loop says cards are ready but none was served
			return queue({ items: [card('l1')] });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('waiting');
		await tick(6100);
		expect(calls).toBe(2);
		expect(page.phase).toBe('settling');
		for (let i = 0; i < 20 && page.phase !== 'card'; i++) {
			expect(page.phase).toBe('settling');
			await tick(5000);
		}
		expect(page.phase).toBe('card');
		dispose();
	});

	it('a failed refresh while reviewing stays settling and refetches instead of ending', async () => {
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		let answered = false;
		api.getReviewQueue.mockImplementation(async () =>
			answered ? queue({ items: [card('b')] }) : queue({ items: [card('a')] })
		);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.current?.card.id).toBe('a');
		// The refresh after the answer fails once: the screen offers a retry (not done) and does not end.
		// Both the prefetch (fired at the tap) and the refresh after the answer fail.
		api.getReviewQueue.mockRejectedValueOnce(new Error('504')).mockRejectedValueOnce(new Error('504'));
		page.flip();
		answered = true;
		await page.grade(good);
		await tick();
		// The loop said reviewing: settling with an inline notice (no full-page error), fetched again by itself.
		expect(page.phase).toBe('settling');
		expect(page.refreshFailed).toBe(true);
		await tick(5000);
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('queue fetch rejects (504) → error with retry, not done; retry loads the cards', async () => {
		api.getReviewQueue.mockRejectedValueOnce(new Error('504 Gateway Timeout'));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')] }));
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
			.mockImplementationOnce(() => slow(10_000, queue()))
			.mockImplementationOnce(async () => queue({ items: [card('a')] }));
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

	it('phase empty → done, no polling', async () => {
		api.getReviewQueue.mockResolvedValue(queue());
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('done');
		await tick(120_000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(1); // no polling once done
		dispose();
	});

	it('done_for_today → done for today (keep going offered), no polling', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ loop: { phase: 'done_for_today' } }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('doneForToday');
		await tick(30 * 60_000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(1);
		expect(vi.getTimerCount()).toBe(0);
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
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		expect(page.current?.card.id).toBe('a');
		expect(page.flipped).toBe(true);
		expect(page.notice).toBe('answerFailed');
		// Locked: a regrade would reuse the same answer id (the server ignores the new rating); only Retry sends.
		await page.grade({ rating: 1, label: 'again' } as never);
		expect(page.key('1', false)).toBe(false);
		expect(ids).toHaveLength(1);
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
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		await tick();
		expect(page.phase).toBe('settling'); // the answer is on its way; the card stays on screen
		await tick(QUEUE_TIMEOUT_MS);
		expect(page.phase).toBe('card');
		expect(page.current?.card.id).toBe('a');
		expect(page.notice).toBe('answerFailed');
		dispose();
	});

	it('a failed undo is surfaced and can be retried', async () => {
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		let undone = false;
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockImplementation(async () =>
			undone ? queue({ items: [card('a'), card('b')] }) : queue({ items: [card('b')] })
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

describe('review flow hardening (RC-WEB attempt 2)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it('an answer the server refuses for good (410 gone) drops the card and shows the next one', async () => {
		api.answerReview.mockRejectedValueOnce(new ResponseError(new Response(null, { status: 410 })));
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		expect(page.current?.card.id).toBe('b');
		expect(page.notice).toBeNull();
		expect(page.history).toHaveLength(0);
		dispose();
	});

	it('a 5xx keeps the card pinned for Retry', async () => {
		api.answerReview.mockRejectedValueOnce(new ResponseError(new Response(null, { status: 503 })));
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick(10_000);
		expect(page.current?.card.id).toBe('a');
		expect(page.notice).toBe('answerFailed');
		dispose();
	});

	it('the last card stays on screen (held) while its answer is saving: no skeleton swap', async () => {
		api.answerReview.mockImplementation(
			(r: { reviewAnswerRequest: { id: string } }) => slow(3000, { review: { id: r.reviewAnswerRequest.id } })
		);
		let answered = false;
		api.getReviewQueue.mockImplementation(async () =>
			answered ? queue() : queue({ items: [card('a')] })
		);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		answered = true;
		await tick(100);
		expect(page.phase).toBe('settling');
		expect(page.display?.card.id).toBe('a');
		expect(page.current).toBeUndefined(); // inert: nothing can be graded
		await tick(3000);
		expect(page.phase).toBe('done');
		expect(page.display).toBeUndefined();
		dispose();
	});

	it('the local queue is refilled while answers are pending, so it does not drain', async () => {
		const answered: string[] = [];
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string; cardId: string } }) => {
			await slow(2000, null);
			answered.push(r.reviewAnswerRequest.cardId);
			return { review: { id: r.reviewAnswerRequest.id } };
		});
		const all = ['a', 'b', 'c', 'd', 'e'];
		let first = true;
		api.getReviewQueue.mockImplementation(async () => {
			const left = all.filter((x) => !answered.includes(x));
			const items = first ? left.slice(0, 2) : left; // the first fetch is short (a server limit)
			first = false;
			return queue({ items: items.map((id) => card(id)) });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const shown: string[] = [];
		for (let i = 0; i < 5; i++) {
			expect(page.phase).toBe('card');
			shown.push(page.current!.card.id);
			page.flip();
			void page.grade(good);
			await tick(50); // faster than the answers are saved
		}
		expect(shown).toEqual(all);
		dispose();
	});

	it('an undo while a prefetch is in flight keeps the undone card (prefetch never removes a local card)', async () => {
		api.answerReview.mockImplementation(
			(r: { reviewAnswerRequest: { id: string } }) => slow(2000, { review: { id: r.reviewAnswerRequest.id } })
		);
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockImplementation(() => slow(1000, queue({ items: [card('b'), card('c')] })));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good); // prefetch starts (a is pending, so the server's answer leaves it out)
		await page.undo(); // a was not sent yet: withdrawn, back in front
		expect(page.current?.card.id).toBe('a');
		await tick(1100);
		expect(page.items.map((i) => i.card.id)).toEqual(['a', 'b', 'c']);
		dispose();
	});

	it('goal_reached with a learning card later: one fetch when it falls due serves it (reviewing again)', async () => {
		const due = new Date(Date.now() + 60_000);
		api.getReviewQueue.mockImplementation(async () =>
			Date.now() >= due.getTime()
				? queue({ items: [card('l1')] })
				: queue({ loop: { phase: 'goal_reached', later: { count: 1, nextDueAt: due } } })
		);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('goalReached');
		await tick(59_000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(1);
		await tick(3000);
		expect(api.getReviewQueue).toHaveBeenCalledTimes(2);
		expect(page.current?.card.id).toBe('l1');
		dispose();
	});

	it('C5: reviewing with only entries to materialize shows settling, refetches 3 times at once, then every 5 s', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ loop: { phase: 'reviewing' }, toMaterialize: 4 }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(api.getReviewQueue).toHaveBeenCalledTimes(4);
		expect(page.phase).toBe('settling'); // there is no pending screen
		await tick(5100);
		expect(api.getReviewQueue.mock.calls.length).toBeGreaterThan(4); // a way forward: it refetches by itself
		expect(page.phase).toBe('settling');
		dispose();
	});

	it('settling backs off, stops after a few tries and offers a quiet Check again, which resumes it', async () => {
		api.getReviewQueue.mockResolvedValue(reviewingEmpty()); // server never serves them
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('settling');
		await tick(60 * 60_000);
		const calls = api.getReviewQueue.mock.calls.length;
		expect(calls).toBeLessThanOrEqual(7);
		expect(page.pendingStopped).toBe(true);
		expect(vi.getTimerCount()).toBe(0);
		expect(page.phase).toBe('settling'); // never "done" while the loop says cards remain
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')] }));
		await page.reload();
		await tick();
		expect(page.pendingStopped).toBe(false);
		expect(page.phase).toBe('card');
		dispose();
	});

	it('a prefetch that lands after a newer refresh is dropped (no buried sibling comes back)', async () => {
		let n = 0;
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		api.getReviewQueue.mockImplementation(() => {
			n++;
			if (n === 1) return Promise.resolve(queue({ items: [card('a'), card('b')] }));
			// The prefetch (fired at the tap) is slow and was built before the answer buried sibling s.
			if (n === 2) return slow(5000, queue({ items: [card('b'), card('s')] }));
			return Promise.resolve(queue({ items: [card('b')] }));
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		await tick(100); // answer saved, refresh applied: [b]
		expect(page.items.map((i) => i.card.id)).toEqual(['b']);
		await tick(5000); // the late prefetch arrives
		expect(page.items.map((i) => i.card.id)).toEqual(['b']);
		dispose();
	});

	it('a 422 reviewed_at in_future (fast clock) keeps the card with a clock notice; retry uses server time', async () => {
		const reqs: { id: string; reviewedAt?: Date }[] = [];
		const problem = {
			type: 'urn:kotobaya:problem:validation_failed',
			code: 'validation_failed',
			status: 422,
			title: 'Validation failed',
			errors: [{ pointer: '/reviewed_at', code: 'in_future', message: 'in the future' }]
		};
		api.answerReview
			.mockImplementationOnce(async (r: { reviewAnswerRequest: { id: string; reviewedAt?: Date } }) => {
				reqs.push(r.reviewAnswerRequest);
				throw new ResponseError(new Response(JSON.stringify(problem), { status: 422, headers: { 'Content-Type': 'application/problem+json' } }));
			})
			.mockImplementation(async (r: { reviewAnswerRequest: { id: string; reviewedAt?: Date } }) => {
				reqs.push(r.reviewAnswerRequest);
				return { review: { id: r.reviewAnswerRequest.id } };
			});
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b')] }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		expect(page.current?.card.id).toBe('a');
		expect(page.notice).toBe('clockSkew');
		await page.retryAnswer();
		await tick();
		expect(reqs).toHaveLength(2);
		expect(reqs[1].id).toBe(reqs[0].id);
		expect(reqs[0].reviewedAt).toBeInstanceOf(Date);
		expect(reqs[1].reviewedAt).toBeUndefined(); // the server's now
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('after a clock 422 the next answers are sent without reviewed_at and with no notice', async () => {
		const reqs: { id: string; cardId: string; reviewedAt?: Date }[] = [];
		const problem = {
			type: 'urn:kotobaya:problem:validation_failed',
			code: 'validation_failed',
			status: 422,
			title: 'Validation failed',
			errors: [{ pointer: '/reviewed_at', code: 'in_future', message: 'in the future' }]
		};
		type R = { reviewAnswerRequest: { id: string; cardId: string; reviewedAt?: Date } };
		api.answerReview
			.mockImplementationOnce(async (r: R) => {
				reqs.push(r.reviewAnswerRequest);
				throw new ResponseError(new Response(JSON.stringify(problem), { status: 422, headers: { 'Content-Type': 'application/problem+json' } }));
			})
			.mockImplementation(async (r: R) => {
				reqs.push(r.reviewAnswerRequest);
				return { review: { id: r.reviewAnswerRequest.id } };
			});
		api.getReviewQueue.mockResolvedValueOnce(queue({ items: [card('a'), card('b'), card('c')] }));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('b'), card('c')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade(good);
		await tick();
		expect(page.notice).toBe('clockSkew');
		await page.retryAnswer();
		await tick();
		expect(page.current?.card.id).toBe('b');
		page.flip();
		await page.grade(good);
		await tick();
		expect(reqs).toHaveLength(3);
		expect(reqs[0].reviewedAt).toBeInstanceOf(Date);
		expect(reqs[2].cardId).toBe('b');
		expect(reqs[2].reviewedAt).toBeUndefined();
		expect(page.notice).toBeNull();
		dispose();
	});

	it('a failed wake fetch while waiting keeps the countdown (inline notice, no full-page error)', async () => {
		const due = new Date(Date.now() + 10_000);
		api.getReviewQueue.mockResolvedValueOnce(waiting(due, 2));
		api.getReviewQueue.mockRejectedValueOnce(new Error('504'));
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('l1')] }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		await tick(11_100);
		expect(page.phase).toBe('waiting');
		expect(page.refreshFailed).toBe(true);
		await tick(5000);
		expect(page.phase).toBe('card');
		dispose();
	});

	it('a retry during a slow load aborts the old fetch', async () => {
		const signals: AbortSignal[] = [];
		api.getReviewQueue.mockImplementation((_r: unknown, init?: RequestInit) => {
			if (init?.signal) signals.push(init.signal);
			return slow(10_000, queue());
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick(100);
		page.retry();
		await tick(100);
		expect(signals).toHaveLength(2);
		expect(signals[0].aborted).toBe(true);
		expect(signals[1].aborted).toBe(false);
		dispose();
	});
});

describe('reviewPhase', () => {
	const base: PhaseInput = {
		loaded: true,
		loadFailed: false,
		refreshFailed: false,
		hasCurrent: false,
		isDrill: false,
		drillUnavailable: false,
		screen: 'empty'
	};

	it('with no card on screen the screen is the presenter screen only (C7)', () => {
		expect(reviewPhase(base)).toBe('done');
		expect(reviewPhase({ ...base, screen: 'waiting' })).toBe('waiting');
		expect(reviewPhase({ ...base, screen: 'goal_reached' })).toBe('goalReached');
		expect(reviewPhase({ ...base, screen: 'done_for_today' })).toBe('doneForToday');
		expect(reviewPhase({ ...base, screen: 'settling' })).toBe('settling');
		expect(reviewPhase({ ...base, screen: 'settling', hasCurrent: true })).toBe('card');
	});
	it('loading and errors are never done', () => {
		expect(reviewPhase({ ...base, loaded: false })).toBe('loading');
		expect(reviewPhase({ ...base, loadFailed: true })).toBe('error');
		expect(reviewPhase({ ...base, refreshFailed: true })).toBe('error');
		expect(reviewPhase({ ...base, screen: null })).toBe('error');
		expect(reviewPhase({ ...base, refreshFailed: true, screen: 'waiting' })).toBe('waiting');
		expect(reviewPhase({ ...base, refreshFailed: true, screen: 'settling' })).toBe('settling');
		expect(reviewPhase({ ...base, refreshFailed: true, hasCurrent: true })).toBe('card');
	});
});
