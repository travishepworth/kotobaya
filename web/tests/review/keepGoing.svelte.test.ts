// @vitest-environment jsdom
// C-27: keep going is an endless stream. Every keep-going fetch that returns items keeps the session going, the
// queue is refetched when it empties, a response with only entries still to materialize is fetched again, and the
// end screen (no current card) shows only after a keep-going fetch that is truly empty. No card is shown twice.
import { flushSync } from 'svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { QueueItem } from '../../src/lib/api/generated';

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
	presetsApi: {},
	noteTypesApi: {},
	settingsApi: {}
}));

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';

const card = (id: string): QueueItem => ({ card: { id, presetId: 'p' }, reviewType: 'schedule', isNew: true }) as unknown as QueueItem;
const good = { rating: 3 } as never;
const settle = async () => {
	for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
};

describe('keep going', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
	});

	it('streams batches until a keep-going fetch is empty, never repeating a card', async () => {
		const total = 47; // more than two batches of 20
		const answered: string[] = [];
		let materializeHiccup = true;
		api.answerReview.mockImplementation(async (req: { reviewAnswerRequest: { id: string; cardId: string } }) => {
			answered.push(req.reviewAnswerRequest.cardId);
			return { review: { id: req.reviewAnswerRequest.id } };
		});
		// Server: a keep-going batch of at most `limit` unanswered cards; once, a response with nothing yet but entries
		// still to materialize (its rounds ran out).
		api.getReviewQueue.mockImplementation(async (req: { limit: number; keepGoing: boolean }) => {
			expect(req.keepGoing).toBe(true);
			const left = Array.from({ length: total }, (_, i) => `c${i}`).filter((id) => !answered.includes(id));
			if (answered.length === 20 && materializeHiccup) {
				materializeHiccup = false;
				return { items: [], toMaterialize: 5, counts: null, dayEndsAt: new Date(), studyDay: new Date(0) };
			}
			return { items: left.slice(0, req.limit).map(card), toMaterialize: 0, counts: null, dayEndsAt: new Date(), studyDay: new Date(0), moreAvailable: false };
		});
		let page!: ReviewPage;
		const dispose = $effect.root(() => {
			page = new ReviewPage();
		});
		page.keepGoing = true;
		await page.reload();
		flushSync();
		const shown: string[] = [];
		for (let i = 0; i < total + 5 && page.current; i++) {
			shown.push(page.current.card.id);
			page.flip();
			await page.grade(good);
			await settle();
			flushSync();
		}
		expect(new Set(shown).size).toBe(shown.length);
		expect(shown.length).toBe(total);
		expect(page.current).toBeUndefined();
		expect(page.keepGoing).toBe(true);
		dispose();
	});
});
