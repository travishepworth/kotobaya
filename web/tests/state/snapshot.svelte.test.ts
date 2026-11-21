// @vitest-environment jsdom
// PERF-SNAP: after one successful load, Home and the first review card come from the start-up snapshot at once,
// with every API call delayed 30 s; a refresh replaces values without blanking; a pending answer's card is never
// served again; the snapshot is per principal and logout clears it.
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Principal, QueueItem, ReviewQueue } from '../../src/lib/api/generated';
import { loop } from '../review/loopFixture';
import { vocabNoteType } from '../support/previews';

const api = vi.hoisted(() => ({
	getReviewQueue: vi.fn(),
	answerReview: vi.fn(),
	undoReview: vi.fn(),
	listNoteTypes: vi.fn(),
	listPresets: vi.fn(),
	listSettings: vi.fn(),
	listDecks: vi.fn(),
	listHotWords: vi.fn(),
	listHotKanji: vi.fn(),
	getStatsSummary: vi.fn(),
	getStatsHeatmap: vi.fn(),
	getStatsRetention: vi.fn(),
	getPrincipal: vi.fn(),
	logout: vi.fn()
}));
vi.mock('../../src/lib/review/api', () => ({
	reviewApi: api,
	cardsApi: {},
	notesApi: {},
	wordsApi: {},
	speechApi: { resolveSpeech: () => new Promise(() => {}) },
	presetsApi: { listPresets: api.listPresets },
	noteTypesApi: { listNoteTypes: api.listNoteTypes },
	settingsApi: { listSettings: api.listSettings }
}));
// Home's stores build their own clients: the same mocks behind them.
vi.mock('../../src/lib/api/generated', async (orig) => ({
	...(await orig<typeof import('../../src/lib/api/generated')>()),
	ReviewApi: class {
		getReviewQueue = api.getReviewQueue;
	},
	DecksApi: class {
		listDecks = api.listDecks;
	},
	WordsApi: class {
		listHotWords = api.listHotWords;
		listHotKanji = api.listHotKanji;
	},
	StatsApi: class {
		getStatsSummary = api.getStatsSummary;
		getStatsHeatmap = api.getStatsHeatmap;
		getStatsRetention = api.getStatsRetention;
	}
}));
vi.mock('../../src/lib/api/client', async (orig) => ({
	...(await orig<typeof import('../../src/lib/api/client')>()),
	authApi: { logout: api.logout, getPrincipal: api.getPrincipal }
}));
vi.mock('../../src/lib/events', () => ({ openEventStream: () => () => {} }));
vi.mock('$app/navigation', () => ({ goto: vi.fn() }));

import HomePage from '../../src/routes/+page.svelte';
import { createReviewPage, resetOutboxForTests, setOutboxStoreForTests, simulateReloadForTests, type ReviewPage } from '../../src/lib/state/review.svelte';
import { TodayQueue } from '../../src/lib/state/today.svelte';
import { DecksListPage } from '../../src/lib/state/decks.svelte';
import { StatsPage } from '../../src/lib/state/stats.svelte';
import { StrugglePage } from '../../src/lib/state/struggle.svelte';
import { bootSession, logout, session } from '../../src/lib/state/session.svelte';
import {
	loadSnapshot,
	readSnapshot,
	resetSnapshotMemoryForTests,
	setSnapshotAccount,
	setSnapshotStoreForTests,
	type SnapshotRecord
} from '../../src/lib/state/snapshot.svelte';
import { memoryOutboxStore, memoryRecordStore, type RecordStore } from '../../src/lib/review/outboxStore';

const DELAY = 30_000;
const good = { rating: 3, label: 'good' } as never;
const principal = (tokenId = 'tok-1'): Principal => ({
	deviceId: null,
	expiresAt: null,
	kind: 'session' as never,
	name: 'web',
	scopes: ['read', 'write', 'review'] as never,
	tokenId,
	totpEnabled: false
});

