// @vitest-environment jsdom
// LOOP-31-WEB (C7, ADR-051/052): the review screen, the header parts and the bar come from the core presenter;
// no pending screen; Keep going sends a goal-sized batch target with every fetch of the visit.
import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { card, queue, type LoopOver } from './loopFixture';

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

import { ReviewPage, resetOutboxForTests, keepGoingAvailable, trimAnswerLog } from '../../src/lib/state/review.svelte';
import { loopView } from '../../src/lib/review/loop';
import { clearKeepGoing, keepGoingNewTarget } from '../../src/lib/review/keepGoingDay';

const good = { rating: 3 } as never;
const tick = async (ms = 0) => {
	await vi.advanceTimersByTimeAsync(ms);
	flushSync();
};
const reviewCards = (n: number) => Array.from({ length: n }, (_, i) => card(`c${i}`, { card: { id: `c${i}`, presetId: 'p', state: 'review' } }));

function mount() {
	let page!: ReviewPage;
	const dispose = $effect.root(() => {
		page = new ReviewPage();
	});
	return {
		page,
		dispose: () => {
			page.dispose();
			dispose();
		}
	};
}

beforeEach(() => {
	vi.useFakeTimers();
	vi.resetAllMocks();
	resetOutboxForTests();
	clearKeepGoing();
	localStorage.clear();
});
afterEach(() => vi.useRealTimers());

