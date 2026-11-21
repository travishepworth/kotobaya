// @vitest-environment jsdom
// LF-W2: deeper prefetch with slow answers, the outbox sent at app start (not only from /review), logout clears it.
import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { card, queue } from './loopFixture';

const api = vi.hoisted(() => ({
	answerReview: vi.fn(),
	undoReview: vi.fn(),
	getReviewQueue: vi.fn(),
	logout: vi.fn()
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
const home = vi.hoisted(() => ({ order: [] as string[], on: false }));
vi.mock('../../src/lib/review/keepGoingDay', async (orig) => {
	const real = await orig<typeof import('../../src/lib/review/keepGoingDay')>();
	return {
		...real,
		// Home's TodayQueue builds its own client; the review page keeps the real wrapper (and the mocked api).
		fetchWithKeepGoing: async (get: Parameters<typeof real.fetchWithKeepGoing>[0]) => {
			if (!home.on) return real.fetchWithKeepGoing(get);
			home.order.push('queue');
			return (await import('./loopFixture')).queue();
		}
	};
});
vi.mock('../../src/lib/api/client', async (orig) => ({
	...(await orig<typeof import('../../src/lib/api/client')>()),
	authApi: { logout: api.logout }
}));

import {
	ReviewPage,
	resetOutboxForTests,
	setOutboxStoreForTests,
	simulateReloadForTests,
	startOutbox
} from '../../src/lib/state/review.svelte';
import { TodayQueue } from '../../src/lib/state/today.svelte';
import { logout } from '../../src/lib/state/session.svelte';
import { memoryOutboxStore, type AnswerRecord, type OutboxStore } from '../../src/lib/review/outboxStore';

const good = { rating: 3, label: 'good' } as never;
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

function stored(id: string, cardId: string): AnswerRecord {
	return {
		id,
		op: 'answer',
		cardId,
		addedAt: 1,
		attempts: 0,
		kind: 'review',
		resultingState: null,
		dueAt: null,
		req: { id, cardId, rating: 3, mode: 'schedule', durationMs: 1, utcOffsetMin: 0 }
	};
}

describe('prefetch with slow answers (LF-W2)', () => {
	it('every answer response delayed 10 s, 30 cards due: 25 cards graded without a settling state', async () => {
		const ids = Array.from({ length: 30 }, (_, i) => `c${i}`);
		const recorded = new Set<string>();
		api.answerReview.mockImplementation(
			(r: { reviewAnswerRequest: { id: string; cardId: string } }) =>
				new Promise((resolve) =>
					setTimeout(() => {
						recorded.add(r.reviewAnswerRequest.cardId);
						resolve({ review: { id: r.reviewAnswerRequest.id }, card: { id: r.reviewAnswerRequest.cardId, state: 'review', dueAt: null } });
					}, 10_000)
				)
		);
		api.getReviewQueue.mockImplementation(async () => queue({ items: ids.filter((i) => !recorded.has(i)).map((i) => card(i)) }));
		let page!: ReviewPage;
		const root = $effect.root(() => {
			page = new ReviewPage();
		});
		page.start(null);
		await tick();
		expect(api.getReviewQueue.mock.calls[0][0].limit).toBe(30);
		for (let n = 0; n < 25; n++) {
			expect(page.current?.card.id).toBe(`c${n}`);
			page.flip();
			void page.grade(good);
			await tick(50);
			expect(page.settling).toBe(false);
			expect(page.current).toBeTruthy();
		}
		page.dispose();
		root();
	});
});

describe('outbox at app start (LF-W2)', () => {
	it('after a reload, stored answers are sent by startOutbox with no ReviewPage, and Home reads its counts after them', async () => {
		await store.put(stored('a1', 'a'));
		simulateReloadForTests();
		const order = home.order;
		order.length = 0;
		home.on = true;
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string } }) => {
			order.push(`answer:${r.reviewAnswerRequest.id}`);
			return { review: { id: r.reviewAnswerRequest.id }, card: { id: 'a', state: 'review', dueAt: null } };
		});

		const today = new TodayQueue();
		const loaded = today.load(); // Home's own load must wait for the stored answer
		await startOutbox();
		await tick(100);
		await loaded;
		expect(order).toEqual(['answer:a1', 'queue']);
		expect(await store.all()).toEqual([]);
		today.dispose();
	});

	it('logout clears the outbox, in memory and in the store', async () => {
		api.answerReview.mockImplementation(() => new Promise(() => {})); // never answered
		api.logout.mockResolvedValue(undefined);
		await store.put(stored('a1', 'a'));
		await startOutbox();
		await tick(10);
		expect(api.answerReview).toHaveBeenCalledTimes(1);
		await logout();
		expect(await store.all()).toEqual([]);
	});
});
