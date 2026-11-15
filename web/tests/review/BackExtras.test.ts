// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import BackExtras from '../../src/routes/review/BackExtras.svelte';
import type { QueueItem } from '../../src/lib/api/generated';

const mounted: Array<ReturnType<typeof mount>> = [];
afterEach(() => {
	mounted.splice(0).forEach((m) => unmount(m));
	document.body.innerHTML = '';
});

function render(item: unknown): HTMLElement {
	const target = document.body.appendChild(document.createElement('div'));
	mounted.push(mount(BackExtras, { target, props: { item: item as QueueItem } }));
	flushSync();
	return target;
}

describe('review back extras', () => {
	it('renders kanji example words with tags and dictionary links', () => {
		const t = render({
			card: { note: { kanjiLiteral: '日' } },
			kanjiExamples: [
				{ seq: 1, headword: '日本', reading: 'にほん', gloss: 'Japan', readingType: 'on' },
				{ seq: 2, headword: '日', reading: 'ひ', gloss: 'sun; day', readingType: 'kun' },
				{ seq: 3, headword: '日曜', reading: 'にちよう', gloss: 'Sunday', readingType: 'other' }
			]
		});
		const links = [...t.querySelectorAll('a')];
		expect(links.map((a) => a.querySelector('.hw')?.textContent)).toEqual(['日本', '日', '日曜']);
		expect(links[0].getAttribute('href')).toContain('1');
		expect(links[0].textContent).toContain('ON');
		expect(links[1].textContent).toContain('KUN');
		expect(links[2].querySelector('.chip')).toBeNull();
	});

	it('renders deck chips with +N more for a vocab card', () => {
		const t = render({
			card: { note: { kanjiLiteral: null } },
			decks: [
				{ id: 'a', name: 'Core', priority: 0 },
				{ id: 'b', name: 'Food', priority: 1 },
				{ id: 'c', name: 'Travel', priority: 2 }
			],
			deckCount: 5
		});
		const links = [...t.querySelectorAll('a')];
		expect(links.map((a) => a.textContent)).toEqual(['Core', 'Food', 'Travel']);
		expect(links[0].getAttribute('href')).toBe('/decks/a');
		expect(t.textContent).toContain('+2 more');
	});

	it('renders nothing when the fields are absent', () => {
		expect(render({ card: { note: { kanjiLiteral: null } } }).querySelector('ul')).toBeNull();
	});
});