describe('presenter-driven header and bar', () => {
	it('with every answer request held open the header parts move per tap and the bar total does not grow on Good', async () => {
		api.answerReview.mockImplementation(() => new Promise(() => {})); // never answers
		const all = reviewCards(5);
		api.getReviewQueue.mockImplementation(async () => queue({ items: all, loop: { phase: 'reviewing', parts: { review: 5 } } }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const total = () => page.history.length + (page.presentation?.untilStop ?? -1);
		expect(page.presentation?.parts.review).toBe(5);
		expect(total()).toBe(5);
		for (let i = 1; i <= 5; i++) {
			page.flip();
			void page.grade(good);
			await tick();
			expect(page.presentation?.parts.review).toBe(5 - i); // never frozen at 5
			expect(total()).toBe(5); // Good never grows the bar
		}
		expect(page.phase).toBe('settling');
		dispose();
	});
});

describe('no terminal screen while something is pending', () => {
	const phases = ['reviewing', 'waiting', 'goal_reached', 'done_for_today', 'empty'] as const;
	const terminal = ['goalReached', 'doneForToday', 'done'];

	// Each row applies a full unguarded fetch (reload) while the answer is unacknowledged: the stale response is
	// really in `view` (asserted), and the presenter still refuses to end the visit.
	const rows: { phase: (typeof phases)[number]; parts: LoopOver['parts'] }[] = [
		...phases.map((phase) => ({ phase, parts: {} })),
		{ phase: 'goal_reached', parts: { review: 2 } },
		{ phase: 'empty', parts: { review: 2 } }
	];
	it.each(rows)('answer outstanding, applied response says $phase with parts $parts: settling, never terminal', async ({ phase, parts }) => {
		api.answerReview.mockImplementation(() => new Promise(() => {}));
		let first = true;
		api.getReviewQueue.mockImplementation(async () => {
			if (first) {
				first = false;
				return queue({ items: reviewCards(1), loop: { phase: 'reviewing', parts: { review: 1 } } });
			}
			// The server has not seen the answer: nothing served and a stale phase.
			return queue({ items: [], loop: { phase, parts } });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		await tick(1);
		await page.reload();
		await tick();
		expect(page.view?.phase).toBe(phase); // the stale response was applied
		expect(page.saving).toBe(true);
		expect(terminal).not.toContain(page.phase);
		expect(page.phase).toBe('settling');
		dispose();
	});

	it('answer acked (outbox empty) while an unguarded reload is in flight that says goal_reached: settling until the follow-up fetch', async () => {
		// The presenter-only case: the answer is acknowledged, but it was given after the reload's request was sent
		// (seq > appliedSeq), so the stale response does not show it and it still counts as pending.
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		const held: ((q: unknown) => void)[] = [];
		let first = true;
		api.getReviewQueue.mockImplementation(() => {
			if (first) {
				first = false;
				return Promise.resolve(queue({ items: reviewCards(1), loop: { phase: 'reviewing', parts: { review: 1 } } }));
			}
			return new Promise((res) => held.push(res));
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		// The unguarded full fetch `reload()` runs, without reload's busy lock (which refuses taps meanwhile): the
		// only way an answer can be given after the applied response's request was sent.
		const reload = (page as unknown as { fetchQueue: () => Promise<boolean> }).fetchQueue(); // sent before the tap
		await tick();
		expect(held).toHaveLength(1);
		page.flip();
		void page.grade(good);
		await tick();
		expect(page.saving).toBe(false); // acknowledged: the outbox is empty
		expect(held.length).toBeGreaterThanOrEqual(2); // the follow-up fetch after the ack is in flight
		const stale = queue({ items: [], loop: { phase: 'goal_reached', parts: {} } });
		held[0](stale);
		await reload;
		await tick();
		expect(page.view?.phase).toBe('goal_reached'); // the stale response was applied ...
		expect(page.phase).toBe('settling'); // ... and the acked answer it does not show keeps the visit open
		for (const r of held.slice(1)) r(queue({ items: [], loop: { phase: 'goal_reached', parts: {} } }));
		await tick();
		expect(page.phase).toBe('goalReached'); // the follow-up fetch shows the answer: now it may end
		dispose();
	});

	it.each(phases)('consistent response with phase %s: a terminal screen only with zero parts and no pending answer', async (phase) => {
		// ADR-049: a stop screen only exists when until_stop is 0; `reviewing` with nothing served has parts left.
		const parts = phase === 'reviewing' ? { review: 2 } : phase === 'waiting' ? { later: 1 } : {};
		api.getReviewQueue.mockResolvedValue(queue({ items: [], loop: { phase, parts } }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		if (terminal.includes(page.phase)) {
			expect(page.presentation?.untilStop).toBe(0);
			expect(page.pendingAnswers).toEqual([]);
		}
		if (phase === 'reviewing') expect(page.phase).toBe('settling');
		dispose();
	});
});

describe('settling, never a pending screen', () => {
	it('reviewing + no items + to_materialize > 0 shows settling, then refetches', async () => {
		let calls = 0;
		api.getReviewQueue.mockImplementation(async () => {
			calls++;
			return calls < 6 ? queue({ items: [], toMaterialize: 3, loop: { phase: 'reviewing' } }) : queue({ items: [card('a')] });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('settling');
		for (let i = 0; i < 5 && page.phase === 'settling'; i++) await tick(5100);
		expect(page.phase).toBe('card');
		expect(calls).toBeGreaterThanOrEqual(6);
	});

	it('C5: after the first three retries, exactly one request per 5 s tick', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ items: [], toMaterialize: 3, loop: { phase: 'reviewing' } }));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(api.getReviewQueue).toHaveBeenCalledTimes(4); // the first fetch and its three retries
		for (let n = 1; n <= 3; n++) {
			await tick(5000);
			expect(api.getReviewQueue).toHaveBeenCalledTimes(4 + n);
		}
		dispose();
	});
});

describe('Keep going (ADR-052)', () => {
	const stop = (today: number, over: LoopOver = {}) =>
		queue({ items: [], loop: { phase: 'goal_reached', keepGoingAvailable: true, newCards: { today, goal: 20, available: true }, ...over } });

	it('sends keep_going_new_target on every fetch of the visit; at the target the stop screen returns', async () => {
		const calls: { keepGoing?: boolean; keepGoingNewTarget?: number }[] = [];
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => ({ review: { id: r.reviewAnswerRequest.id } }));
		let answered = 0;
		api.getReviewQueue.mockImplementation(async (req: { keepGoing?: boolean; keepGoingNewTarget?: number }) => {
			calls.push(req);
			if (!req.keepGoing) return stop(20);
			if (answered === 0) return queue({ items: [card('n1', { isNew: true })], loop: { phase: 'reviewing', keepGoing: true, newCards: { today: 20, goal: 20 } } });
			return stop(40, { keepGoing: true });
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		expect(page.phase).toBe('goalReached');
		expect(calls[0].keepGoingNewTarget).toBeUndefined();

		await page.keepGoingOn();
		await tick();
		expect(calls.at(-1)).toMatchObject({ keepGoing: true, keepGoingNewTarget: 40 });
		expect(page.current?.card.id).toBe('n1');

		page.flip();
		answered = 1;
		await page.grade(good);
		await tick(5000);
		// Every fetch of the visit carries the same target.
		for (const c of calls.slice(1)) expect(c).toMatchObject({ keepGoing: true, keepGoingNewTarget: 40 });
		expect(page.phase).toBe('goalReached'); // the batch is done: the stop screen is back ...
		expect(keepGoingAvailable(page.view!)).toBe(true); // ... with Keep going

		await page.keepGoingOn(); // the next press computes the next T from that response
		expect(keepGoingNewTarget()).toBe(60);
		dispose();
	});

	it('the target is dropped with the visit (Home and the next visit send none)', async () => {
		api.getReviewQueue.mockResolvedValue(stop(20));
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		await page.keepGoingOn();
		expect(keepGoingNewTarget()).toBe(40);
		page.dispose();
		expect(keepGoingNewTarget()).toBeNull();
		dispose();
	});

	it('the empty phase never offers Keep going', () => {
		expect(keepGoingAvailable(loopView(queue({ items: [], loop: { phase: 'empty' } })))).toBe(false);
		expect(keepGoingAvailable(loopView(queue({ items: [], loop: { phase: 'empty', keepGoingAvailable: false } })))).toBe(false);
		expect(keepGoingAvailable(loopView(queue({ items: [], loop: { phase: 'waiting', keepGoingAvailable: false } })))).toBe(false);
		expect(keepGoingAvailable(loopView(queue({ items: [], loop: { phase: 'waiting', keepGoingAvailable: true } })))).toBe(true);
	});
});

describe('trimAnswerLog (presenter log bound)', () => {
	const entries = (n: number, acked: (i: number) => boolean) =>
		Array.from({ length: n }, (_, i) => ({ seq: i + 1, acked: acked(i + 1) }));

	it('more than 200 unacked entries: nothing is dropped', () => {
		const log = entries(250, () => false);
		expect(trimAnswerLog(log, 250)).toHaveLength(250);
	});

	it('drops only acked entries at or below every live page\'s applied seq, oldest first, down to 200', () => {
		// 260 entries; odd seqs acked; the slowest live page has applied seq 100.
		const log = entries(260, (seq) => seq % 2 === 1);
		const out = trimAnswerLog(log, 100);
		const dropped = log.filter((l) => !out.includes(l));
		expect(dropped).toHaveLength(50); // only 50 acked entries are <= 100: still 210 left (above the bound)
		expect(dropped.every((l) => l.acked && l.seq <= 100)).toBe(true);
		expect(out.filter((l) => !l.acked)).toHaveLength(130); // every unacked entry is kept
		expect(out.some((l) => l.seq > 100 && l.acked)).toBe(true); // acked but newer than a live page: kept
	});

	it('stops at the bound: never trims below 200', () => {
		const log = entries(230, () => true);
		const out = trimAnswerLog(log, 230);
		expect(out).toHaveLength(200);
		expect(out[0].seq).toBe(31); // the oldest 30 went
	});
});
