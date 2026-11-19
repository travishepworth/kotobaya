// @vitest-environment jsdom
// ADR-037 C1 (due first): the client may hold a page of cards, but before showing each next card it fetches again
// once now >= T, T = min(`loop.later_today.next_due_at`, `due_at` of each learning answer since that fetch). So a
// learning card that came due is the very next card, never one of the held new cards.
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

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';

const good = { rating: 3, label: 'good' } as never;
const tick = async (ms = 0) => {
	await vi.advanceTimersByTimeAsync(ms);
	flushSync();
};

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

describe('due first (C1)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it('3 cards + a learning card due during card 2: never loading, due card next after it is due, never before', async () => {
		const due = new Date(Date.now() + 60_000);
		const answered: string[] = [];
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string; cardId: string } }) => {
			answered.push(r.reviewAnswerRequest.cardId);
			return { review: { id: r.reviewAnswerRequest.id }, card: { id: r.reviewAnswerRequest.cardId, state: 'review', dueAt: null } };
		});
		api.getReviewQueue.mockImplementation(async () => {
			const news = ['n1', 'n2', 'n3'].filter((id) => !answered.includes(id)).map((id) => card(id, { isNew: true }));
			return Date.now() >= due.getTime()
				? queue({ items: [card('l1'), ...news], loop: { phase: 'reviewing', ready: { learning: 1 } } })
				: queue({ items: news, loop: { phase: 'reviewing', later: { count: 1, nextDueAt: due } } });
		});
		const { page, dispose } = mount();
		const phases: string[] = [];
		const watch = $effect.root(() => {
			$effect(() => {
				phases.push(page.phase);
			});
		});
		page.start(null);
		await tick();
		expect(page.items.map((i) => i.card.id)).toEqual(['n1', 'n2', 'n3']);
		page.flip();
		void page.grade(good); // n1 answered before l1 is due
		await tick();
		expect(page.current?.card.id).toBe('n2'); // never l1 before its due_at
		await tick(61_000); // l1 falls due while n2 is on screen: spliced in behind it in the background
		expect(page.current?.card.id).toBe('n2');
		page.flip();
		void page.grade(good);
		expect(page.current?.card.id).toBe('l1'); // next, immediately
		await tick();
		expect(page.current?.card.id).toBe('l1');
		expect(phases.slice(phases.indexOf('card')).every((p) => p === 'card')).toBe(true); // no loading/settling
		watch();
		dispose();
	});

	it('an answer that leaves a card in learning brings T forward to its due_at', async () => {
		const answered: string[] = [];
		let againDue = 0;
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string; cardId: string; rating: number } }) => {
			const id = r.reviewAnswerRequest.cardId;
			answered.push(id);
			if (r.reviewAnswerRequest.rating === 1) againDue = Date.now() + 30_000;
			const state = r.reviewAnswerRequest.rating === 1 ? 'relearning' : 'review';
			return { review: { id: r.reviewAnswerRequest.id }, card: { id, state, dueAt: state === 'relearning' ? new Date(againDue) : null } };
		});
		api.getReviewQueue.mockImplementation(async () => {
			const left = ['a', 'b', 'c', 'd'].filter((id) => !answered.includes(id) || (id === 'a' && Date.now() >= againDue));
			const relearn = left.includes('a') && answered.includes('a');
			const order = relearn ? ['a', ...left.filter((x) => x !== 'a')] : left;
			return queue({
				items: order.map((id) => card(id)),
				loop: {
					phase: 'reviewing',
					ready: { review: order.length },
					later: relearn || !answered.includes('a') ? { count: 0, nextDueAt: null } : { count: 1, nextDueAt: new Date(againDue) }
				}
			});
		});
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		await page.grade({ rating: 1, label: 'again' } as never); // a → relearning, due in 30 s
		await tick();
		expect(page.current?.card.id).toBe('b');
		await tick(31_000);
		page.flip();
		await page.grade(good); // b answered after a came due: a is next, not c
		await tick();
		expect(page.current?.card.id).toBe('a');
		dispose();
	});

	it('before T the held page is used as is (no refetch per card)', async () => {
		api.answerReview.mockImplementation(async (r: { reviewAnswerRequest: { id: string; cardId: string } }) => ({
			review: { id: r.reviewAnswerRequest.id },
			card: { id: r.reviewAnswerRequest.cardId, state: 'review', dueAt: null }
		}));
		const due = new Date(Date.now() + 10 * 60_000);
		api.getReviewQueue.mockResolvedValueOnce(
			queue({ items: ['n1', 'n2', 'n3', 'n4', 'n5', 'n6'].map((id) => card(id)), loop: { phase: 'reviewing', later: { count: 1, nextDueAt: due } } })
		);
		api.getReviewQueue.mockImplementation(() => new Promise(() => {})); // later fetches never answer
		const { page, dispose } = mount();
		page.start(null);
		await tick();
		page.flip();
		void page.grade(good);
		expect(page.current?.card.id).toBe('n2');
		dispose();
	});
});
