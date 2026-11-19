// @vitest-environment jsdom
// ADR-039: Keep going lasts one review visit (in memory): cleared when the screen is left, on for the whole visit,
// never in localStorage. A stale study_day is dropped (and the queue fetched again without it) when `study_day`
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
import { clearKeepGoing, keepGoingDay, startKeepGoing } from '../../src/lib/review/keepGoingDay';

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
		clearKeepGoing();
	});

	it('cleared on a new visit; persists across fetches within a visit', async () => {
		serve('2026-11-17');
		const first = reviewPage();
		await settle();
		await first.page.keepGoingOn();
		await settle();
		expect(first.page.keepGoing).toBe(true);
		await first.page.reload();
		await settle();
		expect(flags().slice(-1)).toEqual([true]); // still on within the visit
		expect(localStorage.getItem('kotobaya.keepGoingDay')).toBeNull();
		first.dispose();

		api.getReviewQueue.mockClear();
		const second = reviewPage();
		await settle();
		expect(flags()).toEqual([false]);
		expect(second.page.keepGoing).toBe(false);
		second.dispose();
	});

	it('is dropped when study_day changes: refetched with keep_going=false, mismatching response discarded', async () => {
		startKeepGoing(new Date('2026-11-16T00:00:00Z'));
		serve('2026-11-17');
		const { page, dispose } = reviewPage();
		await settle();
		expect(flags()).toEqual([true, false]);
		expect(keepGoingDay()).toBeNull();
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
		expect(keepGoingDay()).toBe('2026-11-17');
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
		expect(keepGoingDay()).toBeNull();
		expect(page.phase).toBe('doneForToday');
		dispose();
	});
});
