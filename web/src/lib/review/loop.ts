// ADR-037 review loop on the client: the phase and every number come from `ReviewQueue.loop` (core
// `ReviewLoop.evaluate`). Nothing here counts cards or decides a phase; this file only reads the wire.
import type { ReviewLoop, ReviewQueue } from '../api/generated';
import { ReviewLoopPhaseEnum } from '../api/generated';

export type LoopPhase = ReviewLoopPhaseEnum;
export type { ReviewLoop };

export interface LoopView {
	/** `loop.phase`; an unknown value is `reviewing` with items, else `empty` (openapi C4). */
	phase: LoopPhase;
	loop: ReviewLoop;
	studyDay: Date;
	/**
	 * The server sent no `loop` (older than ADR-037). The contract treats that as "server too old"; this client
	 * still shows a degraded screen built from the deprecated fields (see `legacyLoop`) instead of breaking, and
	 * hides the numbers that do not exist there (later-today hint, new bar, backlog line).
	 */
	legacy: boolean;
}

const PHASES = new Set<string>(Object.values(ReviewLoopPhaseEnum));

export function loopView(q: ReviewQueue): LoopView {
	if (!q.loop) return { phase: legacyPhase(q), loop: legacyLoop(q), studyDay: q.studyDay, legacy: true };
	const phase = PHASES.has(q.loop.phase) ? q.loop.phase : q.items.length > 0 ? 'reviewing' : 'empty';
	return { phase, loop: q.loop, studyDay: q.studyDay, legacy: false };
}

/**
 * Degraded mode for a server without `loop` (pre-ADR-037). The only place the deprecated fields are read; it maps
 * them one to one (no counting): the old screens' meaning ("done for now" = more_available, waiting =
 * next_learning_due_at).
 */
function legacyPhase(q: ReviewQueue): LoopPhase {
	if (q.items.length > 0 || (q.toMaterialize ?? 0) > 0) return 'reviewing';
	if (q.nextLearningDueAt) return 'waiting';
	if (q.moreAvailable) return 'done_for_today';
	const c = q.counts;
	return c && c._new + c.learning + c.review > 0 ? 'reviewing' : 'empty';
}

function legacyLoop(q: ReviewQueue): ReviewLoop {
	const c = q.counts ?? { _new: 0, learning: 0, review: 0 };
	const next = q.nextLearningDueAt ?? null;
	return {
		phase: legacyPhase(q),
		keepGoing: false,
		ready: { learning: c.learning, review: c.review, total: c.learning + c.review },
		laterToday: { count: next ? 1 : 0, nextDueAt: next },
		backlog: { reviews: c.review, beyondLimit: 0 },
		reviews: { today: 0, limit: null },
		_new: { today: 0, goal: 0, available: c._new > 0 || (q.moreAvailable ?? false) }
	};
}

/** Whole minutes until `at` (at least 1), for the "next in 6 min" hint. */
export function minutesUntil(at: Date, now: number): number {
	return Math.max(1, Math.ceil((at.getTime() - now) / 60_000));
}

/** "m:ss" until `at` (never negative), for the waiting countdown. */
export function countdown(at: Date | null, now: number): string {
	const sec = at ? Math.max(0, Math.ceil((at.getTime() - now) / 1000)) : 0;
	return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
}
