// @vitest-environment jsdom
// C-27 / ADR-037: keep going is an endless stream. Every keep-going fetch that returns items keeps the session going,
// the queue is refetched when it empties, a response with only entries still to materialize is fetched again (C5), and
// the end screen (no current card) shows only after a keep-going fetch that is truly empty. No card is shown twice.
import { flushSync } from 'svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
	presetsApi: {},
	noteTypesApi: {},
	settingsApi: {}
}));

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';
import { startKeepGoing } from '../../src/lib/review/keepGoingDay';

const good = { rating: 3 } as never;
const settle = async () => {
	for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
};

describe('keep going', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
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
				return queue({ loop: { phase: 'reviewing', keepGoing: true }, toMaterialize: 5 });
			}
			const items = left.slice(0, req.limit).map((id) => card(id, { isNew: true }));
			return queue({ items, loop: { phase: items.length > 0 ? 'reviewing' : 'empty', keepGoing: true } });
		});
		startKeepGoing(new Date('2026-11-17T00:00:00Z'));
		let page!: ReviewPage;
		const dispose = $effect.root(() => {
			page = new ReviewPage();
		});
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
		page.dispose();
		dispose();
	});
});
