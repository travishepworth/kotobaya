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

function open(factory: IDBFactory): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const r = factory.open(OUTBOX_DB, OUTBOX_DB_VERSION);
		r.onupgradeneeded = () => {
			// Other stores of this database (LF-W2) create their own on a later version; never drop ours.
			if (!r.result.objectStoreNames.contains(OUTBOX_STORE)) r.result.createObjectStore(OUTBOX_STORE, { keyPath: 'id' });
		};
		r.onsuccess = () => resolve(r.result);
		r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
		r.onblocked = () => reject(new Error('IndexedDB open blocked'));
	});
}

/**
 * The IndexedDB store. Each write is its own transaction; transactions on one store run in the order they were
 * created, so a put followed by a delete of the same key always ends deleted. If the database cannot be opened,
 * every call goes to an in-memory store instead (and `persistent` turns false).
 */
export function idbOutboxStore(factory: IDBFactory): OutboxStore {
	const fallback = memoryOutboxStore();
	let failed = false;
	const db = open(factory).catch(() => {
		failed = true;
		return null;
	});
	async function run<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>, mem: () => Promise<T>): Promise<T> {
		const d = await db;
		if (!d) return mem();
		const tx = d.transaction(OUTBOX_STORE, mode);
		const done = new Promise<void>((resolve, reject) => {
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'));
			tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
		});
		const out = await req(f(tx.objectStore(OUTBOX_STORE)));
		if (mode === 'readwrite') await done; // durable once the transaction commits
		return out;
	}
	return {
		get persistent() {
			return !failed;
		},
		all: async () => (await run('readonly', (s) => s.getAll() as IDBRequest<OutboxRecord[]>, () => fallback.all())).sort(byAddedAt),
		put: async (r) => {
			await run('readwrite', (s) => s.put(r), () => fallback.put(r).then(() => r.id));
		},
		delete: async (id) => {
			await run<undefined>('readwrite', (s) => s.delete(id), () => fallback.delete(id).then(() => undefined));
		},
		clear: async () => {
			await run<undefined>('readwrite', (s) => s.clear(), () => fallback.clear().then(() => undefined));
		}
	};
}

/** IndexedDB when the browser has it, else the in-memory store. */
export function defaultOutboxStore(): OutboxStore {
	const f = typeof indexedDB !== 'undefined' ? indexedDB : undefined;
	return f ? idbOutboxStore(f) : memoryOutboxStore();
}
