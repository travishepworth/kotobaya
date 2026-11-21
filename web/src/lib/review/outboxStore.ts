// LF-W1 / openapi rule C8: the persisted review outbox. Every grade tap (and every undo of an answer the server may
// already have) is written here before it is sent, and deleted only on a `2xx` or a final refusal, so a reload or a
// lost connection never loses an answer. IndexedDB database `kotobaya`, object store `outbox`, key `id`; when
// IndexedDB is missing or cannot be opened (some private modes) an in-memory store stands in for the page's life.
import type { ReviewAnswerRequest, ReviewUndoRequest } from '../api/generated';
import type { CardState, QueueItemKind } from '../core/index';

export const OUTBOX_DB = 'kotobaya';
export const OUTBOX_STORE = 'outbox';
const OUTBOX_DB_VERSION = 1;

interface RecordBase {
	/** Key. The answer's own `id` for an answer; `undo:<review id>` for an undo (it may sit behind its answer). */
	id: string;
	cardId: string;
	/** Insertion order: strictly increasing within a page life (see `nextAddedAt`). */
	addedAt: number;
	/** Sends tried so far. More than 0 means the server may already have it (a response can be lost). */
	attempts: number;
}

export interface AnswerRecord extends RecordBase {
	op: 'answer';
	req: ReviewAnswerRequest;
	/** The header part the card was counted in (C7 `pending`). */
	kind: QueueItemKind;
	/** The scheduler preview at the tap (C7 `pending` state/due until the response says otherwise). */
	resultingState: CardState | null;
	dueAt: number | null;
}

export interface UndoRecord extends RecordBase {
	op: 'undo';
	req: ReviewUndoRequest;
}

export type OutboxRecord = AnswerRecord | UndoRecord;

export interface OutboxStore {
	/** False for the in-memory fallback (a tab close loses what it holds). */
	readonly persistent: boolean;
	/** Every record, in `addedAt` order. */
	all(): Promise<OutboxRecord[]>;
	put(r: OutboxRecord): Promise<void>;
	delete(id: string): Promise<void>;
	clear(): Promise<void>;
}

let lastAddedAt = 0;

/** `Date.now()`, but never equal to or below the previous value handed out (two taps in one millisecond). */
export function nextAddedAt(): number {
	lastAddedAt = Math.max(Date.now(), lastAddedAt + 1);
	return lastAddedAt;
}

/**
 * Raise the floor of `nextAddedAt` to `max` (the highest `addedAt` read back from the store). The clock may have
 * moved backward since those were written (C8: a clock that was ahead gets fixed); a later tap must still sort
 * after them, or an undo could go out before its answer.
 */
export function seedAddedAt(max: number): void {
	if (Number.isFinite(max)) lastAddedAt = Math.max(lastAddedAt, max);
}

/** Test hook: a fresh module (a page reload starts from 0). */
export function resetAddedAtForTests(): void {
	lastAddedAt = 0;
}

function byAddedAt(a: OutboxRecord, b: OutboxRecord): number {
	return a.addedAt - b.addedAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

/** The fallback: a map that lives as long as the page. */
export function memoryOutboxStore(): OutboxStore {
	const m = new Map<string, OutboxRecord>();
	return {
		persistent: false,
		all: async () => [...m.values()].map((r) => ({ ...r })).sort(byAddedAt),
		put: async (r) => {
			m.set(r.id, { ...r });
		},
		delete: async (id) => {
			m.delete(id);
		},
		clear: async () => {
			m.clear();
		}
	};
}

function req<T>(r: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		r.onsuccess = () => resolve(r.result);
		r.onerror = () => reject(r.error ?? new Error('IndexedDB request failed'));
	});
}

/** How long an open may stay `blocked` (another tab holds an older version open) before memory takes over. */
export const OPEN_BLOCKED_TIMEOUT_MS = 10_000;

/**
 * Open the database. `blocked` is not an error: the open completes once the other connection closes, so it is
 * waited for, up to `blockedTimeoutMs`. Without `version` (a reopen after another tab upgraded) the current
 * version is opened; it must still hold our store.
 */
