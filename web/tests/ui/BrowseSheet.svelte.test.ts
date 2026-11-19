// @vitest-environment jsdom
// BUG-ADD2DECK-500: "View note" from a dictionary entry (/notes?note=<id>) on a narrow screen opens the note in
// BrowseLayout's bottom-sheet Dialog; registering that overlay looped (effect_update_depth_exceeded). Same on /cards.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createRawSnippet, flushSync, mount, unmount } from 'svelte';
import BrowseLayout from '../../src/routes/cards/BrowseLayout.svelte';
import { closeTopOverlay, overlayCount } from '../../src/lib/ui/backStack.svelte';

vi.mock('$app/navigation', () => ({ goto: () => Promise.resolve() }));

const mounted: Array<ReturnType<typeof mount>> = [];
let host: HTMLElement;

beforeEach(() => {
	const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
	proto.showModal = function (this: HTMLDialogElement) {
		this.setAttribute('open', '');
	};
	proto.close = function (this: HTMLDialogElement) {
		this.removeAttribute('open');
	};
	// jsdom has no layout: clientWidth is 0, so the frame is "narrow" once the observer fires.
	vi.stubGlobal(
		'ResizeObserver',
		class {
			constructor(private readonly cb: () => void) {}
			observe(): void {
				this.cb();
			}
			disconnect(): void {}
		}
	);
	host = document.createElement('div');
	document.body.append(host);
});

afterEach(() => {
	for (const m of mounted.splice(0)) void unmount(m);
	flushSync();
	host.remove();
	vi.unstubAllGlobals();
});

const snip = (html: string) => createRawSnippet(() => ({ render: () => html }));

describe('note view sheet bounded effects (/notes?note=<id> on a phone)', () => {
	it('opening the linked note in the sheet does not loop, and back closes it', () => {
		const s = $state({ active: false });
		const onclose = vi.fn(() => {
			s.active = false;
		});
		const detail = snip('<p>note</p>');
		mounted.push(
			mount(BrowseLayout, {
				target: host,
				props: {
					view: 'notes',
					toolbar: snip('<div></div>'),
					master: snip('<div></div>'),
					placeholder: snip('<p>pick</p>'),
					get detail() {
						return s.active ? detail : null;
					},
					detailTitle: 'Note',
					onclose
				}
			})
		);
		flushSync();
		expect(overlayCount()).toBe(0);

		s.active = true; // NotesPage.start(linkedNote) picked the note
		expect(() => flushSync()).not.toThrow();
		expect(overlayCount()).toBe(1);
		expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(true);

		expect(closeTopOverlay()).toBe(true);
		expect(() => flushSync()).not.toThrow();
		expect(onclose).toHaveBeenCalledTimes(1);
		expect(overlayCount()).toBe(0);
	});
});
