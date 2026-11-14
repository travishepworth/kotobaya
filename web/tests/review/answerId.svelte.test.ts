// @vitest-environment jsdom
// BUG-14a: the answer id is per press. A card that comes back (relearning) must not reuse the previous id,
// but a retry of a failed POST for the same press must.
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

import { ReviewPage } from '../../src/lib/state/review.svelte';

const item = { card: { id: 'card-1', presetId: 'p' }, reviewType: 'schedule' } as unknown as QueueItem;
const button = { rating: 3 } as never;

function setup(): { page: ReviewPage; ids: string[]; dispose: () => void } {
	const ids: string[] = [];
	api.answerReview.mockImplementation(async (req: { reviewAnswerRequest: { id: string } }) => {
		ids.push(req.reviewAnswerRequest.id);
		return { review: { id: `r${ids.length}` } };
	});
	api.getReviewQueue.mockResolvedValue({ items: [item], counts: null, dayEndsAt: new Date() });
	let page!: ReviewPage;
	const dispose = $effect.root(() => {
		page = new ReviewPage();
	});
	page.items = [item];
	flushSync();
	return { page, ids, dispose };
}

describe('review answer id', () => {
	beforeEach(() => vi.clearAllMocks());

	it('sends a different id when the same card is served again', async () => {
		const { page, ids, dispose } = setup();
		page.flip();
		await page.grade(button);
		expect(page.flipped).toBe(false);
		page.flip();
		await page.grade(button);
		expect(ids).toHaveLength(2);
		expect(ids[0]).not.toBe(ids[1]);
		expect(page.history.map((h) => h.reviewId)).toEqual(['r1', 'r2']);
		dispose();
	});

	it('reuses the id when a failed POST is retried', async () => {
		const { page, ids, dispose } = setup();
		api.answerReview.mockRejectedValueOnce(new Error('network'));
		page.flip();
		const first = page.answerId;
		await page.grade(button);
		expect(page.answerId).toBe(first);
		await page.grade(button);
		expect(ids).toEqual([first]);
		dispose();
	});

	it('does not present the graded card again when the queue refetch fails', async () => {
		const { page, ids, dispose } = setup();
		api.getReviewQueue.mockRejectedValueOnce(new Error('network'));
		page.flip();
		await page.grade(button);
		flushSync();
		expect(ids).toHaveLength(1);
		expect(page.current).toBeUndefined();
		expect(page.flipped).toBe(false);
		page.flip();
		await page.grade(button);
		expect(ids).toHaveLength(1);
		expect(page.history.map((h) => h.reviewId)).toEqual(['r1']);
		dispose();
	});

	it('mints a fresh id after undo', async () => {
		const { page, dispose } = setup();
		page.flip();
		await page.grade(button);
		const before = page.answerId;
		api.undoReview.mockResolvedValue({});
		await page.undo();
		expect(page.answerId).not.toBe(before);
		dispose();
	});
});
