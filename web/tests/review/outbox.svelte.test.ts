// @vitest-environment jsdom
// LF-W1 / openapi C8: the persisted answer outbox. A grade tap never waits for the network and is never put back;
// answers survive a reload and are sent once, in tap order; undo of an unsent answer removes it; logout clears it.
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

import {
	ReviewPage,
	clearOutbox,
	resetOutboxForTests,
	setOutboxStoreForTests,
	simulateReloadForTests
} from '../../src/lib/state/review.svelte';
import { memoryOutboxStore, type OutboxStore } from '../../src/lib/review/outboxStore';
import { ResponseError } from '../../src/lib/api/generated';

const good = { rating: 3, label: 'good' } as never;

interface Req {
	id: string;
	cardId: string;
	reviewedAt?: Date;
}

/**
 * A server that dedups by answer `id` (contract: a repeat returns the stored review) and never lists an answered
 * card again. `down` makes every request fail like a dropped connection; `hang` keeps answers unanswered.
 */
function server(ids: string[]) {
	const s = {
		down: false,
		hang: false,
		calls: [] as Req[],
		reviews: new Map<string, string>(), // answer id -> card id
		undone: new Set<string>()
	};
	api.answerReview.mockImplementation((r: { reviewAnswerRequest: Req }) => {
		const req = r.reviewAnswerRequest;
		if (s.down) return Promise.reject(new TypeError('Failed to fetch'));
		s.calls.push({ ...req });
		if (s.hang) return new Promise(() => {});
		if (!s.reviews.has(req.id)) s.reviews.set(req.id, req.cardId);
		return Promise.resolve({ review: { id: req.id }, card: { id: req.cardId, state: 'review', dueAt: null } });
	});
	api.undoReview.mockImplementation((r: { reviewUndoRequest: { reviewId: string } }) => {
		if (s.down) return Promise.reject(new TypeError('Failed to fetch'));
		const id = r.reviewUndoRequest.reviewId;
		if (!s.reviews.has(id)) return Promise.reject(new ResponseError(new Response(null, { status: 404 })));
		s.undone.add(id);
		return Promise.resolve({ review: { id } });
	});
	api.getReviewQueue.mockImplementation(() => {
		if (s.down) return Promise.reject(new TypeError('Failed to fetch'));
		const answered = new Set([...s.reviews].filter(([id]) => !s.undone.has(id)).map(([, c]) => c));
		return Promise.resolve(queue({ items: ids.filter((i) => !answered.has(i)).map((i) => card(i)) }));
	});
	return s;
}

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

let store: OutboxStore;

beforeEach(() => {
	vi.resetAllMocks();
	resetOutboxForTests();
	store = memoryOutboxStore();
	setOutboxStoreForTests(store);
	localStorage.clear();
	vi.useFakeTimers();
});
afterEach(() => {
	vi.useRealTimers();
});

