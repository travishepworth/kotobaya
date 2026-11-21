// LF-W1: the outbox store keeps records across "reloads" (a new store over the same database), in `addedAt` order,
// and falls back to memory when IndexedDB cannot be opened.
import { describe, expect, it, vi } from 'vitest';
import {
	OUTBOX_STORE,
	idbOutboxStore,
	memoryOutboxStore,
	nextAddedAt,
	resetAddedAtForTests,
	seedAddedAt,
	type OutboxRecord
} from '../../src/lib/review/outboxStore';

/**
 * Just enough of IndexedDB for the store: one database of keyPath stores; requests and transactions complete
 * asynchronously, in creation order, with structured-cloned values. Each open is its own connection: `upgrade()`
 * fires `versionchange` on every open one (the store must close), and a closed connection's `transaction` throws.
 * `blockFor` ms: an open reports `blocked` first and only then succeeds (Infinity: never). `failOpensAfter`: opens
 * after that many fail.
 */
function fakeIndexedDB(opts: { failOpen?: boolean; blockFor?: number; failOpensAfter?: number } = {}) {
	const data = new Map<string, Map<string, unknown>>();
	let version = 0;
	let opens = 0;
	const later = (f: () => void, ms = 0) => setTimeout(f, ms);
	function request<T>(run: () => T) {
		const r: { result?: T; error: Error | null; onsuccess?: () => void; onerror?: () => void } = { error: null };
		later(() => {
			r.result = run();
			r.onsuccess?.();
		});
		return r;
	}
	interface Conn {
		closed: boolean;
		onversionchange: (() => void) | null;
	}
	const conns: Conn[] = [];
	function connection() {
		const c = {
			closed: false,
			onversionchange: null as (() => void) | null,
			close: () => {
				c.closed = true;
			},
			objectStoreNames: { contains: (n: string) => data.has(n) },
			createObjectStore: (n: string) => {
				data.set(n, new Map());
			},
			transaction: (name: string) => {
				if (c.closed) throw Object.assign(new Error('The database connection is closing.'), { name: 'InvalidStateError' });
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
		conns.push(c);
		return c;
	}
	const factory = {
		open: (_name: string, v?: number) => {
			opens++;
			const c = connection();
			const r: { result: typeof c; error: Error | null; onupgradeneeded?: () => void; onsuccess?: () => void; onerror?: () => void; onblocked?: () => void } = {
				result: c,
				error: null
			};
			const finish = () => {
				if (opts.failOpen || (opts.failOpensAfter !== undefined && opens > opts.failOpensAfter)) {
					r.error = new Error('InvalidStateError');
					r.onerror?.();
					return;
				}
				if (v !== undefined && v < version) {
					r.error = Object.assign(new Error('VersionError'), { name: 'VersionError' });
					r.onerror?.();
					return;
				}
				if ((v ?? Math.max(version, 1)) > version) {
					version = v ?? 1;
					r.onupgradeneeded?.();
				}
				r.onsuccess?.();
			};
			later(() => {
				if (opts.blockFor === undefined) return finish();
				r.onblocked?.();
				if (opts.blockFor !== Infinity) later(finish, opts.blockFor);
			});
			return r;
		}
	};
	/** Another tab upgrades the database: every open connection gets `versionchange`. */
	function upgrade(): void {
		version++;
		for (const c of conns) if (!c.closed) c.onversionchange?.();
	}
	return { factory: factory as unknown as IDBFactory, data, conns: conns as Conn[], upgrade };
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

	it('seedAddedAt: after a clock that went backward, the next addedAt is still above every stored one', () => {
		resetAddedAtForTests(); // a fresh page
		const stored = Date.now() + 3_600_000; // written while the clock was an hour ahead
		seedAddedAt(stored);
		expect(nextAddedAt()).toBeGreaterThan(stored);
		seedAddedAt(-Infinity); // nothing stored: no effect
		expect(nextAddedAt()).toBeGreaterThan(stored);
	});

	it('another tab upgrading the database: the connection closes on versionchange and the next call reopens', async () => {
		const { factory, data, conns, upgrade } = fakeIndexedDB();
		const s = idbOutboxStore(factory);
		await s.put(rec('x', 1));
		upgrade();
		expect(conns[0].closed).toBe(true); // never left blocking the other tab's upgrade
		await s.put(rec('y', 2));
		expect(s.persistent).toBe(true);
		expect([...(data.get(OUTBOX_STORE)?.keys() ?? [])].sort()).toEqual(['x', 'y']);
	});

	it('a transaction that cannot start and cannot reopen: falls back to memory and reports not persistent', async () => {
		const { factory, conns } = fakeIndexedDB({ failOpensAfter: 1 });
		const s = idbOutboxStore(factory);
		await s.put(rec('x', 1));
		expect(s.persistent).toBe(true);
		conns[0].closed = true; // closed under us (no versionchange)
		await s.put(rec('y', 2));
		expect(s.persistent).toBe(false); // the page now warns before a close
		expect((await s.all()).map((r) => r.id)).toEqual(['y']);
	});

	it('an open reported blocked is waited for, not treated as failure', async () => {
		const { factory } = fakeIndexedDB({ blockFor: 50 });
		const s = idbOutboxStore(factory);
		await s.put(rec('x', 1));
		expect(s.persistent).toBe(true);
		expect((await idbOutboxStore(factory).all()).map((r) => r.id)).toEqual(['x']);
	});

	it('an open blocked for longer than the timeout falls back to memory', async () => {
		vi.useFakeTimers();
		try {
			const { factory } = fakeIndexedDB({ blockFor: Infinity });
			const s = idbOutboxStore(factory, { blockedTimeoutMs: 1000 });
			const p = s.put(rec('x', 1));
			await vi.advanceTimersByTimeAsync(1000);
			await p;
			expect(s.persistent).toBe(false);
			expect(await s.all()).toHaveLength(1);
		} finally {
			vi.useRealTimers();
		}
	});

	it('a database already upgraded past our version by another tab is opened at its current version', async () => {
		const { factory, data, upgrade } = fakeIndexedDB();
		await idbOutboxStore(factory).put(rec('x', 1)); // creates v1
		upgrade(); // now v2
		const s = idbOutboxStore(factory);
		await s.put(rec('y', 2));
		expect(s.persistent).toBe(true);
		expect(data.get(OUTBOX_STORE)?.size).toBe(2);
	});
});