function open(factory: IDBFactory, blockedTimeoutMs: number, version?: number): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		let timer: ReturnType<typeof setTimeout> | null = null;
		let gaveUp = false;
		const stop = () => {
			if (timer !== null) clearTimeout(timer);
			timer = null;
		};
		const r = version === undefined ? factory.open(OUTBOX_DB) : factory.open(OUTBOX_DB, version);
		r.onupgradeneeded = () => {
			// Other stores of this database (LF-W2) create their own on a later version; never drop ours.
			if (!r.result.objectStoreNames.contains(OUTBOX_STORE)) r.result.createObjectStore(OUTBOX_STORE, { keyPath: 'id' });
		};
		r.onsuccess = () => {
			stop();
			const d = r.result;
			if (gaveUp || !d.objectStoreNames.contains(OUTBOX_STORE)) {
				d.close();
				reject(new Error('IndexedDB outbox store missing'));
				return;
			}
			resolve(d);
		};
		r.onerror = () => {
			stop();
			reject(r.error ?? new Error('IndexedDB open failed'));
		};
		r.onblocked = () => {
			timer ??= setTimeout(() => {
				gaveUp = true; // a success after this closes at once: no connection left blocking other tabs
				reject(new Error('IndexedDB open blocked'));
			}, blockedTimeoutMs);
		};
	});
}

/**
 * The IndexedDB store. Each write is its own transaction; transactions on one store run in the order they were
 * created, so a put followed by a delete of the same key always ends deleted. Our connection closes when another
 * tab upgrades the database (`versionchange`) and is reopened on the next call. If the database cannot be opened,
 * or a transaction cannot be started even after a reopen, every call from then on goes to an in-memory store
 * (and `persistent` turns false, so the page warns before a close).
 */
export function idbOutboxStore(factory: IDBFactory, opts: { blockedTimeoutMs?: number } = {}): OutboxStore {
	const blockedTimeoutMs = opts.blockedTimeoutMs ?? OPEN_BLOCKED_TIMEOUT_MS;
	const fallback = memoryOutboxStore();
	let failed = false;
	let db: Promise<IDBDatabase | null> | null = null;
	function connect(version?: number): Promise<IDBDatabase | null> {
		const p = open(factory, blockedTimeoutMs, version)
			.catch((e: unknown) => {
				// Another tab already upgraded past our version (LF-W2 stores): open whatever is current.
				if (version !== undefined && e instanceof Error && e.name === 'VersionError') return open(factory, blockedTimeoutMs);
				throw e;
			})
			.then(
				(d) => {
					d.onversionchange = () => {
						d.close();
						if (db === p) db = null; // reopened (at the new version) on the next call
					};
					return d;
				},
				() => {
					failed = true;
					return null;
				}
			);
		return p;
	}
	function tx(d: IDBDatabase, mode: IDBTransactionMode): IDBTransaction | null {
		try {
			return d.transaction(OUTBOX_STORE, mode);
		} catch {
			return null; // closed (versionchange, browser shutdown) or otherwise unusable
		}
	}
	async function run<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>, mem: () => Promise<T>): Promise<T> {
		if (failed) return mem();
		const first = (db ??= connect(OUTBOX_DB_VERSION));
		let d = await first;
		let t = d ? tx(d, mode) : null;
		if (d && !t) {
			// One reopen at the current version, shared by calls that race here (a call that finds a newer
			// connection promise uses that one); if it cannot start a transaction either, memory from now on.
			if (db === first || db === null) db = connect();
			d = await db;
			t = d ? tx(d, mode) : null;
			if (!t) failed = true;
		}
		if (!t) return mem();
		const done = new Promise<void>((resolve, reject) => {
			t.oncomplete = () => resolve();
			t.onerror = () => reject(t.error ?? new Error('IndexedDB transaction failed'));
			t.onabort = () => reject(t.error ?? new Error('IndexedDB transaction aborted'));
		});
		const out = await req(f(t.objectStore(OUTBOX_STORE)));
		if (mode === 'readwrite') await done; // durable once the transaction commits
		return out;
	}
	return {
		get persistent() {
			return !failed;
		},
		all: async () =>
			(
				await run(
					'readonly',
					(s) => s.getAll() as IDBRequest<OutboxRecord[]>,
					() => fallback.all()
				)
			).sort(byAddedAt),
		put: async (r) => {
			await run(
				'readwrite',
				(s) => s.put(r),
				() => fallback.put(r).then(() => r.id)
			);
		},
		delete: async (id) => {
			await run<undefined>(
				'readwrite',
				(s) => s.delete(id),
				() => fallback.delete(id).then(() => undefined)
			);
		},
		clear: async () => {
			await run<undefined>(
				'readwrite',
				(s) => s.clear(),
				() => fallback.clear().then(() => undefined)
			);
		}
	};
}

/** IndexedDB when the browser has it, else the in-memory store. */
export function defaultOutboxStore(): OutboxStore {
	const f = typeof indexedDB !== 'undefined' ? indexedDB : undefined;
	return f ? idbOutboxStore(f) : memoryOutboxStore();
}
