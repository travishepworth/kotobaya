// @vitest-environment jsdom
// ADR-037 C4: with no card the review screen (and the Home hero) is rendered from `loop.phase` only. One case per
// phase from a mocked `loop`. The fixture's `counts` / `more_available` / `next_learning_due_at` throw when read, so
// no code path reads the deprecated fields while `loop` is present.
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { card, queue, type LoopOver } from './loopFixture';

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
import { loopView } from '../../src/lib/review/loop';
import LoopEnd from '../../src/routes/review/LoopEnd.svelte';
import LoopHero from '../../src/lib/review/LoopHero.svelte';

const NOW = new Date('2026-11-17T09:00:00Z').getTime();
const settle = async () => {
	for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
	flushSync();
};

const mounted: ReturnType<typeof mount>[] = [];
afterEach(() => {
	mounted.splice(0).forEach((m) => unmount(m));
	document.body.innerHTML = '';
	vi.useRealTimers();
});

async function reviewScreen(over: LoopOver): Promise<{ page: ReviewPage; el: HTMLElement; dispose: () => void }> {
	api.getReviewQueue.mockResolvedValue(queue({ loop: over }));
	let page!: ReviewPage;
	const root = $effect.root(() => {
		page = new ReviewPage();
	});
	page.start(null);
	await settle();
	const el = document.body.appendChild(document.createElement('div'));
	const phase = page.phase as 'goalReached' | 'doneForToday' | 'waiting' | 'done';
	mounted.push(
		mount(LoopEnd, {
			target: el,
			props: { phase, view: page.view, busy: false, canUndo: false, checkFailed: false, onkeepgoing: () => {}, onundo: () => {}, onreload: () => {} }
		})
	);
	flushSync();
	return {
		page,
		el,
		dispose: () => {
			page.dispose();
			root();
		}
	};
}

function hero(over: LoopOver, items = 0): HTMLElement {
	const el = document.body.appendChild(document.createElement('div'));
	const view = loopView(queue({ items: Array.from({ length: items }, (_, i) => card(`c${i}`)), loop: over }));
	mounted.push(mount(LoopHero, { target: el, props: { view, onkeepgoing: () => {} } }));
	flushSync();
	return el;
}

describe('review screen per loop phase', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		resetOutboxForTests();
		localStorage.clear();
		vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
		vi.setSystemTime(NOW);
	});

	it('reviewing: the first card is on screen', async () => {
		api.getReviewQueue.mockResolvedValue(queue({ items: [card('a')], loop: { phase: 'reviewing', ready: { review: 1 } } }));
		let page!: ReviewPage;
		const root = $effect.root(() => {
			page = new ReviewPage();
		});
		page.start(null);
		await settle();
		expect(page.phase).toBe('card');
		expect(page.loop?.ready.total).toBe(1);
		page.dispose();
		root();
	});

	it('goal_reached: new bar 40/40 and Keep going', async () => {
		const { page, el, dispose } = await reviewScreen({ phase: 'goal_reached', reviews: { today: 120, limit: null }, newCards: { today: 40, goal: 40, available: true } });
		expect(page.phase).toBe('goalReached');
		expect(el.textContent).toContain('New-card goal reached');
		expect(el.querySelector('[data-testid="end-quota"]')?.textContent).toBe('40/40 new · New-card goal reached');
		expect([...el.querySelectorAll('button')].map((b) => b.textContent?.trim())).toContain('Keep going');
		dispose();
	});

	it('done_for_today: reviews 200/200, new 40/40, 135 more in backlog, Keep going', async () => {
		const { page, el, dispose } = await reviewScreen({
			phase: 'done_for_today',
			reviews: { today: 200, limit: 200 },
			backlog: { reviews: 135, beyondLimit: 135 },
			newCards: { today: 40, goal: 40, available: true }
		});
		expect(page.phase).toBe('doneForToday');
		expect(el.textContent).toContain('Done for today');
		expect(el.querySelector('[data-testid="end-quota"]')?.textContent).toBe('200/200 reviews · 40/40 new');
		expect(el.querySelector('[data-testid="end-backlog"]')?.textContent).toBe('135 more reviews in backlog');
		expect([...el.querySelectorAll('button')].map((b) => b.textContent?.trim())).toContain('Keep going');
		dispose();
	});

	it('done_for_today unlimited: the quota line has no reviews part', async () => {
		const { el, dispose } = await reviewScreen({ phase: 'done_for_today', reviews: { today: 212, limit: null }, newCards: { today: 40, goal: 40, available: true } });
		expect(el.querySelector('[data-testid="end-quota"]')?.textContent).toBe('40/40 new');
		dispose();
	});

	it('waiting: next card in m:ss, no Keep going', async () => {
		const due = new Date(NOW + 6 * 60_000 + 5_000);
		const { page, el, dispose } = await reviewScreen({ phase: 'waiting', later: { count: 8, nextDueAt: due } });
		expect(page.phase).toBe('waiting');
		expect(el.querySelector('h2')?.textContent).toBe('Next card in');
		expect(el.querySelector('.countdown')?.textContent).toBe('6:05');
		expect(el.querySelector('.countdown')?.getAttribute('aria-label')).toBe('Next card in 6:05');
		expect(el.textContent).not.toContain('Keep going');
		dispose();
	});

	it('empty: all done for today', async () => {
		const { page, el, dispose } = await reviewScreen({ phase: 'empty' });
		expect(page.phase).toBe('done');
		expect(el.textContent).toContain('All done for today');
		expect(el.textContent).not.toContain('Keep going');
		dispose();
	});

	it('an unknown phase is reviewing with items, else empty (open enum)', () => {
		expect(loopView(queue({ loop: { phase: 'paused' as never } })).phase).toBe('empty');
		expect(loopView(queue({ items: [card('a')], loop: { phase: 'paused' as never } })).phase).toBe('reviewing');
	});
});

