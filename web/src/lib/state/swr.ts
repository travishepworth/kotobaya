// LF-W2 stale-while-revalidate plumbing shared by the Home/stats stores (ADR-050): one event subscription for the
// whole page life, and the "not downloaded yet" test for `503 shell_uncached` (SHELL.md rule K-3).
import { ResponseError, readProblem } from '../api/client';
import { openEventStream, type ServerEvent } from '../events';

/** `sync.changed` bursts (one per answer) are folded into one refetch this long after the last. */
export const SYNC_DEBOUNCE_MS = 1000;

const subs = new Set<(e: ServerEvent) => void>();
let close: (() => void) | null = null;

function ensureStream(): void {
	if (close) return;
	try {
		close = openEventStream({ types: ['sync.changed', 'cache.updated'], onEvent: (e) => subs.forEach((s) => s(e)) });
	} catch {
		close = null; // no EventSource (tests, very old browsers): stores simply do not live-update
	}
}

/**
 * Call `refetch` on `sync.changed` (debounced 1 s) and on `cache.updated` for one of `operations` (at once: the
 * shell already sends at most one per key per second). Events for other operations are ignored. Returns the
 * unsubscribe function; the stream closes with the last subscriber.
 */
export function revalidateOn(operations: readonly string[], refetch: () => void, debounceMs = SYNC_DEBOUNCE_MS): () => void {
	let timer: ReturnType<typeof setTimeout> | null = null;
	const stop = (): void => {
		if (timer !== null) clearTimeout(timer);
		timer = null;
	};
	const sub = (e: ServerEvent): void => {
		if (e.type === 'sync.changed') {
			stop();
			timer = setTimeout(() => {
				timer = null;
				refetch();
			}, debounceMs);
		} else if (e.type === 'cache.updated' && operations.includes(String(e.data['operation_id'] ?? ''))) {
			refetch();
		}
	};
	subs.add(sub);
	ensureStream();
	return () => {
		stop();
		subs.delete(sub);
		if (subs.size === 0 && close) {
			close();
			close = null;
		}
	};
}

/** `503 shell_uncached`: the answer is not in the device's response cache yet. Not an error: show a placeholder. */
export async function isUncached(e: unknown): Promise<boolean> {
	if (!(e instanceof ResponseError) || e.response.status !== 503) return false;
	return (await readProblem(e.response))?.code === 'shell_uncached';
}
