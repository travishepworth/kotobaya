// @vitest-environment jsdom
// Regression for HOTFIX-0.1.7: the preview effect must not read the state it writes (it looped forever).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import CardPreview from '../../src/lib/cards/CardPreview.svelte';
import { tc } from '../../src/lib/cards/messages';
import { CAT_SEQ, previewCard, stubPreviewFetch, vocabNoteType } from '../support/previews';

// Counts how often the effect reaches the dictionary API layer; past LOOP_CAP the call never settles,
// so a looping effect stops instead of starving the event loop (the test then fails on the count).
const probe = vi.hoisted(() => ({ entryCalls: 0, kanjiCalls: 0 }));
vi.mock('../../src/lib/cards/api', async (importOriginal) => {
	const actual = await importOriginal<typeof import('../../src/lib/cards/api')>();
	const LOOP_CAP_LOCAL = 20;
	return {
		...actual,
		fetchEntry: (seq: number) => {
			probe.entryCalls += 1;
			return probe.entryCalls > LOOP_CAP_LOCAL ? new Promise<never>(() => {}) : actual.fetchEntry(seq);
		},
		fetchKanji: (literal: string) => {
			probe.kanjiCalls += 1;
			return probe.kanjiCalls > LOOP_CAP_LOCAL ? new Promise<never>(() => {}) : actual.fetchKanji(literal);
		}
	};
});

const mounted: Array<ReturnType<typeof mount>> = [];
let target: HTMLElement;

function render(card: ReturnType<typeof previewCard>): HTMLElement {
	mounted.push(mount(CardPreview, { target, props: { card, noteType: vocabNoteType } }));
	flushSync();
	return target;
}

/** Waits until the loading line is gone. Fails (after the timeout) if it never settles. */
async function settle(): Promise<void> {
	const loading = tc('cards.previewLoading');
	await vi.waitFor(() => expect(target.textContent).not.toContain(loading), { timeout: 2000, interval: 5 });
}

beforeEach(() => {
	stubPreviewFetch();
	probe.entryCalls = 0;
	probe.kanjiCalls = 0;
	target = document.createElement('div');
	document.body.append(target);
});

afterEach(() => {
	for (const m of mounted.splice(0)) void unmount(m);
	target.remove();
	vi.unstubAllGlobals();
});

describe('CardPreview bounded effect', () => {
	it('dictionary-backed note: the entry effect runs at most twice and loading settles', async () => {
		render(previewCard({ entrySeq: CAT_SEQ }));
		await settle();
		expect(probe.entryCalls).toBeGreaterThanOrEqual(1);
		expect(probe.entryCalls).toBeLessThanOrEqual(2);
	});

	it('kanji note: the kanji effect runs at most twice and loading settles', async () => {
		render(previewCard({ kanjiLiteral: '日' }));
		await settle();
		expect(probe.kanjiCalls).toBeGreaterThanOrEqual(1);
		expect(probe.kanjiCalls).toBeLessThanOrEqual(2);
	});
});
