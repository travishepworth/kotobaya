// @vitest-environment jsdom
// Regression for HOTFIX-0.1.7: the dictionary effect must not read the state it writes (it looped forever,
// and each pass re-ran onentry, which re-rendered the note table).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import NoteEditor from '../../src/lib/notes/NoteEditor.svelte';
import { tn } from '../../src/lib/notes/messages';
import { CAT_SEQ, stubPreviewFetch, vocabNoteType } from '../support/previews';
import type { Note } from '../../src/lib/api/generated';

// Counts how often the effect reaches the dictionary API layer; past 20 calls the call never settles,
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

function note(ref: { entrySeq?: number; kanjiLiteral?: string }): Note {
	return {
		cards: [],
		createdAt: new Date(0),
		createdByDeviceId: '00000000-0000-7000-8000-000000000001',
		deletedAt: null,
		entrySeq: ref.entrySeq ?? null,
		fields: { expression: '', reading: '', meaning: '' },
		grammarPointId: null,
		id: '00000000-0000-7000-8000-0000000000aa',
		kanjiLiteral: ref.kanjiLiteral ?? null,
		language: 'ja',
		mergedIntoId: null,
		mnemonic: '',
		noteTypeId: vocabNoteType.id,
		origin: 'manual',
		sourceMediaId: null,
		sourceSentence: '',
		sourceSentenceId: null,
		tags: [],
		type: 'vocab',
		updatedAt: new Date(0)
	};
}

function render(n: Note, onentry: (seq: number, entry: unknown) => void): void {
	mounted.push(
		mount(NoteEditor, {
			target,
			props: { note: n, noteType: vocabNoteType, onentry, onsaved: () => {}, ondeleted: () => {} }
		})
	);
	flushSync();
}

/** Waits until the dictionary-loading line is gone. Fails (after the timeout) if it never settles. */
async function settle(): Promise<void> {
	const loading = tn('notes.dictLoading');
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

describe('NoteEditor bounded effect', () => {
	it('dictionary-backed note: the entry effect and onentry run a bounded number of times, loading settles', async () => {
		const onentry = vi.fn();
		render(note({ entrySeq: CAT_SEQ }), onentry);
		await settle();
		expect(probe.entryCalls).toBeGreaterThanOrEqual(1);
		expect(probe.entryCalls).toBeLessThanOrEqual(2);
		expect(onentry.mock.calls.length).toBeGreaterThanOrEqual(1);
		expect(onentry.mock.calls.length).toBeLessThanOrEqual(2);
	});

	it('kanji note: the kanji effect runs a bounded number of times and loading settles', async () => {
		render(note({ kanjiLiteral: '日' }), () => {});
		await settle();
		expect(probe.kanjiCalls).toBeGreaterThanOrEqual(1);
		expect(probe.kanjiCalls).toBeLessThanOrEqual(2);
	});
});
