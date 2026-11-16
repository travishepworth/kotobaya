// @vitest-environment jsdom
// WEB-REVIEW-FAST: rapid clicks and keys. A card is graded once, the next card shows at the tap, answers are
// sent in tap order, and a failed send restores the card without losing or reordering later answers.
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

const card = (id: string): QueueItem => ({ card: { id, presetId: 'p' }, reviewType: 'schedule' }) as unknown as QueueItem;
const good = { rating: 3 } as never;

interface Sent {
	cardId: string;
	id: string;
}

function setup(ids: string[]): { page: ReviewPage; sent: Sent[]; dispose: () => void } {
	const sent: Sent[] = [];
	api.answerReview.mockImplementation(async (req: { reviewAnswerRequest: Sent }) => {
		sent.push({ cardId: req.reviewAnswerRequest.cardId, id: req.reviewAnswerRequest.id });
		return { review: { id: req.reviewAnswerRequest.id } };
	});
	// The server queue never lists answered cards again.
	api.getReviewQueue.mockImplementation(async () => ({
		items: ids.filter((i) => !sent.some((s) => s.cardId === i)).map(card),
		counts: null,
		dayEndsAt: new Date()
	}));
	let page!: ReviewPage;
	const dispose = $effect.root(() => {
		page = new ReviewPage();
	});
	page.items = ids.map(card);
	flushSync();
	return { page, sent, dispose };
}

const settle = () => new Promise((r) => setTimeout(r, 0));

