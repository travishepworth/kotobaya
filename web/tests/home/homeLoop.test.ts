// @vitest-environment jsdom
// ADR-037 user case A1 (reviews unlimited): 0 ready, 26 learning later today, new cards available. The server says
// `reviewing` (new cards are served). Home must offer Start, show the later hint, and the headline must be
// `ready.total` = 0, not the old over-count (26 learning + new = 28 shown, then a waiting screen).
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { card, queue } from '../review/loopFixture';
import { loopView } from '../../src/lib/review/loop';
import LoopHero from '../../src/lib/review/LoopHero.svelte';

const NOW = new Date('2026-11-17T09:00:00Z').getTime();
const mounted: ReturnType<typeof mount>[] = [];

beforeEach(() => {
	vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
	vi.setSystemTime(NOW);
});
afterEach(() => {
	mounted.splice(0).forEach((m) => unmount(m));
	document.body.innerHTML = '';
	vi.useRealTimers();
});

describe('Home hero on loop (A1)', () => {
	it('0 ready + 26 later + new available → Start enabled, hint shown, number 0 (not 26/28)', () => {
		const q = queue({
			items: [card('n1', { isNew: true })],
			loop: {
				phase: 'reviewing',
				ready: { learning: 0, review: 0 },
				later: { count: 26, nextDueAt: new Date(NOW + 6 * 60_000) },
				reviews: { today: 9, limit: null },
				newCards: { today: 0, goal: 40, available: true }
			}
		});
		const el = document.body.appendChild(document.createElement('div'));
		mounted.push(mount(LoopHero, { target: el, props: { view: loopView(q), onkeepgoing: () => {} } }));
		flushSync();

		expect(el.querySelector('[data-testid="loop-ready"]')?.textContent).toBe('0');
		expect(el.textContent).not.toMatch(/\b2[68]\b(?! learning later)/);
		const start = el.querySelector('a[href="/review"]');
		expect(start?.textContent).toContain('Start review'); // a link: enabled (a disabled Button renders no href)
		expect(el.querySelector('[data-testid="loop-later"]')?.textContent).toBe('26 learning later today · next in 6 min');
		expect(el.querySelector('[data-testid="loop-new"]')?.textContent).toContain('0/40');
		expect(el.querySelector('[data-testid="loop-backlog"]')).toBeNull();
	});
});
