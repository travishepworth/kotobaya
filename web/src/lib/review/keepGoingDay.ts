// ADR-037 rule C2: Keep going is persisted per client for the study day it was started in. One store, used by
// Home and the review screen, so both send the same `keep_going`. Not an op, not a setting, never synced.
import type { ReviewQueue } from '../api/generated';

export const KEEP_GOING_KEY = 'kotobaya.keepGoingDay';

/** The wire `study_day` ("YYYY-MM-DD"); the generated client parses it as UTC midnight. */
export function studyDayString(d: Date): string {
	return d.toISOString().slice(0, 10);
}

function storage(): Storage | null {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage;
	} catch {
		return null; // storage disabled: keep going lasts for nothing beyond the request it was pressed on
	}
}

/** The stored study day, or null when keep going is off. */
export function keepGoingDay(): string | null {
	return storage()?.getItem(KEEP_GOING_KEY) ?? null;
}

/** The flag every `getReviewQueue` of this client sends. */
export function keepGoingFlag(): boolean {
	return keepGoingDay() !== null;
}

/** Keep going was pressed on a response of `studyDay`. */
export function startKeepGoing(studyDay: Date): void {
	storage()?.setItem(KEEP_GOING_KEY, studyDayString(studyDay));
}

export function clearKeepGoing(): void {
	storage()?.removeItem(KEEP_GOING_KEY);
}

/**
 * Fetch with the persisted flag. When keep going is stored for another study day than the response's, it is
 * dropped and the queue fetched again with `keep_going=false` (the mismatching response is discarded).
 */
export async function fetchWithKeepGoing(get: (keepGoing: boolean) => Promise<ReviewQueue>): Promise<ReviewQueue> {
	const day = keepGoingDay();
	const q = await get(day !== null);
	if (day === null || day === studyDayString(q.studyDay)) return q;
	// Only drop what this fetch read: a Keep going pressed meanwhile (another tab, same day) stays.
	if (keepGoingDay() === day) clearKeepGoing();
	return get(keepGoingFlag());
}