describe('Home hero per loop phase', () => {
	beforeEach(() => {
		vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
		vi.setSystemTime(NOW);
	});

	it('reviewing: ready number, Start, backlog line and later hint', () => {
		const el = hero(
			{
				phase: 'reviewing',
				ready: { learning: 1, review: 200 },
				later: { count: 8, nextDueAt: new Date(NOW + 6 * 60_000) },
				backlog: { reviews: 335, beyondLimit: 135 },
				reviews: { today: 0, limit: 200 },
				newCards: { today: 12, goal: 40, available: true }
			},
			1
		);
		expect(el.querySelector('[data-testid="loop-ready"]')?.textContent).toBe('201');
		expect(el.querySelector('a[href="/review"]')?.textContent).toContain('Start review');
		expect(el.querySelector('[data-testid="loop-backlog"]')?.textContent?.trim()).toBe('Backlog: 335 reviews due · 200/day limit');
		expect(el.querySelector('[data-testid="loop-later"]')?.textContent).toBe('8 learning later today · next in 6 min');
		expect(el.querySelector('[data-testid="loop-new"]')?.textContent).toContain('12/40');
	});

	it('reviewing past the goal after keep going: the number, not a bar fraction', () => {
		const el = hero({ phase: 'reviewing', keepGoing: true, newCards: { today: 52, goal: 40, available: true } }, 1);
		expect(el.querySelector('[data-testid="loop-new"]')?.textContent).toContain('52 new today');
		expect(el.querySelector('[data-testid="loop-new"]')?.textContent).not.toContain('52/40');
	});

	it('no backlog line while nothing is held back by the limit', () => {
		const el = hero({ phase: 'reviewing', ready: { review: 30 }, backlog: { reviews: 30, beyondLimit: 0 } }, 1);
		expect(el.querySelector('[data-testid="loop-backlog"]')).toBeNull();
	});

	it('goal_reached → "New-card goal reached" + Keep going', () => {
		const el = hero({ phase: 'goal_reached', newCards: { today: 40, goal: 40, available: true } });
		expect(el.querySelector('h2')?.textContent).toBe('New-card goal reached');
		expect([...el.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual(['Keep going']);
		expect(el.querySelector('a[href="/review"]')).toBeNull();
	});

	it('done_for_today → "Done for today" + Keep going', () => {
		const el = hero({ phase: 'done_for_today', backlog: { reviews: 135, beyondLimit: 135 }, reviews: { today: 200, limit: 200 } });
		expect(el.querySelector('h2')?.textContent).toBe('Done for today');
		expect([...el.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual(['Keep going']);
		expect(el.querySelector('[data-testid="loop-backlog"]')?.textContent?.trim()).toBe('Backlog: 135 reviews due · 200/day limit');
	});

	it('waiting → countdown', () => {
		const el = hero({ phase: 'waiting', later: { count: 8, nextDueAt: new Date(NOW + 90_000) } });
		expect(el.querySelector('[data-testid="loop-countdown"]')?.textContent?.trim()).toBe('Next card in 1:30');
		expect(el.querySelector('button')).toBeNull();
	});

	it('empty → all done', () => {
		const el = hero({ phase: 'empty' });
		expect(el.querySelector('h2')?.textContent).toBe('All done for today');
		expect(el.querySelector('button')).toBeNull();
	});
});