function item(id: string, word = id): QueueItem {
	return {
		card: {
			id,
			noteId: `n-${id}`,
			presetId: 'p',
			kind: 'meaning',
			state: 'review',
			note: { id: `n-${id}`, noteTypeId: vocabNoteType.id, type: 'vocab', fields: { expression: word, reading: '', meaning: 'm' }, mnemonic: '', tags: [] }
		},
		reviewType: 'schedule'
	} as unknown as QueueItem;
}

function queueOf(ids: string[], ready = ids.length): ReviewQueue {
	return {
		items: ids.map((i) => item(i)),
		studyDay: new Date('2026-11-21T00:00:00Z'),
		dayEndsAt: new Date(Date.now() + 10 * 3600_000),
		generatedAt: new Date(),
		toMaterialize: 0,
		loop: loop({ phase: ids.length > 0 ? 'reviewing' : 'empty', ready: { review: ready } })
	} as unknown as ReviewQueue;
}

const later = <T,>(v: T, ms = DELAY): Promise<T> => new Promise((r) => setTimeout(() => r(v), ms));
const tick = async (ms = 0) => {
	await vi.advanceTimersByTimeAsync(ms);
	flushSync();
};

/** Every call answers after `ms` (0: at once). */
function answerAll(ms: number, q: () => ReviewQueue): void {
	const at = <T,>(v: () => T) => () => (ms === 0 ? Promise.resolve(v()) : later(v(), ms));
	api.getReviewQueue.mockImplementation(at(q));
	api.listNoteTypes.mockImplementation(at(() => ({ items: [vocabNoteType] })));
	api.listPresets.mockImplementation(at(() => ({ items: [] })));
	api.listSettings.mockImplementation(at(() => ({ items: [] })));
	api.listDecks.mockImplementation(
		at(() => ({ items: [{ id: 'd1', name: 'Core', type: 'user', priority: 1, itemCount: 7, enabled: true, createdAt: new Date(0), updatedAt: new Date(0) }] }))
	);
	api.listHotWords.mockImplementation(at(() => ({ items: [] })));
	api.listHotKanji.mockImplementation(at(() => ({ items: [] })));
	api.getStatsSummary.mockImplementation(at(() => ({ totals: { cards: 12, leeches: 0 }, doneToday: { reviews: 0, timeMs: 0, retention: null }, streakDays: 4, nextDueAt: null })));
	api.getStatsHeatmap.mockImplementation(at(() => ({ days: [], from: new Date(0), to: new Date(0) })));
	api.getStatsRetention.mockImplementation(at(() => ({ overall: { retention: 0.9 } })));
	api.getPrincipal.mockImplementation(at(() => principal()));
	api.answerReview.mockImplementation((r: { reviewAnswerRequest: { id: string; cardId: string } }) =>
		later({ review: { id: r.reviewAnswerRequest.id }, card: { id: r.reviewAnswerRequest.cardId, state: 'review', dueAt: null } }, ms)
	);
}

let snapStore: RecordStore<SnapshotRecord>;
const pages: ReviewPage[] = [];
const mounted: ReturnType<typeof mount>[] = [];

/** A page reload: memory is gone, both IndexedDB stores are kept. */
async function reload(): Promise<void> {
	pages.splice(0).forEach((p) => p.release());
	simulateReloadForTests();
	resetSnapshotMemoryForTests();
	session.status = 'unknown';
	session.principal = null;
	await loadSnapshot();
}

/** One successful session: Home loaded, the review screen opened once. */
async function priorLoad(ids: string[]): Promise<void> {
	answerAll(0, () => queueOf(ids));
	setSnapshotAccount(principal());
	const today = new TodayQueue();
	await today.load();
	new DecksListPage().start();
	new StrugglePage().start();
	const stats = new StatsPage();
	stats.start(['summary', 'heatmap', 'retention']);
	const p = createReviewPage();
	pages.push(p);
	p.start(null);
	await tick();
	expect(p.current?.card.id).toBe(ids[0]);
	today.dispose();
	stats.dispose();
}

