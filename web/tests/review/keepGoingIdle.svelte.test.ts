// @vitest-environment jsdom
// ADR-039 idle amendment: keep going also ends after 10 minutes without an answer. Only Date is faked (the
// settle loop needs real timers); the idle check is lazy, so no timer is involved.
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
	presetsApi: {},
	noteTypesApi: {},
	settingsApi: {}
}));

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';
import { keepGoingDay, startKeepGoing } from '../../src/lib/review/keepGoingDay';

const MIN = 60_000;
const good = { rating: 3 } as never;
const T0 = new Date('2026-11-17T09:00:00Z');
const settle = async () => {
	for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
	flushSync();
};
const flags = () => api.getReviewQueue.mock.calls.map((c) => (c[0] as { keepGoing: boolean }).keepGoing);

/** Keep going on: a batch of cards. Off: done for today. */
function serve(): void {
	api.getReviewQueue.mockImplementation(async (r: { keepGoing: boolean }) =>
		r.keepGoing
			? queue({ studyDay: '2026-11-17', items: [card('k1', { isNew: true }), card('k2', { isNew: true })], loop: { phase: 'reviewing', keepGoing: true } })
			: queue({ studyDay: '2026-11-17', loop: { phase: 'done_for_today', backlog: { reviews: 135, beyondLimit: 135 } } })
	);
}

describe('keep going idle (ADR-039 amendment)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
		vi.useFakeTimers({ toFake: ['Date'] });
		vi.setSystemTime(T0);
		serve();
		startKeepGoing(new Date('2026-11-17T00:00:00Z'));
	});

	afterEach(() => {
		vi.useRealTimers();
	});

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

	it('11 min idle: the next fetch sends keep_going=false', async () => {
		const { page, dispose } = mount();
		await page.reload();
		await settle();
		expect(flags()).toEqual([true]);

		vi.setSystemTime(T0.getTime() + 11 * MIN);
		await page.reload();
		await settle();
		expect(flags()).toEqual([true, false]);
		expect(keepGoingDay()).toBeNull();
		dispose();
	});

	it('9 min idle: the next fetch still sends keep_going=true', async () => {
		const { page, dispose } = mount();
		await page.reload();
		await settle();

		vi.setSystemTime(T0.getTime() + 9 * MIN);
		await page.reload();
		await settle();
		expect(flags()).toEqual([true, true]);
		expect(keepGoingDay()).toBe('2026-11-17');
		dispose();
	});

	it('an answer restarts the idle clock', async () => {
		api.answerReview.mockImplementation(async (req: { reviewAnswerRequest: { id: string } }) => ({
			review: { id: req.reviewAnswerRequest.id }
		}));
		const { page, dispose } = mount();
		await page.reload();
		await settle();

		vi.setSystemTime(T0.getTime() + 9 * MIN);
		page.flip();
		await page.grade(good);
		await settle();

		vi.setSystemTime(T0.getTime() + 18 * MIN); // 9 min after the answer
		await page.reload();
		await settle();
		expect(flags().length).toBeGreaterThan(2); // the answer's own refill fetches are sent too
		expect(flags().every(Boolean)).toBe(true);
		expect(keepGoingDay()).toBe('2026-11-17');
		dispose();
	});

	it('returning to the page after 11 min idle ends it before any fetch', async () => {
		vi.setSystemTime(T0.getTime() + 11 * MIN);
		const { page, dispose } = mount();
		page.onVisible();
		expect(keepGoingDay()).toBeNull();
		dispose();
	});
});