describe('rapid review input', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
	});

	it('reveal then an immediate grade click advances once and submits once', async () => {
		const { page, sent, dispose } = setup(['a', 'b', 'c']);
		page.flip();
		void page.grade(good);
		expect(page.current?.card.id).toBe('b'); // advanced at the tap
		expect(page.flipped).toBe(false);
		await settle();
		expect(sent.map((s) => s.cardId)).toEqual(['a']);
		expect(page.current?.card.id).toBe('b');
		dispose();
	});

	it('a double click on grade submits one answer; a double reveal stays on the back', async () => {
		const { page, sent, dispose } = setup(['a', 'b']);
		page.flip();
		page.flip();
		expect(page.current?.card.id).toBe('a');
		expect(page.flipped).toBe(true);
		void page.grade(good);
		void page.grade(good); // second click: card b is on its front, so it is refused
		await settle();
		expect(sent.map((s) => s.cardId)).toEqual(['a']);
		expect(page.current?.card.id).toBe('b');
		expect(page.flipped).toBe(false);
		dispose();
	});

	it('a failed submit restores the card; later taps are withdrawn in order, none lost', async () => {
		const { page, sent, dispose } = setup(['a', 'b', 'c']);
		let rejectFirst!: () => void;
		api.answerReview.mockImplementationOnce(
			() => new Promise((_, rej) => (rejectFirst = () => rej(new Error('network'))))
		);
		page.flip();
		void page.grade(good); // a in flight
		await settle();
		page.flip();
		void page.grade(good); // b queued behind it
		expect(page.current?.card.id).toBe('c');
		rejectFirst();
		await settle();
		expect(sent).toEqual([]); // b was never sent ahead of a
		expect(page.items.map((i) => i.card.id)).toEqual(['a', 'b', 'c']);
		expect(page.history).toEqual([]);
		expect(page.flipped).toBe(true);
		const retryId = page.answerId;
		await page.retryAnswer(); // retry reuses the id (a regrade is locked)
		expect(sent).toEqual([{ cardId: 'a', id: retryId }]);
		dispose();
	});

	it('sends answers in tap order', async () => {
		const { page, sent, dispose } = setup(['a', 'b', 'c']);
		for (let i = 0; i < 3; i++) {
			page.flip();
			void page.grade(good);
		}
		await settle();
		expect(sent.map((s) => s.cardId)).toEqual(['a', 'b', 'c']);
		dispose();
	});

	it('key auto-repeat does not grade twice or skip the back', async () => {
		const { page, sent, dispose } = setup(['a', 'b']);
		page.key(' ', false);
		page.key(' ', true);
		expect(page.flipped).toBe(true);
		page.key('3', false);
		page.key('3', true);
		page.key('3', true);
		await settle();
		expect(sent.map((s) => s.cardId)).toEqual(['a']);
		expect(page.current?.card.id).toBe('b');
		expect(page.flipped).toBe(false);
		dispose();
	});

	it('undo right after a tap withdraws the unsent answer; undo of an in-flight one waits for it', async () => {
		const { page, sent, dispose } = setup(['a', 'b']);
		let release!: () => void;
		let sentId = '';
		api.answerReview.mockImplementationOnce(
			(req: { reviewAnswerRequest: Sent }) =>
				new Promise((res) => {
					release = () => {
						sentId = req.reviewAnswerRequest.id;
						sent.push({ cardId: req.reviewAnswerRequest.cardId, id: req.reviewAnswerRequest.id });
						res({ review: { id: req.reviewAnswerRequest.id } });
					};
				})
		);
		page.flip();
		void page.grade(good); // a in flight
		await settle();
		page.flip();
		void page.grade(good); // b queued
		await page.undo(); // withdraws b without any request
		expect(page.items.map((i) => i.card.id)).toEqual(['b']);
		api.undoReview.mockImplementation(async () => {
			sent.length = 0; // the server forgets the undone answer
			return {};
		});
		const u = page.undo(); // a is in flight: waits
		await settle();
		expect(api.undoReview).not.toHaveBeenCalled();
		release();
		await u;
		expect(api.undoReview).toHaveBeenCalledTimes(1);
		expect(api.undoReview.mock.calls[0][0].reviewUndoRequest.reviewId).toBe(sentId);
		expect(page.items.map((i) => i.card.id)).toEqual(['a', 'b']);
		dispose();
	});

	it('a stale queue fetch resolving after a further tap does not bring back the answered card', async () => {
		const { page, sent, dispose } = setup(['a', 'b', 'c']);
		let resolveStale!: () => void;
		api.getReviewQueue.mockImplementationOnce(
			() =>
				new Promise((res) => {
					resolveStale = () => res({ items: ['a', 'b', 'c'].map(card), counts: null, dayEndsAt: new Date() });
				})
		);
		page.flip();
		void page.grade(good);
		await settle(); // a recorded; the refresh is now hanging with the old list
		page.flip();
		void page.grade(good); // b tapped while it hangs
		resolveStale();
		await settle();
		expect(page.items.map((i) => i.card.id)).toEqual(['c']);
		expect(sent.map((x) => x.cardId)).toEqual(['a', 'b']);
		dispose();
	});

	it('a page opened while an answer is in flight does not list that card again', async () => {
		const first = setup(['a', 'b', 'c']);
		let release!: () => void;
		api.answerReview.mockImplementationOnce(
			(req: { reviewAnswerRequest: Sent }) =>
				new Promise((res) => {
					release = () => {
						first.sent.push({ cardId: req.reviewAnswerRequest.cardId, id: req.reviewAnswerRequest.id });
						res({ review: { id: 'r' } });
					};
				})
		);
		first.page.flip();
		void first.page.grade(good);
		await settle();
		first.dispose(); // left /review
		let second!: ReviewPage;
		const dispose2 = $effect.root(() => {
			second = new ReviewPage();
		});
		api.getReviewQueue.mockResolvedValue({ items: ['a', 'b', 'c'].map(card), counts: null, dayEndsAt: new Date() });
		await second.reload();
		expect(second.items.map((i) => i.card.id)).toEqual(['b', 'c']);
		release();
		await settle();
		dispose2();
	});

	it('a refresh keeps the card on screen, back showing', async () => {
		const { page, dispose } = setup(['a', 'b', 'c']);
		// Every fetch from now on (the prefetch at the tap and the refresh after the answer) hangs until the server
		// has a learning card x first.
		const waiting: (() => void)[] = [];
		const resolveRefresh = () => waiting.splice(0).forEach((r) => r());
		api.getReviewQueue.mockImplementation(
			() =>
				new Promise((res) => {
					waiting.push(() => res({ items: ['x', 'b', 'c'].map(card), counts: null, dayEndsAt: new Date() }));
				})
		);
		page.flip();
		void page.grade(good);
		await settle(); // a recorded; the refresh hangs
		page.flip(); // b's back is showing
		resolveRefresh(); // a learning card x is now first on the server
		await settle();
		expect(page.items.map((i) => i.card.id)).toEqual(['b', 'x', 'c']);
		expect(page.flipped).toBe(true);
		dispose();
	});
});
