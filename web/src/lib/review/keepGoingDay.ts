// ADR-037 rule C2 as amended by ADR-039 (one visit, not persisted). One store, used by
// Home and the review screen, so both send the same `keep_going`. Not an op, not a setting, never synced.
import type { ReviewQueue } from '../api/generated';

/** The wire `study_day` ("YYYY-MM-DD"); the generated client parses it as UTC midnight. */
export function studyDayString(d: Date): string {
	return d.toISOString().slice(0, 10);
}

// ADR-039: Keep going lasts one review visit. Held in memory only: gone on app restart and cleared when the
// review screen is left (ReviewPage.dispose), so it stays on across answers within a visit and never longer.
let day: string | null = null;

/** The study day keep going was started on, or null when it is off. */
export function keepGoingDay(): string | null {
	return day;
}

/** The flag every `getReviewQueue` of this client sends. */
export function keepGoingFlag(): boolean {
	return keepGoingDay() !== null;
}

/** Keep going was pressed on a response of `studyDay`. */
export function startKeepGoing(studyDay: Date): void {
	day = studyDayString(studyDay);
}

export function clearKeepGoing(): void {
	day = null;
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
