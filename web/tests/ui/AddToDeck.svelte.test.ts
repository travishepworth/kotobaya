// @vitest-environment jsdom
// BUG-ADD2DECK-500: clicking "Add to deck" on /dictionary threw effect_update_depth_exceeded (SvelteKit showed "500").
// Cause: Dialog's open-effect called registerOverlay, whose `overlays.push` read and wrote the reactive overlay
// stack inside the effect, so the effect invalidated itself forever. Every Dialog (settings, notes, ...) shared it.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createRawSnippet, flushSync, mount, unmount } from 'svelte';
import AddToDeck from '../../src/lib/ui/AddToDeck.svelte';
import Dialog from '../../src/lib/ui/Dialog.svelte';
import { closeTopOverlay, overlayCount } from '../../src/lib/ui/backStack.svelte';
import { ut } from '../../src/lib/ui/messages';
import type { Deck } from '../../src/lib/api/generated';

const calls = vi.hoisted(() => ({ list: 0, items: 0, add: 0, remove: 0 }));
vi.mock('../../src/lib/decks/api', () => ({
	decksApi: {
		listDecks: () => {
			calls.list += 1;
			const deck = { id: '00000000-0000-7000-8000-0000000000d1', type: 'user', name: 'Core', priority: 0, deletedAt: null };
			return Promise.resolve({ items: [deck as unknown as Deck] });
		},
		listDeckItems: () => {
			calls.items += 1;
			return Promise.resolve({ items: [], nextCursor: null });
		},
		addDeckItems: () => {
			calls.add += 1;
			return Promise.resolve({});
		},
		removeDeckItem: () => {
			calls.remove += 1;
			return Promise.resolve({});
		},
		createDeck: () => Promise.reject(new Error('unused'))
	}
}));

const mounted: Array<ReturnType<typeof mount>> = [];
let host: HTMLElement;

beforeEach(() => {
	Object.assign(calls, { list: 0, items: 0, add: 0, remove: 0 });
	// jsdom's <dialog> may lack the modal API.
	const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
	proto.showModal = function (this: HTMLDialogElement) {
		this.setAttribute('open', '');
	};
	proto.close = function (this: HTMLDialogElement) {
		this.removeAttribute('open');
	};
	host = document.createElement('div');
	document.body.append(host);
});

afterEach(() => {
	for (const m of mounted.splice(0)) void unmount(m);
	flushSync();
	host.remove();
});

function buttonByText(text: string): HTMLButtonElement {
	const b = [...host.querySelectorAll('button')].find((x) => x.textContent?.includes(text));
	if (!b) throw new Error(`no button "${text}"`);
	return b;
}

describe('AddToDeck bounded effects (/dictionary add-to-deck)', () => {
	it('opening the picker and toggling membership does not loop', async () => {
		mounted.push(mount(AddToDeck, { target: host, props: { target: { entrySeq: 1234, noteId: null } } }));
		flushSync();
		await vi.waitFor(() => expect(calls.items).toBe(1), { timeout: 1000, interval: 5 });

		buttonByText(ut('ui.deck.add')).click();
		expect(() => flushSync()).not.toThrow();
		expect(overlayCount()).toBe(1);

		const row = host.querySelector<HTMLButtonElement>('button[role="checkbox"]');
		expect(row).not.toBeNull();
		row?.click();
		await vi.waitFor(() => expect(row?.getAttribute('aria-checked')).toBe('true'), { timeout: 1000, interval: 5 });
		expect(calls.add).toBe(1);
		row?.click();
		await vi.waitFor(() => expect(row?.getAttribute('aria-checked')).toBe('false'), { timeout: 1000, interval: 5 });
		expect(calls.remove).toBe(1);
		// The load effect ran once: toggling does not re-trigger it.
		expect(calls.list).toBe(1);

		buttonByText(ut('ui.deck.done')).click();
		expect(() => flushSync()).not.toThrow();
		expect(overlayCount()).toBe(0);
	});
});

describe('Dialog overlay registration (every route with a dialog)', () => {
	it('open, back-close and reopen keep the overlay stack bounded', () => {
		const s = $state({ open: false });
		const children = createRawSnippet(() => ({ render: () => '<p>body</p>' }));
		mounted.push(
			mount(Dialog, {
				target: host,
				props: {
					get open() {
						return s.open;
					},
					set open(v: boolean) {
						s.open = v;
					},
					title: 't',
					children
				}
			})
		);
		flushSync();
		expect(overlayCount()).toBe(0);
		s.open = true;
		expect(() => flushSync()).not.toThrow();
		expect(overlayCount()).toBe(1);
		expect(closeTopOverlay()).toBe(true);
		expect(() => flushSync()).not.toThrow();
		expect(s.open).toBe(false);
		expect(overlayCount()).toBe(0);
		s.open = true;
		expect(() => flushSync()).not.toThrow();
		expect(overlayCount()).toBe(1);
	});
});
