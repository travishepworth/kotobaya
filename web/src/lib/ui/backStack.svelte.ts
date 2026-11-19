// Back handling shared by the shell (SHELL.md 6.2): dialogs and sheets register a close handler while open (LIFO), and
// the review screen registers its undo-by-back. `nav.ts` reads both; nothing here knows the bridge.
import { untrack } from 'svelte';

export interface ReviewBack {
	/** Would back undo an answer right now (no side effects). */
	canUndo(): boolean;
	/** Undo the last answer; false when nothing was undone. */
	undo(): boolean;
}

const overlays: { id: number; close: () => void }[] = $state([]);
let nextId = 1;

/** Number of open overlays. Reactive. */
export function overlayCount(): number {
	return overlays.length;
}

/**
 * Register a close handler for an open dialog or sheet. Returns the unregister function.
 * Callers register from inside an `$effect`; `push`/`splice` read `overlays.length` before writing it, so both run
 * untracked, else the effect depends on the stack it writes and loops (BUG-ADD2DECK-500, effect_update_depth_exceeded).
 */
export function registerOverlay(close: () => void): () => void {
	const id = nextId++;
	untrack(() => overlays.push({ id, close }));
	return () => {
		untrack(() => {
			const i = overlays.findIndex((o) => o.id === id);
			if (i >= 0) overlays.splice(i, 1);
		});
	};
}

/** Close the most recently opened overlay; false when none is open. */
export function closeTopOverlay(): boolean {
	const top = overlays[overlays.length - 1];
	if (!top) return false;
	top.close();
	return true;
}

export const reviewBack = $state<{ handler: ReviewBack | null }>({ handler: null });

/** The review screen registers while mounted. Returns the unregister function. */
export function registerReviewBack(h: ReviewBack): () => void {
	reviewBack.handler = h;
	return () => {
		if (reviewBack.handler === h) reviewBack.handler = null;
	};
}

// Views import shell navigation through lib/ui (view import rule).
export { installNav, intakeFromSearch, reportBackState } from '../api/shell/nav';
export type { BackEnv } from '../api/shell/nav';