describe('persisted answer outbox (LF-W1, C8)', () => {
	it('tap, reload before the response: the answer is sent exactly once after reload with the same id, card not served again', async () => {
		const s = server(['a', 'b', 'c']);
		const first = mount();
		first.page.start(null);
		await tick();
		s.hang = true; // the response never comes back (the tab is reloaded meanwhile)
		first.page.flip();
		const tappedId = first.page.answerId;
		void first.page.grade(good);
		await tick();
		expect((await store.all()).map((r) => r.id)).toEqual([tappedId]);
		const before = s.calls.length;

		simulateReloadForTests(); // the page's memory is gone; the store is not
		first.dispose();
		s.hang = false;
		const second = mount();
		second.page.start(null);
		await tick();
		await tick(100);
		const after = s.calls.slice(before);
		expect(after).toHaveLength(1);
		expect(after[0].id).toBe(tappedId);
		expect(s.reviews.size).toBe(1);
		expect(second.page.items.map((i) => i.card.id)).not.toContain('a');
		expect(second.page.current?.card.id).toBe('b');
		expect(await store.all()).toEqual([]);
		second.dispose();
	});

	it('a stored answer is not served while its replay is still on its way (filtered from the first fetch)', async () => {
		const s = server(['a', 'b']);
		const first = mount();
		first.page.start(null);
		await tick();
		s.hang = true;
		first.page.flip();
		void first.page.grade(good);
		await tick();
		simulateReloadForTests();
		first.dispose();
		// After reload the answer is sent but its response is slow; the server's queue does not know it yet.
		const second = mount();
		second.page.start(null);
		await tick();
		expect(second.page.items.map((i) => i.card.id)).toEqual(['b']);
		expect(second.page.saving).toBe(true);
		second.dispose();
	});

	it('10 answers with the network down: all 10 cards advance with no restore; back online they go in tap order', async () => {
		const ids = Array.from({ length: 12 }, (_, i) => `c${i}`);
		const s = server(ids);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		s.down = true;
		const tapped: string[] = [];
		const answerIds: string[] = [];
		for (let i = 0; i < 10; i++) {
			expect(page.gradeLocked).toBe(false);
			page.flip();
			tapped.push(page.current?.card.id ?? '');
			answerIds.push(page.answerId);
			void page.grade(good);
			await tick(700); // failures and backoff while the user keeps going
		}
		await tick(5000);
		expect(page.current?.card.id).toBe('c10'); // never put back
		expect(page.notice).toBeNull();
		expect(page.history).toHaveLength(10);
		expect(s.calls).toEqual([]);
		expect((await store.all()).map((r) => r.id)).toEqual(answerIds);

		s.down = false;
		window.dispatchEvent(new Event('online'));
		await tick();
		await tick(100);
		expect(s.calls.map((c) => c.cardId)).toEqual(tapped);
		expect(s.calls.map((c) => c.id)).toEqual(answerIds);
		expect(await store.all()).toEqual([]);
		expect(page.saving).toBe(false);
		dispose();
	});

	it('retries with backoff by itself (1 s, doubling, capped) without an online event', async () => {
		const s = server(['a', 'b']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		s.down = true;
		let tries = 0;
		const real = api.answerReview.getMockImplementation();
		api.answerReview.mockImplementation((r: unknown) => {
			tries++;
			return real?.(r);
		});
		page.flip();
		void page.grade(good);
		await tick();
		expect(tries).toBe(1);
		await tick(1000);
		expect(tries).toBe(2);
		await tick(2000);
		expect(tries).toBe(3);
		await tick(120_000); // 4 s, 8 s, 16 s, then every 30 s at most
		expect(tries).toBeLessThanOrEqual(3 + 3 + Math.ceil((120_000 - 28_000) / 30_000) + 1);
		expect(tries).toBeGreaterThanOrEqual(3 + 3 + Math.floor((120_000 - 28_000) / 30_000));
		s.down = false;
		await tick(30_000);
		expect(s.calls).toHaveLength(1);
		expect(await store.all()).toEqual([]);
		dispose();
	});

	it('duplicate delivery (response lost, retried) leaves one review: the same id is resent', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		// The first request reaches the server but its response is lost.
		api.answerReview.mockImplementationOnce(async (r: { reviewAnswerRequest: Req }) => {
			await real?.(r);
			throw new TypeError('Failed to fetch');
		});
		page.flip();
		const id = page.answerId;
		void page.grade(good);
		await tick();
		await tick(1000);
		expect(s.calls.map((c) => c.id)).toEqual([id, id]);
		expect(s.reviews.size).toBe(1);
		expect(await store.all()).toEqual([]);
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('out-of-order completion cannot reorder sends: one request at a time, the next only after the previous', async () => {
		const s = server(['a', 'b', 'c', 'd']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		const resolvers: (() => void)[] = [];
		let inFlight = 0;
		let maxInFlight = 0;
		api.answerReview.mockImplementation((r: { reviewAnswerRequest: Req }) => {
			inFlight++;
			maxInFlight = Math.max(maxInFlight, inFlight);
			return new Promise((res) => {
				resolvers.push(() => {
					inFlight--;
					res(real?.(r));
				});
			});
		});
		for (let i = 0; i < 3; i++) {
			page.flip();
			void page.grade(good);
		}
		await tick();
		expect(resolvers).toHaveLength(1); // b and c wait behind a
		resolvers[0]();
		await tick();
		expect(resolvers).toHaveLength(2);
		resolvers[1]();
		await tick();
		resolvers[2]();
		await tick();
		expect(maxInFlight).toBe(1);
		expect(s.calls.map((c) => c.cardId)).toEqual(['a', 'b', 'c']);
		dispose();
	});

	it('a timed-out request that lands later does not reorder: the retry of the same id goes before the next tap', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		const sentOrder: string[] = []; // request order as the client sends them
		let late!: () => void;
		let first = true;
		api.answerReview.mockImplementation((r: { reviewAnswerRequest: Req }) => {
			sentOrder.push(r.reviewAnswerRequest.cardId);
			if (!first) return real?.(r);
			first = false;
			return new Promise((res) => (late = () => res(real?.(r))));
		});
		page.flip();
		const aId = page.answerId;
		void page.grade(good);
		await tick();
		page.flip();
		void page.grade(good); // b behind a
		await tick(30_000); // a times out
		await tick(1000); // a retried (same id) and accepted, then b
		late(); // the abandoned first request completes now
		await tick();
		expect(sentOrder).toEqual(['a', 'a', 'b']);
		expect(s.calls[0].id).toBe(aId);
		expect([...s.reviews.values()]).toEqual(['a', 'b']);
		dispose();
	});

	it('clock 422 at /reviewed_at: resent once at once without reviewed_at (in the store too), then all without it', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		const problem = {
			code: 'validation_failed',
			status: 422,
			errors: [{ pointer: '/reviewed_at', code: 'in_future', message: 'in the future' }]
		};
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: Req }) => {
			if (r.reviewAnswerRequest.reviewedAt) {
				s.calls.push({ ...r.reviewAnswerRequest });
				throw new ResponseError(
					new Response(JSON.stringify(problem), { status: 422, headers: { 'Content-Type': 'application/problem+json' } })
				);
			}
			return real?.(r);
		});
		page.flip();
		void page.grade(good);
		await tick();
		page.flip();
		void page.grade(good);
		await tick();
		expect(s.calls.map((c) => [c.cardId, !!c.reviewedAt])).toEqual([
			['a', true],
			['a', false],
			['b', false]
		]);
		expect(page.notice).toBeNull();
		expect(page.current?.card.id).toBe('c');
		dispose();
	});

	it('a permanent refusal drops the entry and toasts; the taps behind it still go', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		api.answerReview.mockRejectedValueOnce(new ResponseError(new Response(null, { status: 410 })));
		page.flip();
		void page.grade(good);
		page.flip();
		void page.grade(good);
		await tick();
		expect(s.calls.map((c) => c.cardId)).toEqual(['b']);
		expect(page.history).toHaveLength(1);
		expect(await store.all()).toEqual([]);
		dispose();
	});

	it('401 keeps the outbox and stops sending until the page is started again (after login)', async () => {
		const s = server(['a', 'b']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		let tries = 0;
		api.answerReview.mockImplementation((r: unknown) => {
			tries++;
			return tries === 1 ? Promise.reject(new ResponseError(new Response(null, { status: 401 }))) : real?.(r);
		});
		page.flip();
		void page.grade(good);
		await tick(120_000);
		expect(tries).toBe(1);
		expect(await store.all()).toHaveLength(1);
		page.start(null); // logged in again, /review opened
		await tick();
		expect(s.calls).toHaveLength(1);
		expect(await store.all()).toEqual([]);
		dispose();
	});

	it('undo before send removes the stored entry (no request at all)', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		s.hang = true;
		page.flip();
		void page.grade(good); // a in flight
		await tick();
		page.flip();
		const bId = page.answerId;
		void page.grade(good); // b stored, not sent
		await tick();
		expect((await store.all()).map((r) => r.id)).toContain(bId);
		await page.undo();
		await tick();
		expect((await store.all()).map((r) => r.id)).not.toContain(bId);
		expect(page.current?.card.id).toBe('b');
		expect(page.flipped).toBe(true);
		expect(s.calls.map((c) => c.cardId)).toEqual(['a']);
		dispose();
	});

	it('undo of a sent answer is itself queued: the card is back at once, the undo goes out when the network does', async () => {
		const s = server(['a', 'b']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		const aId = page.answerId;
		void page.grade(good);
		await tick();
		expect(s.reviews.has(aId)).toBe(true);
		s.down = true;
		await page.undo();
		expect(page.current?.card.id).toBe('a');
		expect(page.flipped).toBe(true);
		expect(page.notice).toBeNull();
		expect((await store.all()).map((r) => r.op)).toEqual(['undo']);
		await tick(5000);
		s.down = false;
		window.dispatchEvent(new Event('online'));
		await tick();
		expect(s.undone.has(aId)).toBe(true);
		expect(await store.all()).toEqual([]);
		// Re-grading the card sends a fresh answer behind the undo.
		page.flip();
		void page.grade(good);
		await tick();
		expect(s.calls.map((c) => c.cardId)).toEqual(['a', 'a']);
		expect(s.calls[1].id).not.toBe(aId);
		dispose();
	});

	it('undo of an answer still in flight never waits: the undo is queued behind it', async () => {
		const s = server(['a', 'b']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		const real = api.answerReview.getMockImplementation();
		let release!: () => void;
		api.answerReview.mockImplementationOnce(
			(r: { reviewAnswerRequest: Req }) => new Promise((res) => (release = () => res(real?.(r))))
		);
		page.flip();
		const aId = page.answerId;
		void page.grade(good);
		await tick();
		await page.undo(); // returns at once
		expect(page.current?.card.id).toBe('a');
		expect(api.undoReview).not.toHaveBeenCalled();
		release();
		await tick();
		expect(api.undoReview).toHaveBeenCalledTimes(1);
		expect(s.undone.has(aId)).toBe(true);
		dispose();
	});

	it('logout clears the store and the pending answers', async () => {
		const s = server(['a', 'b', 'c']);
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		s.down = true;
		page.flip();
		void page.grade(good);
		page.flip();
		void page.grade(good);
		await tick();
		expect(await store.all()).toHaveLength(2);
		await clearOutbox();
		expect(await store.all()).toEqual([]);
		expect(page.saving).toBe(false);
		s.down = false;
		window.dispatchEvent(new Event('online'));
		await tick(60_000);
		expect(s.calls).toEqual([]);
		dispose();
	});
});
