// @vitest-environment jsdom
// ADR-048 regression: review -> dictionary -> back left the session stuck on the old card. The kept ReviewPage was
// created during the /review component's init, so its $derived fields (current, display, phase) belonged to that
// component and went inert when it was destroyed; the resumed session answered but never moved on.
import { describe, expect, it, vi } from 'vitest';
import type { QueueItem } from '../../src/lib/api/generated';

vi.mock('../../src/lib/review/api', () => ({
	reviewApi: {},
	cardsApi: {},
	notesApi: {},
	wordsApi: {},
	presetsApi: { listPresets: async () => ({ items: [] }) },
	noteTypesApi: { listNoteTypes: async () => ({ items: [] }) },
	settingsApi: { listSettings: async () => ({ items: [] }) }
}));

import { createReviewPage, type ReviewPage } from '../../src/lib/state/review.svelte';
import { keepSession, takeSession } from '../../src/lib/state/reviewSession';

const item = (id: string): QueueItem => ({ card: { id } }) as unknown as QueueItem;

/** What the /review component does: create the session during its init, then be destroyed (navigation away). */
function visit(make: () => ReviewPage): ReviewPage {
	let page!: ReviewPage;
	const component = $effect.root(() => {
		page = make();
		void page.current; // the view reads it while mounted
	});
	page.pauseSession();
	component();
	return page;
}

describe('a kept review session stays live after its component is gone', () => {
	it('advances to the next card after resume', () => {
		const page = visit(createReviewPage);
		keepSession(page, null, 'k');
		const resumed = takeSession(null, 'k');
		expect(resumed).toBe(page);
		page.resume();

		page.items = [item('a'), item('b')];
		expect(page.current?.card.id).toBe('a');
		page.items = page.items.slice(1); // what an answer does
		expect(page.current?.card.id).toBe('b');
		expect(page.display?.card.id).toBe('b');
		page.release();
	});

	it('a dropped session is released', () => {
		const page = visit(createReviewPage);
		keepSession(page, null, 'k1');
		expect(takeSession(null, 'other')).toBeNull(); // a different history entry: the kept one is dropped
		expect(page.destroyRoot).toBeNull();
	});
});
