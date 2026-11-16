// @vitest-environment jsdom
// ADR-037 C2: Keep going is persisted per client for the study day (localStorage["kotobaya.keepGoingDay"]); it
// survives leaving the screen and a reload, is dropped (and the queue fetched again without it) when `study_day`
// changes, and Home and the review screen send the same flag.
import { flushSync } from 'svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queue } from './loopFixture';

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
// Home builds its own ReviewApi (quiet config): the same mock answers it.
vi.mock('../../src/lib/api/generated', async (orig) => ({
	...(await orig<typeof import('../../src/lib/api/generated')>()),
	ReviewApi: class {
		getReviewQueue = api.getReviewQueue;
	}
}));

import { ReviewPage, resetOutboxForTests } from '../../src/lib/state/review.svelte';
import { TodayQueue } from '../../src/lib/state/today.svelte';
import { KEEP_GOING_KEY } from '../../src/lib/review/keepGoingDay';

const settle = async () => {
	for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
	flushSync();
};

function reviewPage(): { page: ReviewPage; dispose: () => void } {
	let page!: ReviewPage;
	const root = $effect.root(() => {
		page = new ReviewPage();
	});
	page.start(null);
	return {
		page,
		dispose: () => {
			page.dispose();
			root();
		}
	};
}

const flags = () => api.getReviewQueue.mock.calls.map((c) => (c[0] as { keepGoing: boolean }).keepGoing);

/** Server for one study day: done_for_today without keep going, a keep-going card with it. */
function serve(day: string): void {
	api.getReviewQueue.mockImplementation(async (r: { keepGoing: boolean }) =>
		r.keepGoing
			? queue({ studyDay: day, items: [], loop: { phase: 'empty', keepGoing: true } })
			: queue({ studyDay: day, loop: { phase: 'done_for_today', backlog: { reviews: 135, beyondLimit: 135 } } })
	);
}

describe('keep going day (C2)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
	});

	it('persists across a remount: the next visit sends keep_going=true', async () => {
		serve('2026-11-17');
		const first = reviewPage();
		await settle();
		expect(first.page.phase).toBe('doneForToday');
		await first.page.keepGoingOn();
		await settle();
		expect(localStorage.getItem(KEEP_GOING_KEY)).toBe('2026-11-17');
		expect(first.page.keepGoing).toBe(true);
		first.dispose();

		api.getReviewQueue.mockClear();
		const second = reviewPage();
		await settle();
		expect(flags()).toEqual([true]);
		expect(second.page.keepGoing).toBe(true);
		second.dispose();
	});

	it('is dropped when study_day changes: refetched with keep_going=false, mismatching response discarded', async () => {
		localStorage.setItem(KEEP_GOING_KEY, '2026-11-16');
		serve('2026-11-17');
		const { page, dispose } = reviewPage();
		await settle();
		expect(flags()).toEqual([true, false]);
		expect(localStorage.getItem(KEEP_GOING_KEY)).toBeNull();
		expect(page.keepGoing).toBe(false);
		expect(page.phase).toBe('doneForToday'); // the keep_going=false answer, not the discarded one
		dispose();

		api.getReviewQueue.mockClear();
		const today = new TodayQueue();
		await today.load();
		expect(flags()).toEqual([false]);
		today.dispose();
	});

	it('Home and the review screen send the same flag', async () => {
		serve('2026-11-17');
		const home = new TodayQueue();
		await home.load();
		const r1 = reviewPage();
		await settle();
		r1.dispose();
		expect(flags()).toEqual([false, false]);

		// Keep going pressed on Home: persisted for the shown day; the review screen then sends it too.
		api.getReviewQueue.mockClear();
		expect(home.phase).toBe('done_for_today');
		home.keepGoing();
		expect(localStorage.getItem(KEEP_GOING_KEY)).toBe('2026-11-17');
		await home.load();
		const r2 = reviewPage();
		await settle();
		r2.dispose();
		expect(flags()).toEqual([true, true]);
		home.dispose();
	});

	it('a failed Keep going fetch leaves it off', async () => {
		serve('2026-11-17');
		const { page, dispose } = reviewPage();
		await settle();
		api.getReviewQueue.mockRejectedValueOnce(new Error('504'));
		await page.keepGoingOn();
		expect(localStorage.getItem(KEEP_GOING_KEY)).toBeNull();
		expect(page.phase).toBe('doneForToday');
		dispose();
	});
});
