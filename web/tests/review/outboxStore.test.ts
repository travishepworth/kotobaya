// LF-W1: the outbox store keeps records across "reloads" (a new store over the same database), in `addedAt` order,
// and falls back to memory when IndexedDB cannot be opened.
import { describe, expect, it } from 'vitest';
import {
	OUTBOX_STORE,
	idbOutboxStore,
	memoryOutboxStore,
	nextAddedAt,
	type OutboxRecord
} from '../../src/lib/review/outboxStore';

/**
 * Just enough of IndexedDB for the store: one database of keyPath stores; requests and transactions complete
 * asynchronously, in creation order, with structured-cloned values.
 */
function fakeIndexedDB(opts: { failOpen?: boolean } = {}) {
	const data = new Map<string, Map<string, unknown>>();
	let version = 0;
	const later = (f: () => void) => setTimeout(f, 0);
	function request<T>(run: () => T) {
		const r: { result?: T; error: Error | null; onsuccess?: () => void; onerror?: () => void } = { error: null };
		later(() => {
			r.result = run();
			r.onsuccess?.();
		});
		return r;
	}
	const db = {
		objectStoreNames: { contains: (n: string) => data.has(n) },
		createObjectStore: (n: string) => {
			data.set(n, new Map());
		},
		transaction: (name: string) => {
			const m = data.get(name);
			if (!m) throw new Error('NotFoundError');
			const tx: { oncomplete?: () => void; onerror?: () => void; onabort?: () => void; error: null; objectStore: () => unknown } = {
				error: null,
				objectStore: () => ({
					getAll: () => request(() => [...m.values()].map((v) => structuredClone(v))),
					put: (v: { id: string }) => request(() => (m.set(v.id, structuredClone(v)), v.id)),
					delete: (k: string) => request(() => void m.delete(k)),
					clear: () => request(() => m.clear())
				})
			};
			later(() => later(() => tx.oncomplete?.()));
			return tx;
		}
	};
	const factory = {
		open: (_name: string, v: number) => {
			const r: { result: typeof db; error: Error | null; onupgradeneeded?: () => void; onsuccess?: () => void; onerror?: () => void; onblocked?: () => void } = {
				result: db,
				error: null
			};
			later(() => {
				if (opts.failOpen) {
					r.error = new Error('InvalidStateError');
					r.onerror?.();
					return;
				}
				if (v > version) {
					version = v;
					r.onupgradeneeded?.();
				}
				r.onsuccess?.();
			});
			return r;
		}
	};
	return { factory: factory as unknown as IDBFactory, data };
}

const rec = (id: string, addedAt: number): OutboxRecord => ({
	id,
	op: 'answer',
	cardId: `card-${id}`,
	addedAt,
	attempts: 0,
	kind: 'review',
	resultingState: 'review',
	dueAt: null,
	req: { id, cardId: `card-${id}`, rating: 3, reviewedAt: new Date(addedAt) } as never
});

describe('outbox store (LF-W1)', () => {
	it('IndexedDB: records survive a new store over the same database, in addedAt order, Dates intact', async () => {
		const { factory, data } = fakeIndexedDB();
		const a = idbOutboxStore(factory);
		await a.put(rec('x', 30));
		await a.put(rec('y', 10));
		await a.put(rec('z', 20));
		await a.delete('z');
		expect(a.persistent).toBe(true);
		expect(data.get(OUTBOX_STORE)?.size).toBe(2);
		const b = idbOutboxStore(factory); // the reload
		const all = await b.all();
		expect(all.map((r) => r.id)).toEqual(['y', 'x']);
		expect((all[0].req as { reviewedAt: Date }).reviewedAt).toBeInstanceOf(Date);
		await b.clear();
		expect(await idbOutboxStore(factory).all()).toEqual([]);
	});

	it('a put then a delete of the same key, not awaited in between, ends deleted', async () => {
		const { factory } = fakeIndexedDB();
		const s = idbOutboxStore(factory);
		const p = s.put(rec('x', 1));
		const d = s.delete('x');
		await Promise.all([p, d]);
		expect(await s.all()).toEqual([]);
	});

	it('IndexedDB that cannot be opened falls back to memory (not persistent)', async () => {
		const { factory } = fakeIndexedDB({ failOpen: true });
		const s = idbOutboxStore(factory);
		await s.put(rec('x', 1));
		expect(await s.all()).toHaveLength(1);
		expect(s.persistent).toBe(false);
	});

	it('memory store: order, delete, clear; returned records are copies', async () => {
		const s = memoryOutboxStore();
		await s.put(rec('b', 2));
		await s.put(rec('a', 1));
		const all = await s.all();
		expect(all.map((r) => r.id)).toEqual(['a', 'b']);
		all[0].attempts = 99;
		expect((await s.all())[0].attempts).toBe(0);
		await s.delete('a');
		expect((await s.all()).map((r) => r.id)).toEqual(['b']);
		await s.clear();
		expect(await s.all()).toEqual([]);
	});

	it('nextAddedAt is strictly increasing within one millisecond', () => {
		const xs = Array.from({ length: 5 }, () => nextAddedAt());
		for (let i = 1; i < xs.length; i++) expect(xs[i]).toBeGreaterThan(xs[i - 1]);
	});
});
