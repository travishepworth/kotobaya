// The review session outlives the route (ADR-048): following a link out of a review card and coming back (back
// button, mouse back, Android back) restores the same card, side and queue instead of fetching a new one.
// The history entry of /review carries `reviewKey` (page state); only a return to that very entry restores.
import { uuidv7 } from '../review/model';
import type { ReviewPage } from './review.svelte';

interface Kept {
	page: ReviewPage;
	source: string | null;
	key: string;
}

let kept: Kept | null = null;

/** The route is left: keep the (suspended) session for a possible return through history. */
export function keepSession(page: ReviewPage, source: string | null, key: string): void {
	if (kept && kept.page !== page) kept.page.release();
	kept = { page, source, key };
}

/** A /review view opens: the kept session when this history entry and drill source match, else nothing (and the old one is dropped). */
export function takeSession(source: string | null, key: string | undefined): ReviewPage | null {
	const k = kept;
	kept = null;
	if (!k) return null;
	if (key !== undefined && k.key === key && k.source === source) return k.page;
	k.page.release();
	return null;
}

/** A fresh key for a new review history entry. */
export function newSessionKey(): string {
	return uuidv7();
}
