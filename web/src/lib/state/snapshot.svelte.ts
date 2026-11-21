// PERF-SNAP: the start-up snapshot. The last successful Home state (loop counts, decks, stats widgets, hot lists),
// the first page of the review queue and what the review screen needs to render it (note types, presets, the two
// settings) are kept in IndexedDB (database `kotobaya-snapshot`, store `snapshot`, one record per section), so the
// next app start draws Home and the first card at once and refreshes behind them (stale-while-revalidate).
// Everything belongs to one logged-in principal (`Principal.token_id`): another principal never sees it, and
// logout clears it. Reads are synchronous from memory after `loadSnapshot` (once per page life, at app start).
import type {
	Deck,
	HotKanji,
	HotWord,
	NoteType,
	Preset,
	Principal,
	QueueItem,
	StatsHeatmap,
	StatsRetention,
	StatsSummary
} from '../api/generated';
import type { LoopView } from '../review/loop';
import { idbRecordStore, memoryRecordStore, type RecordStore } from '../review/outboxStore';

export const SNAPSHOT_DB = 'kotobaya-snapshot';
export const SNAPSHOT_STORE = 'snapshot';
const SNAPSHOT_DB_VERSION = 1;
/** Record format. A record of another format (an older or newer bundle) is ignored and overwritten. */
export const SNAPSHOT_FORMAT = 1;
/** At most this many queue items are kept (the first page; `QUEUE_LIMIT` of the review screen). */
export const SNAPSHOT_QUEUE_MAX = 30;

export interface QueueSnapshot {
	/** `loop` of the fetch the items came from (Home's hero and the review header). */
	view: LoopView;
	dayEndsAt: Date | null;
	/** The first page, minus every card answered on this device since (dropped at the tap). */
	items: QueueItem[];
}

export interface ReviewStatics {
	noteTypes: NoteType[];
	presets: Preset[];
	fiveButtons: boolean;
	autoplay: boolean;
}

export interface StatsSnapshot {
	summary?: StatsSummary;
	heatmap?: StatsHeatmap;
	retention?: StatsRetention;
}

export interface Sections {
	principal: Principal;
	queue: QueueSnapshot;
	statics: ReviewStatics;
	decks: Deck[];
	struggle: { words: HotWord[]; kanji: HotKanji[] };
	stats: StatsSnapshot;
}

export type Section = keyof Sections;

export interface SnapshotRecord {
	id: Section;
	format: number;
	account: string;
	savedAt: number;
	data: unknown;
}

let store: RecordStore<SnapshotRecord> | null = null;
/** Whose snapshot is in memory (and may be written): `Principal.token_id`, or null (nobody known yet). */
let account: string | null = null;
let cache = new Map<Section, unknown>();
let loading: Promise<void> | null = null;
/** Bumped by a clear: a read begun before it does not bring the old data back. */
let gen = 0;

function storeRef(): RecordStore<SnapshotRecord> {
	if (!store) {
		const f = typeof indexedDB !== 'undefined' ? indexedDB : undefined;
		store = f ? idbRecordStore<SnapshotRecord>(f, { db: SNAPSHOT_DB, store: SNAPSHOT_STORE, version: SNAPSHOT_DB_VERSION }) : memoryRecordStore();
	}
	return store;
}

/**
 * Read what an earlier page life stored (once per page life; later calls share the first). Records of another
 * account than the stored principal's, or of another format, are ignored. Never throws.
 */
export function loadSnapshot(): Promise<void> {
	if (!loading) {
		const g = gen;
		loading = storeRef()
			.all()
			.then(
				(recs) => {
					if (g !== gen) return;
					const current = recs.filter((r) => r.format === SNAPSHOT_FORMAT);
					const who = account ?? current.find((r) => r.id === 'principal')?.account ?? null;
					if (who === null) return;
					account = who;
					for (const r of current) if (r.account === who && !cache.has(r.id)) cache.set(r.id, r.data);
				},
				() => {
					/* nothing readable: a cold start */
				}
			);
	}
	return loading;
}

/** The principal the snapshot belongs to (an optimistic session at start-up), or null. */
export function snapshotPrincipal(): Principal | null {
	return readSnapshot('principal');
}

/** A section of the snapshot, synchronously (null when there is none for the current account). */
export function readSnapshot<K extends Section>(k: K): Sections[K] | null {
	return (cache.get(k) as Sections[K] | undefined) ?? null;
}

/**
 * The session's principal is known (login, or the session check). Another principal than the snapshot's: the old
 * snapshot is dropped, here and in the store.
 */
export function setSnapshotAccount(p: Principal): void {
	if (account !== null && account !== p.tokenId) {
		gen++;
		cache = new Map();
		void storeRef()
			.clear()
			.catch(() => {});
	}
	account = p.tokenId;
	writeSnapshot('principal', p);
}

/**
 * Keep `data` as section `k` (memory at once, the store in the background). Nothing is written before an account
 * is known. `data` may be reactive state: a plain copy is stored.
 */
export function writeSnapshot<K extends Section>(k: K, data: Sections[K]): void {
	if (account === null) return;
	const plain = $state.snapshot(data) as Sections[K];
	cache.set(k, plain);
	const rec: SnapshotRecord = { id: k, format: SNAPSHOT_FORMAT, account, savedAt: Date.now(), data: plain };
	void storeRef()
		.put(rec)
		.catch(() => {
			/* memory still has it for this page life */
		});
}

/** Write the queue page (at most `SNAPSHOT_QUEUE_MAX` items). */
export function writeQueueSnapshot(q: QueueSnapshot): void {
	writeSnapshot('queue', { ...q, items: q.items.slice(0, SNAPSHOT_QUEUE_MAX) });
}

/** Logout: the snapshot belongs to the user who logged out; gone here and in the store. */
export async function clearSnapshot(): Promise<void> {
	gen++;
	account = null;
	cache = new Map();
	loading = Promise.resolve();
	await storeRef().clear();
}

/** Test hook: use this store from now on (null: a fresh default one). Memory is forgotten. */
export function setSnapshotStoreForTests(s: RecordStore<SnapshotRecord> | null): void {
	store = s;
	resetSnapshotMemoryForTests();
}

/** Test hook: a page reload (memory is gone, the store is kept). */
export function resetSnapshotMemoryForTests(): void {
	gen++;
	account = null;
	cache = new Map();
	loading = null;
}
