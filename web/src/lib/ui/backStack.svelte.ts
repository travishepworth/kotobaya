// Back handling shared by the shell (SHELL.md 6.2): dialogs and sheets register a close handler while open (LIFO), and
// the review screen registers its undo-by-back. `nav.ts` reads both; nothing here knows the bridge.
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

/** Register a close handler for an open dialog or sheet. Returns the unregister function. */
export function registerOverlay(close: () => void): () => void {
	const id = nextId++;
	overlays.push({ id, close });
	return () => {
		const i = overlays.findIndex((o) => o.id === id);
		if (i >= 0) overlays.splice(i, 1);
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
