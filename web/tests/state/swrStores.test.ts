// @vitest-environment jsdom
// LF-W2: stores keep their data while refetching, refetch only for their own `cache.updated`, and a
// `503 shell_uncached` is a placeholder (no toast) that fills after the event.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerEvent } from '../../src/lib/events';

const bus = vi.hoisted(() => ({ emit: (_e: unknown): void => {} }));
vi.mock('../../src/lib/events', () => ({
	openEventStream: (o: { onEvent: (e: unknown) => void }) => {
		bus.emit = o.onEvent;
		return () => {};
	}
}));
const toast = vi.hoisted(() => vi.fn());
vi.mock('../../src/lib/toasts.svelte', () => ({ pushToast: toast }));

import { DecksListPage } from '../../src/lib/state/decks.svelte';
import { revalidateOn } from '../../src/lib/state/swr';

const event = (type: string, data: Record<string, unknown> = {}): ServerEvent => ({ type: type as never, at: '2026-11-21T00:00:00.000Z', data });
const cacheUpdated = (operation_id: string) => event('cache.updated', { operation_id, path: '/api/v1/x', query: '', status: 200 });
const json = (body: unknown, status = 200) =>
	new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status === 200 ? 'application/json' : 'application/problem+json' } });
const deck = (id: string) => ({
	id,
	name: id,
	type: 'user',
	priority: 1,
	itemCount: 1,
	createdAt: '2026-01-01T00:00:00Z',
	updatedAt: '2026-01-01T00:00:00Z'
});

beforeEach(() => {
	vi.useFakeTimers();
	toast.mockReset();
});
afterEach(() => {
	vi.useRealTimers();
	vi.unstubAllGlobals();
});

describe('revalidateOn', () => {
	it('sync.changed is debounced 1 s; cache.updated of another operation is ignored, its own is not', () => {
		const refetch = vi.fn();
		const off = revalidateOn(['listDecks'], refetch);
		bus.emit(event('sync.changed'));
		bus.emit(event('sync.changed'));
		vi.advanceTimersByTime(999);
		expect(refetch).not.toHaveBeenCalled();
		vi.advanceTimersByTime(2);
		expect(refetch).toHaveBeenCalledTimes(1);
		bus.emit(cacheUpdated('getStatsForecast'));
		expect(refetch).toHaveBeenCalledTimes(1);
		bus.emit(cacheUpdated('listDecks'));
		expect(refetch).toHaveBeenCalledTimes(2);
		off();
	});
});

describe('DecksListPage stale-while-revalidate', () => {
	it('a refetch does not blank a loaded list, and cache.updated for another operation does not refetch it', async () => {
		let release: (r: Response) => void = () => {};
		const fetchMock = vi
			.fn()
			.mockResolvedValueOnce(json({ items: [deck('d1')] }))
			.mockImplementationOnce(() => new Promise<Response>((r) => (release = r)));
		vi.stubGlobal('fetch', fetchMock);
		const page = new DecksListPage();
		const off = page.watch();
		page.start();
		await vi.advanceTimersByTimeAsync(0);
		expect(page.items.map((d) => d.id)).toEqual(['d1']);
		expect(page.loaded).toBe(true);

		bus.emit(cacheUpdated('getStatsForecast'));
		await vi.advanceTimersByTimeAsync(0);
		expect(fetchMock).toHaveBeenCalledTimes(1);

		bus.emit(cacheUpdated('listDecks'));
		await vi.advanceTimersByTimeAsync(0);
		expect(fetchMock).toHaveBeenCalledTimes(2);
		expect(page.loaded).toBe(true); // no skeleton on a refetch
		expect(page.items.map((d) => d.id)).toEqual(['d1']); // still showing
		release(json({ items: [deck('d1'), deck('d2')] }));
		await vi.advanceTimersByTimeAsync(0);
		expect(page.items.map((d) => d.id)).toEqual(['d1', 'd2']);
		off();
	});

	it('shell_uncached is a placeholder with no toast; the widget fills after cache.updated', async () => {
		const problem = { type: 'about:blank', title: 'x', status: 503, code: 'shell_uncached' };
		const fetchMock = vi
			.fn()
			.mockResolvedValueOnce(json(problem, 503))
			.mockResolvedValueOnce(json({ items: [deck('d1')] }));
		vi.stubGlobal('fetch', fetchMock);
		const page = new DecksListPage();
		const off = page.watch();
		page.start();
		await vi.advanceTimersByTimeAsync(0);
		expect(page.uncached).toBe(true);
		expect(page.loadFailed).toBe(false);
		expect(page.items).toEqual([]);
		expect(toast).not.toHaveBeenCalled();

		bus.emit(cacheUpdated('listDecks'));
		await vi.advanceTimersByTimeAsync(0);
		expect(page.uncached).toBe(false);
		expect(page.items.map((d) => d.id)).toEqual(['d1']);
		off();
	});
});