beforeEach(() => {
	vi.resetAllMocks();
	vi.useFakeTimers();
	resetOutboxForTests();
	setOutboxStoreForTests(memoryOutboxStore());
	snapStore = memoryRecordStore<SnapshotRecord>();
	setSnapshotStoreForTests(snapStore);
	localStorage.clear();
	session.status = 'unknown';
	session.principal = null;
});
afterEach(() => {
	mounted.splice(0).forEach((m) => unmount(m));
	document.body.innerHTML = '';
	pages.splice(0).forEach((p) => p.release());
	vi.useRealTimers();
});

describe('instant start from the snapshot (PERF-SNAP)', () => {
	it('every API call delayed 30 s: Home counts and an enabled Start review render synchronously', async () => {
		await priorLoad(['c1', 'c2', 'c3']);
		await reload();
		answerAll(DELAY, () => queueOf(['c1', 'c2', 'c3']));

		const booted = bootSession(); // the session check now takes 30 s
		await tick();
		expect(session.status).toBe('in'); // optimistic, from the snapshot's principal

		const el = document.body.appendChild(document.createElement('div'));
		mounted.push(mount(HomePage, { target: el }));
		flushSync();
		expect(el.querySelector('[aria-busy="true"]')).toBeNull(); // no skeleton
		expect(el.querySelector('[data-testid="loop-ready"]')?.textContent).toBe('3');
		expect(el.querySelector('a[href="/review"]')?.textContent).toContain('Start review');
		expect(el.textContent).toContain('Core'); // decks from the snapshot
		expect(el.querySelector('dd')?.textContent).toBe('4'); // streak, from the stats snapshot
		expect(el.querySelector('[data-testid="home-updating"]')).not.toBeNull();

		await tick(DELAY + 3000);
		await booted;
		flushSync();
		expect(el.querySelector('[data-testid="home-updating"]')).toBeNull();
	});

	it('every API call delayed 30 s: the first card shows at once, rendered, and can be graded', async () => {
		await priorLoad(['c1', 'c2']);
		await reload();
		answerAll(DELAY, () => queueOf(['c1', 'c2']));

		const p = createReviewPage();
		pages.push(p);
		p.start(null);
		expect(p.loaded).toBe(true);
		expect(p.phase).toBe('card');
		expect(p.current?.card.id).toBe('c1');
		const r = p.rendered;
		expect(r?.ok && r.card.html).toContain('c1');
		expect(p.stale).toBe(true);
		p.flip();
		void p.grade(good);
		expect(p.current?.card.id).toBe('c2');

		// The fetch times out (30 s): the snapshot stays on screen, still marked as updating, never an error page.
		await tick(DELAY + 1);
		expect(p.loadFailed).toBe(false);
		expect(p.phase).toBe('card');
		expect(p.current?.card.id).toBe('c2');
		expect(p.stale).toBe(true);
	});

	it('the review refresh replaces the snapshot queue behind the card on screen, without blanking', async () => {
		await priorLoad(['c1', 'c2', 'c3']);
		await reload();
		answerAll(5000, () => queueOf(['c2', 'c9'], 2)); // c1 was answered on another device; c9 is new
		const p = createReviewPage();
		pages.push(p);
		p.start(null);
		expect(p.current?.card.id).toBe('c1');
		p.flip();
		await tick(2500);
		expect(p.current?.card.id).toBe('c1'); // still on screen while the queue loads
		await tick(2500);
		expect(p.stale).toBe(false);
		expect(p.current?.card.id).toBe('c1'); // the card being looked at is not swapped away
		expect(p.flipped).toBe(true);
		expect(p.items.map((i) => i.card.id)).toEqual(['c1', 'c2', 'c9']);
		expect(p.loop?.ready.total).toBe(2);
	});

	it('a refresh replaces the values without blanking', async () => {
		await priorLoad(['c1', 'c2', 'c3']);
		await reload();
		answerAll(DELAY, () => queueOf(['c4', 'c5'], 9));
		const today = new TodayQueue();
		expect(today.loaded).toBe(true);
		expect(today.view?.loop.ready.total).toBe(3);
		const decks = new DecksListPage();
		expect(decks.items.map((d) => d.id)).toEqual(['d1']);
		const loading = today.load();
		decks.start();
		await tick(DELAY / 2);
		expect(today.view?.loop.ready.total).toBe(3); // still shown while the refresh runs
		expect(today.stale).toBe(true);
		expect(decks.loaded).toBe(true);
		await tick(DELAY);
		await loading;
		expect(today.view?.loop.ready.total).toBe(9);
		expect(today.stale).toBe(false);
		expect(readSnapshot('queue')?.items.map((i) => i.card.id)).toEqual(['c4', 'c5']);
		today.dispose();
	});

	it('a failed refresh keeps the snapshot, marked as updating', async () => {
		await priorLoad(['c1']);
		await reload();
		api.getReviewQueue.mockRejectedValue(new TypeError('offline'));
		const today = new TodayQueue();
		await today.load();
		expect(today.view?.loop.ready.total).toBe(1);
		expect(today.failed).toBe(false);
		expect(today.stale).toBe(true);
		today.dispose();
	});

	it('a card answered (and still in the outbox) is not served again from the snapshot', async () => {
		await priorLoad(['c1', 'c2', 'c3']);
		const p = pages[0];
		answerAll(DELAY, () => queueOf(['c1', 'c2', 'c3'])); // the answer stays unacknowledged
		p.flip();
		void p.grade(good);
		await tick();
		expect(readSnapshot('queue')?.items.map((i) => i.card.id)).toEqual(['c2', 'c3']);

		await reload();
		const q = createReviewPage();
		pages.push(q);
		q.start(null);
		expect(q.current?.card.id).toBe('c2');
		await tick(DELAY + 1);
		expect(q.items.map((i) => i.card.id)).not.toContain('c1');
	});

	it('a stale snapshot listing a card with a pending answer (read back from the outbox) drops it', async () => {
		await priorLoad(['c1', 'c2']);
		// The answer reached the outbox store but not the snapshot (a crash in between).
		const store = memoryOutboxStore();
		await store.put({
			id: 'a1',
			op: 'answer',
			cardId: 'c1',
			addedAt: 1,
			attempts: 0,
			kind: 'review',
			resultingState: null,
			dueAt: null,
			req: { id: 'a1', cardId: 'c1', rating: 3, mode: 'schedule', durationMs: 1, utcOffsetMin: 0 }
		});
		await reload();
		setOutboxStoreForTests(store);
		answerAll(DELAY, () => queueOf(['c1', 'c2']));
		const p = createReviewPage();
		pages.push(p);
		p.start(null);
		await tick();
		expect(p.current?.card.id).toBe('c2');
		await tick(DELAY + 1);
		expect(p.items.map((i) => i.card.id)).not.toContain('c1');
	});
});

describe('per account (PERF-SNAP)', () => {
	it('logout clears the snapshot, in memory and in the store', async () => {
		await priorLoad(['c1']);
		api.logout.mockResolvedValue(undefined);
		expect(await snapStore.all()).not.toEqual([]);
		await logout();
		expect(readSnapshot('queue')).toBeNull();
		expect(await snapStore.all()).toEqual([]);
		await reload();
		expect(readSnapshot('principal')).toBeNull();
		expect(new TodayQueue().loaded).toBe(false);
	});

	it('another principal drops the snapshot', async () => {
		await priorLoad(['c1']);
		await reload();
		expect(readSnapshot('queue')).not.toBeNull();
		setSnapshotAccount(principal('tok-2'));
		expect(readSnapshot('queue')).toBeNull();
		await tick();
		expect((await snapStore.all()).map((r) => r.id)).toEqual(['principal']);
	});

	it('no snapshot: nothing is optimistic and Home waits as before', async () => {
		answerAll(DELAY, () => queueOf(['c1']));
		void bootSession();
		await tick();
		expect(session.status).toBe('unknown');
		expect(new TodayQueue().loaded).toBe(false);
	});
});
