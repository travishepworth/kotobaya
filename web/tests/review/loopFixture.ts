// Test helpers: ADR-037 `ReviewQueue` responses with a `loop` (the server's numbers; nothing computed here).
import type { QueueItem, ReviewLoop, ReviewQueue } from '../../src/lib/api/generated';

export interface LoopOver {
	phase?: ReviewLoop['phase'];
	keepGoing?: boolean;
	ready?: { learning?: number; review?: number };
	later?: { count: number; nextDueAt: Date | null };
	backlog?: { reviews: number; beyondLimit: number };
	reviews?: { today: number; limit: number | null };
	newCards?: { today?: number; goal?: number; available?: boolean };
}

export function loop(o: LoopOver = {}): ReviewLoop {
	const learning = o.ready?.learning ?? 0;
	const review = o.ready?.review ?? 0;
	return {
		phase: o.phase ?? 'empty',
		keepGoing: o.keepGoing ?? false,
		ready: { learning, review, total: learning + review },
		laterToday: o.later ?? { count: 0, nextDueAt: null },
		backlog: o.backlog ?? { reviews: review, beyondLimit: 0 },
		reviews: o.reviews ?? { today: 0, limit: 200 },
		_new: { today: o.newCards?.today ?? 0, goal: o.newCards?.goal ?? 40, available: o.newCards?.available ?? false }
	};
}

/**
 * A response whose deprecated fields throw when read: a client built for ADR-037 never reads `counts`,
 * `more_available` or `next_learning_due_at` while `loop` is present.
 */
export function queue(o: { items?: QueueItem[]; loop?: LoopOver; studyDay?: string; toMaterialize?: number } = {}): ReviewQueue {
	const items = o.items ?? [];
	const q = {
		items,
		studyDay: new Date(`${o.studyDay ?? '2026-11-17'}T00:00:00Z`),
		dayEndsAt: new Date(Date.now() + 10 * 3600_000),
		generatedAt: new Date(),
		toMaterialize: o.toMaterialize ?? 0,
		loop: loop({ phase: items.length > 0 ? 'reviewing' : undefined, ...o.loop })
	};
	for (const k of ['counts', 'moreAvailable', 'nextLearningDueAt']) {
		Object.defineProperty(q, k, {
			enumerable: true,
			get() {
				throw new Error(`read deprecated ReviewQueue.${k}`);
			}
		});
	}
	return q as unknown as ReviewQueue;
}

export const card = (id: string, extra: Record<string, unknown> = {}): QueueItem =>
	({ card: { id, presetId: 'p' }, reviewType: 'schedule', ...extra }) as unknown as QueueItem;
