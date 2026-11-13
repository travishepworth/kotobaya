// Mock-server mode (UI-FOUND-WEB): a Vite dev-server middleware that answers /api/v1 from the demo dataset in
// fixtures/demo/, in the wire shapes the generated client expects (snake_case JSON, RFC 9457 problems).
// Dev tooling only: never part of the built SPA. Writes are kept in memory per scenario and lost on restart.
//
// Scenario: KOTOBAYA_MOCK=<default|empty|due900> picks the default; a `kb-mock-scenario` cookie overrides it
// per browser (the screenshot harness uses that), and GET /__mock/scenario/<name> sets the cookie and redirects.
import type { Connect, Plugin } from 'vite';
import accountJson from '../../fixtures/demo/account.json' with { type: 'json' };
import dictionaryJson from '../../fixtures/demo/dictionary.json' with { type: 'json' };
import learningJson from '../../fixtures/demo/learning.json' with { type: 'json' };
import mediaJson from '../../fixtures/demo/media.json' with { type: 'json' };
import scenariosJson from '../../fixtures/demo/scenarios.json' with { type: 'json' };
import statsJson from '../../fixtures/demo/stats.json' with { type: 'json' };

/** A wire object; fields are read through the narrow interfaces below or passed through untouched. */
type Obj = { [key: string]: unknown };

interface WireCard extends Obj {
	id: string;
	note_id: string;
	kind: string;
	state: string;
	suspended: boolean;
	leech: boolean;
}
interface WireNote extends Obj {
	id: string;
	note_type_id: string;
	type: string;
	fields: Record<string, string>;
	tags: string[];
	entry_seq: number | null;
	kanji_literal: string | null;
	cards: WireCard[];
}
interface WireEntry extends Obj {
	seq: number;
	headword: string;
	headword_reading: string;
	senses: { gloss: { text: string }[]; pos: string[] }[];
	is_common: boolean;
	note_id: string | null;
	kanji: string[];
}
interface WireDeck extends Obj {
	id: string;
	type: string;
	name: string;
	media_id: string | null;
}
interface WireMedia extends Obj {
	id: string;
	kind: string;
	title: string;
}
interface WireSentence extends Obj {
	id: string;
	media_id: string;
	segment_id: string;
	text: string;
	tokens: { seq: number | null }[];
	unknown_count: number;
}
interface Scenario {
	description: string;
	queue_counts: { new: number; learning: number; review: number };
	empty: string[];
}

interface Dataset {
	principal: Obj;
	meta: Obj;
	settings: Obj[];
	presets: Obj[];
	note_types: Obj[];
	devices: Obj[];
	tokens: Obj[];
	entries: WireEntry[];
	kanji: Obj[];
	tatoeba: Record<string, Obj[]>;
	notes: WireNote[];
	decks: WireDeck[];
	deck_items: Record<string, Obj[]>;
	hot_words: Obj[];
	hot_kanji: Obj[];
	confusables: Obj[];
	deck_lists: Obj[];
	media: WireMedia[];
	sentences: WireSentence[];
	coverage: Record<string, Obj>;
	parse_overrides: Obj[];
	jpdb_vocab: Obj[];
	grammar_points: Obj[];
	stats: Obj | null;
}

/** Mutable per-scenario state on top of a fresh copy of the dataset. */
interface State {
	name: string;
	scenario: Scenario;
	data: Dataset;
	loggedIn: boolean;
	answered: string[];
	jobs: Map<string, Obj>;
	reviewSeq: number;
}

export const SCENARIOS = Object.keys(scenariosJson);

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

function load(name: string): State {
	const scenario = (scenariosJson as Record<string, Scenario>)[name] ?? (scenariosJson as Record<string, Scenario>).default;
	const a = clone(accountJson) as unknown as Obj;
	const d = clone(dictionaryJson) as unknown as Obj;
	const l = clone(learningJson) as unknown as Obj;
	const m = clone(mediaJson) as unknown as Obj;
	const data = { ...a, ...d, ...l, ...m, stats: clone(statsJson) } as unknown as Dataset;
	for (const key of scenario.empty) {
		const k = key as keyof Dataset;
		const v = data[k];
		(data as unknown as Obj)[k] = Array.isArray(v) ? [] : k === 'stats' ? null : {};
	}
	if (scenario.empty.includes('notes')) for (const e of data.entries) e.note_id = null;
	return { name, scenario, data, loggedIn: true, answered: [], jobs: new Map(), reviewSeq: 0 };
}

/* ---------------------------------------------------------------- helpers */

class HttpError extends Error {
	constructor(
		readonly status: number,
		readonly code: string,
		readonly detail?: string
	) {
		super(code);
	}
}

const notFound = (what: string): HttpError => new HttpError(404, 'not_found', `${what} does not exist (demo data)`);
const now = (): string => new Date().toISOString();

function uuid(): string {
	const h = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-7${h.slice(13, 16)}-8${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

/** Offset cursor paging over an array. */
function page<T>(items: T[], q: URLSearchParams, def = 50): { items: T[]; next_cursor: string | null } {
	const limit = Math.max(1, Number(q.get('limit') ?? def));
	const start = Number(q.get('cursor') ?? 0) || 0;
	const end = start + limit;
	return { items: items.slice(start, end), next_cursor: end < items.length ? String(end) : null };
}

function noteSummary(n: WireNote): Obj {
	const { id, note_type_id, type, fields, tags, mnemonic, entry_seq, kanji_literal, grammar_point_id, source_sentence } = n;
	return { id, note_type_id, type, fields, tags, mnemonic, entry_seq, kanji_literal, grammar_point_id, source_sentence };
}

function entrySummary(e: WireEntry): Obj {
	return {
		seq: e.seq,
		headword: e.headword,
		headword_reading: e.headword_reading,
		glosses: e.senses.flatMap((s) => s.gloss.map((g) => g.text)).slice(0, 6),
		pos: e.pos,
		is_common: e.is_common,
		freq_rank: e.freq_rank,
		retired: e.retired,
		note_id: e.note_id,
		status: e.status
	};
}

function allCards(s: State): { card: WireCard; note: WireNote }[] {
	return s.data.notes.flatMap((note) => note.cards.map((card) => ({ card, note })));
}

function cardWithNote(card: WireCard, note: WireNote): Obj {
	return { ...card, note: noteSummary(note) };
}

function findNote(s: State, id: string): WireNote {
	const n = s.data.notes.find((x) => x.id === id);
	if (!n) throw notFound('note');
	return n;
}

function entryOf(s: State, seq: number | null): WireEntry | null {
	return seq === null ? null : (s.data.entries.find((e) => e.seq === seq) ?? null);
}

function kanjiOf(s: State, literal: string): Obj {
	return (
		s.data.kanji.find((k) => k.literal === literal) ?? {
			literal,
			meanings: [],
			on_readings: [],
			kun_readings: [],
			nanori: [],
			stroke_count: null,
			grade: null,
			jlpt: null,
			freq_rank: null,
			radical: null,
			components: [],
			note_id: null
		}
	);
}

function job(s: State, kind: string, payload: Obj, result: Obj | null): Obj {
	const j = {
		id: uuid(),
		kind,
		status: 'succeeded',
		payload,
		result,
		error: null,
		progress: 1,
		progress_message: null,
		attempts: 1,
		max_attempts: 3,
		created_at: now(),
		started_at: now(),
		finished_at: now()
	};
	s.jobs.set(j.id, j);
	return j;
}

/** Text search over a JSON value: every whitespace-separated term must occur (case-insensitive). */
function matches(value: unknown, query: string): boolean {
	const hay = JSON.stringify(value).toLowerCase();
	return query
		.toLowerCase()
		.split(/\s+/)
		.filter((t) => t !== '')
		.every((t) => hay.includes(t.replace(/^[a-z_]+:/, '')));
}

function emptyStats(): Obj {
	const today = now().slice(0, 10);
	const zeroGroup = {
		key: 'overall',
		total: 0,
		passed: 0,
		retention: null,
		young_total: 0,
		young_passed: 0,
		mature_total: 0,
		mature_passed: 0
	};
	return {
		summary: {
			study_day: today,
			day_ends_at: now(),
			due: { new: 0, learning: 0, review: 0 },
			done_today: { reviews: 0, new_cards: 0, time_ms: 0, retention: null },
			streak_days: 0,
			next_due_at: null,
			totals: { notes: 0, cards: 0, suspended: 0, leeches: 0 }
		},
		heatmap: { from: today, to: today, days: [] },
		retention: { from: today, to: today, group_by: 'card_kind', overall: zeroGroup, groups: [] },
		forecast: { days: [], hours: [] },
		known_words: {
			current: { known: 0, never_forget: 0, learning: 0, new: 0, ignored: 0, blacklisted: 0 },
			series: []
		},
		calibration: { from: today, to: today, preset_id: null, review_count: 0, bins: [] }
	};
}

/* ---------------------------------------------------------------- routes */

type Handler = (s: State, m: string[], q: URLSearchParams, body: Obj) => unknown;
const routes: { method: string; re: RegExp; fn: Handler }[] = [];
function on(method: string, path: string, fn: Handler): void {
	routes.push({ method, re: new RegExp(`^${path.replace(/:(\w+)/g, '([^/]+)')}$`), fn });
}

// auth / meta / settings
on('GET', '/auth/session', (s) => {
	if (!s.loggedIn) throw new HttpError(401, 'unauthenticated');
	return s.data.principal;
});
on('POST', '/auth/login', (s) => {
	s.loggedIn = true;
	return s.data.principal;
});
on('POST', '/auth/logout', (s) => {
	s.loggedIn = false;
	return null;
});
on('GET', '/meta', (s) => ({ ...s.data.meta, server_time: now() }));
on('GET', '/health', () => ({ status: 'ok' }));
on('GET', '/settings', (s) => ({ items: s.data.settings }));
on('PATCH', '/settings', (s, _m, _q, b) => {
	const values = (b.values ?? {}) as Obj;
	for (const [key, value] of Object.entries(values)) {
		s.data.settings = s.data.settings.filter((x) => x.key !== key);
		if (value !== null) s.data.settings.push({ key, value, updated_at: now() });
	}
	return { items: s.data.settings };
});

// presets
on('GET', '/presets', (s) => ({ items: s.data.presets }));
on('POST', '/presets', (s, _m, _q, b) => {
	const p = { ...s.data.presets[0], id: uuid(), name: b.name ?? 'New preset', created_at: now(), updated_at: now() };
	s.data.presets.push(p);
	return p;
});
on('GET', '/presets/:id', (s, m) => s.data.presets.find((p) => p.id === m[1]) ?? notFoundThrow('preset'));
on('PATCH', '/presets/:id', (s, m, _q, b) => {
	const p = s.data.presets.find((x) => x.id === m[1]) ?? notFoundThrow('preset');
	Object.assign(p, b, { updated_at: now() });
	return p;
});
on('DELETE', '/presets/:id', (s, m) => {
	s.data.presets = s.data.presets.filter((p) => p.id !== m[1]);
	return null;
});
on('POST', '/presets/:id/optimize', (s, m) => job(s, 'fsrs-optimize', { preset_id: m[1] }, null));

function notFoundThrow(what: string): never {
	throw notFound(what);
}

// note types
on('GET', '/note-types', (s) => ({ items: s.data.note_types }));
on('GET', '/note-types/:id', (s, m) => s.data.note_types.find((t) => t.id === m[1]) ?? notFoundThrow('note type'));
on('POST', '/note-types', (s, _m, _q, b) => {
	const t = { ...b, id: uuid(), builtin: false, created_at: now(), updated_at: now(), deleted_at: null };
	s.data.note_types.push(t);
	return t;
});
on('PATCH', '/note-types/:id', (s, m, _q, b) => {
	const t = s.data.note_types.find((x) => x.id === m[1]) ?? notFoundThrow('note type');
	Object.assign(t, b, { updated_at: now() });
	return t;
});
on('DELETE', '/note-types/:id', (s, m) => {
	const t = s.data.note_types.find((x) => x.id === m[1]);
	if (t?.builtin) throw new HttpError(409, 'builtin', 'Built-in note types cannot be deleted.');
	s.data.note_types = s.data.note_types.filter((x) => x.id !== m[1]);
	return null;
});

// dictionary
on('GET', '/dict/search', (s, _m, q) => {
	const term = (q.get('q') ?? '').trim().toLowerCase();
	const common = q.get('common_only') === 'true';
	const hits = s.data.entries.filter(
		(e) =>
			(!common || e.is_common) &&
			(e.headword.includes(term) ||
				e.headword_reading.includes(term) ||
				e.senses.some((x) => x.gloss.some((g) => g.text.toLowerCase().includes(term))))
	);
	const p = page(hits, q);
	return { ...p, items: p.items.map(entrySummary) };
});
on('GET', '/dict/entries/:seq', (s, m) => entryOf(s, Number(m[1])) ?? notFoundThrow('entry'));
on('GET', '/dict/entries/:seq/examples', (s, m, q) => {
	const seq = Number(m[1]);
	const limit = Number(q.get('limit') ?? 10);
	return {
		media: s.data.sentences.filter((x) => x.tokens.some((t) => t.seq === seq)).slice(0, limit),
		tatoeba: (s.data.tatoeba[String(seq)] ?? []).slice(0, limit)
	};
});
on('GET', '/dict/headwords', (s, _m, q) => {
	const seqs = new Set(q.getAll('seq').flatMap((v) => v.split(',')).map(Number));
	return {
		items: s.data.entries
			.filter((e) => seqs.has(e.seq))
			.map((e) => ({ seq: e.seq, headword: e.headword, headword_reading: e.headword_reading, glosses: entrySummary(e).glosses }))
	};
});
on('GET', '/kanji/hot', (s) => ({ items: s.data.hot_kanji }));
on('GET', '/kanji/:literal', (s, m) => kanjiOf(s, decodeURIComponent(m[1])));
on('GET', '/kanji/:literal/words', (s, m, q) => {
	const lit = decodeURIComponent(m[1]);
	const mine = q.get('mine') === 'true';
	const words = s.data.entries
		.filter((e) => e.headword.includes(lit) && (!mine || e.note_id !== null))
		.map((e) => ({ ...entrySummary(e), decks: [] }));
	return page(words, q);
});

// notes
on('GET', '/notes', (s, _m, q) => {
	const text = q.get('q') ?? '';
	const type = q.get('type');
	const tag = q.get('tag');
	const hits = s.data.notes.filter(
		(n) =>
			(!type || n.type === type) &&
			(!tag || n.tags.includes(tag)) &&
			(text === '' || matches([n.fields, entryOf(s, n.entry_seq)?.headword, n.kanji_literal], text))
	);
	return page(hits, q, 500);
});
on('GET', '/notes/:id', (s, m) => findNote(s, m[1]));
on('POST', '/notes', (s, _m, _q, b) => {
	const nt = s.data.note_types.find((t) => t.id === b.note_type_id);
	const id = (b.id as string | undefined) ?? uuid();
	if (s.data.notes.some((n) => n.id === id)) throw new HttpError(409, 'duplicate', 'This note already exists.');
	const kinds = Object.entries((nt?.templates ?? {}) as Record<string, { default: boolean }>)
		.filter(([, t]) => t.default)
		.map(([k]) => k);
	const n: WireNote = {
		id,
		note_type_id: String(b.note_type_id),
		type: String(nt?.base_type ?? 'vocab'),
		language: 'ja',
		fields: (b.fields as Record<string, string>) ?? {},
		tags: (b.tags as string[]) ?? [],
		mnemonic: (b.mnemonic as string) ?? '',
		entry_seq: (b.entry_seq as number | undefined) ?? null,
		kanji_literal: (b.kanji_literal as string | undefined) ?? null,
		grammar_point_id: (b.grammar_point_id as string | undefined) ?? null,
		source_sentence: (b.source_sentence as string) ?? '',
		source_sentence_id: (b.source_sentence_id as string | undefined) ?? null,
		source_media_id: (b.source_media_id as string | undefined) ?? null,
		origin: (b.origin as string) ?? 'manual',
		merged_into_id: null,
		created_by_device_id: '00000000-0000-7000-8000-000000000001',
		created_at: now(),
		updated_at: now(),
		deleted_at: null,
		cards: []
	};
	n.cards = kinds.map((kind) => newCard(n.id, kind));
	s.data.notes.unshift(n);
	const e = entryOf(s, n.entry_seq);
	if (e) e.note_id = n.id;
	return n;
});
on('PATCH', '/notes/:id', (s, m, _q, b) => {
	const n = findNote(s, m[1]);
	if (b.fields) n.fields = { ...n.fields, ...(b.fields as Record<string, string>) };
	for (const k of ['tags', 'mnemonic', 'source_sentence', 'source_sentence_id', 'source_media_id']) if (k in b) n[k] = b[k];
	n.updated_at = now();
	return n;
});
on('DELETE', '/notes/:id', (s, m) => {
	s.data.notes = s.data.notes.filter((n) => n.id !== m[1]);
	return null;
});

function newCard(noteId: string, kind: string): WireCard {
	return {
		id: uuid(),
		note_id: noteId,
		kind,
		preset_id: '00000000-0000-7000-8000-000000000010',
		state: 'new',
		step: null,
		stability: null,
		difficulty: null,
		due_at: null,
		interval_days: null,
		last_review_at: null,
		reps: 0,
		lapses: 0,
		flags: 0,
		suspended: false,
		buried_until: null,
		leech: false,
		merged_into_id: null,
		created_at: now(),
		updated_at: now(),
		deleted_at: null
	};
}

// cards
on('GET', '/cards', (s, _m, q) => {
	const query = q.get('query') ?? '';
	const hits = allCards(s).filter(({ card, note }) => {
		if (query.includes('is:leech') && !card.leech) return false;
		if (query.includes('is:suspended') && !card.suspended) return false;
		const kind = /kind:(\S+)/.exec(query)?.[1];
		if (kind && card.kind !== kind) return false;
		const tag = /tag:(\S+)/.exec(query)?.[1];
		if (tag && !note.tags.includes(tag)) return false;
		const free = query.replace(/\S+:\S+/g, '').trim();
		return free === '' || matches([note.fields, entryOf(s, note.entry_seq)?.headword, note.kanji_literal], free);
	});
	const p = page(hits, q, 500);
	return { ...p, items: p.items.map(({ card, note }) => cardWithNote(card, note)) };
});
const bulk: Handler = (s, _m, _q, b) => {
	const ids = (b.card_ids as string[] | undefined) ?? allCards(s).map(({ card }) => card.id);
	const matched = ids.length;
	return { matched, changed: Math.max(0, matched - 1), skipped: matched > 0 ? 1 : 0, ops_emitted: 0, dry_run: b.dry_run === true };
};
on('POST', '/cards/bulk', bulk);
on('POST', '/cards/bulk/schedule', bulk);

// review
function queueItem(s: State, card: WireCard, note: WireNote, reviewType = 'scheduled'): Obj {
	return {
		card: cardWithNote(card, note),
		entry: entryOf(s, note.entry_seq),
		kanji: note.kanji_literal ? kanjiOf(s, note.kanji_literal) : null,
		grammar_sentence: null,
		sentence: null,
		preview: null,
		review_type: card.state === 'learning' || card.state === 'relearning' ? 'learning' : reviewType
	};
}
on('GET', '/review/queue', (s, _m, q) => {
	const limit = Number(q.get('limit') ?? 20);
	const due = allCards(s).filter(({ card }) => !card.suspended && !s.answered.includes(card.id));
	// New cards last, like the server's default mix; enough of the queue to fill `limit`.
	due.sort((a, b) => Number(a.card.state === 'new') - Number(b.card.state === 'new'));
	const c = s.scenario.queue_counts;
	let left = s.answered.length;
	const take = (n: number): number => {
		const t = Math.min(n, left);
		left -= t;
		return n - t;
	};
	const counts = { review: take(c.review), learning: take(c.learning), new: take(c.new) };
	const total = counts.new + counts.learning + counts.review;
	return {
		study_day: '2026-11-13',
		day_ends_at: '2026-11-14T04:00:00Z',
		generated_at: now(),
		counts,
		items: due.slice(0, Math.min(limit, total)).map(({ card, note }) => queueItem(s, card, note))
	};
});
on('POST', '/review/answer', (s, _m, _q, b) => {
	const hit = allCards(s).find(({ card }) => card.id === b.card_id) ?? notFoundThrow('card');
	if (!s.answered.includes(hit.card.id)) s.answered.push(hit.card.id);
	return { card: hit.card, review: reviewLog(s, hit.card.id, b) };
});
on('POST', '/review/undo', (s, _m, _q, b) => {
	const cardId = s.answered.pop();
	const hit = allCards(s).find(({ card }) => card.id === cardId) ?? notFoundThrow('review');
	return { card: hit.card, review: { ...reviewLog(s, hit.card.id, {}), id: b.review_id, undone_at: now() } };
});
function reviewLog(s: State, cardId: string, b: Obj): Obj {
	s.reviewSeq++;
	return {
		id: (b.id as string | undefined) ?? uuid(),
		card_id: cardId,
		device_id: '00000000-0000-7000-8000-000000000001',
		reviewed_at: now(),
		rating: (b.rating as number | undefined) ?? null,
		button: (b.button as string | undefined) ?? null,
		review_type: 'scheduled',
		duration_ms: (b.duration_ms as number | undefined) ?? null,
		utc_offset_min: (b.utc_offset_min as number | undefined) ?? null,
		sentence_id: null,
		hlc: `demo-${s.reviewSeq}`,
		import_source: null,
		manual_action: null,
		manual_due_at: null,
		undone_at: null
	};
}
on('POST', '/drill/session', (s, _m, _q, b) => {
	const leeches = allCards(s).filter(({ card }) => card.leech || card.state === 'relearning');
	return {
		id: uuid(),
		mode: b.mode ?? 'drill',
		items: leeches.slice(0, 10).map(({ card, note }) => queueItem(s, card, note, 'drill'))
	};
});

// decks
on('GET', '/deck-lists', (s) => ({ items: s.data.deck_lists }));
on('GET', '/decks', (s, _m, q) => {
	const type = q.get('type');
	return { items: s.data.decks.filter((d) => !type || d.type === type) };
});
on('POST', '/decks', (s, _m, _q, b) => {
	const d: WireDeck = {
		id: uuid(),
		type: String(b.type),
		name: String(b.name ?? b.list_key ?? 'New deck'),
		language: 'ja',
		priority: b.priority ?? 5,
		enabled: b.enabled ?? true,
		query: b.query ?? null,
		list_key: b.list_key ?? null,
		media_id: null,
		item_count: 0,
		new_cards_per_day: b.new_cards_per_day ?? null,
		reviews_per_day: b.reviews_per_day ?? null,
		settings: b.settings ?? {},
		content_blob_hash: null,
		content_version: 0,
		created_at: now(),
		updated_at: now(),
		deleted_at: null
	};
	s.data.decks.push(d);
	return d;
});
on('GET', '/decks/:id', (s, m) => s.data.decks.find((d) => d.id === m[1]) ?? notFoundThrow('deck'));
on('PATCH', '/decks/:id', (s, m, _q, b) => {
	const d = s.data.decks.find((x) => x.id === m[1]) ?? notFoundThrow('deck');
	Object.assign(d, b, { updated_at: now() });
	return d;
});
on('DELETE', '/decks/:id', (s, m) => {
	s.data.decks = s.data.decks.filter((d) => d.id !== m[1]);
	return null;
});
on('GET', '/decks/:id/items', (s, m, q) => {
	const d = s.data.decks.find((x) => x.id === m[1]) ?? notFoundThrow('deck');
	if (d.type === 'smart') throw new HttpError(409, 'not_applicable', 'Smart decks have no items.');
	const statuses = q.getAll('status').flatMap((v) => v.split(','));
	let items = s.data.deck_items[d.id] ?? [];
	if (statuses.length > 0) items = items.filter((i) => statuses.includes(String(i.status)));
	if (q.get('sort') === 'occurrence_count')
		items = [...items].sort((a, b) => Number(b.occurrence_count) - Number(a.occurrence_count));
	return page(items, q);
});
on('POST', '/decks/:id/items', (s, m, _q, b) => {
	const rows = ((b.items as Obj[]) ?? []).map((i, k) => {
		const e = entryOf(s, (i.entry_seq as number | undefined) ?? null);
		return {
			deck_id: m[1],
			item_key: i.entry_seq ? `e:${i.entry_seq}` : `n:${i.note_id}`,
			entry_seq: i.entry_seq ?? null,
			note_id: i.note_id ?? null,
			position: k,
			occurrence_count: 1,
			added_at: now(),
			status: e ? 'new' : null,
			entry: e ? entrySummary(e) : null
		};
	});
	s.data.deck_items[m[1]] = [...rows, ...(s.data.deck_items[m[1]] ?? [])];
	return { items: rows };
});
on('DELETE', '/decks/:id/items/:key', (s, m) => {
	const key = decodeURIComponent(m[2]);
	s.data.deck_items[m[1]] = (s.data.deck_items[m[1]] ?? []).filter((i) => i.item_key !== key);
	return null;
});

// media
on('GET', '/media', (s, _m, q) => {
	const kind = q.get('kind');
	const text = (q.get('q') ?? '').toLowerCase();
	return page(
		s.data.media.filter((x) => (!kind || x.kind === kind) && (text === '' || x.title.toLowerCase().includes(text))),
		q
	);
});
on('GET', '/media/:id', (s, m) => s.data.media.find((x) => x.id === m[1]) ?? notFoundThrow('media'));
on('PATCH', '/media/:id', (s, m, _q, b) => {
	const x = s.data.media.find((y) => y.id === m[1]) ?? notFoundThrow('media');
	Object.assign(x, b, { updated_at: now() });
	return x;
});
on('DELETE', '/media/:id', (s, m) => {
	s.data.media = s.data.media.filter((x) => x.id !== m[1]);
	return null;
});
on('POST', '/media/:id/reparse', (s, m) => job(s, 'media-parse', { media_id: m[1] }, null));
on('GET', '/media/:id/coverage', (s, m) => s.data.coverage[m[1]] ?? notFoundThrow('coverage'));
on('GET', '/media/:id/sentences', (s, m, q) => {
	const seg = q.get('segment_id');
	const filter = q.get('filter');
	const text = q.get('q') ?? '';
	const rows = s.data.sentences.filter(
		(x) =>
			x.media_id === m[1] &&
			(!seg || x.segment_id === seg) &&
			(filter !== 'i+1' || x.unknown_count === 1) &&
			(filter !== 'i+0' || x.unknown_count === 0) &&
			x.text.includes(text)
	);
	return page(rows, q);
});
on('GET', '/sentences', (s, _m, q) => {
	const seq = Number(q.get('entry_seq'));
	return page(
		s.data.sentences.filter((x) => x.tokens.some((t) => t.seq === seq)),
		q,
		10
	);
});
on('POST', '/media/import', (s, _m, _q, b) => {
	const id = s.data.media[0]?.id ?? uuid();
	return job(s, 'media-import', { media_id: id, title: b.title }, { media_id: id, unmapped: 3 });
});
on('POST', '/media/import/jpdb', (s) =>
	job(s, 'jpdb-deck-import', { media_id: s.data.media[5]?.id ?? uuid() }, { unmapped: s.data.jpdb_vocab.length })
);
on('POST', '/import/jpdb/history', (s) =>
	job(
		s,
		'jpdb-history-import',
		{},
		{
			reviews_imported: 18234,
			reviews_skipped: 12,
			cards_created: 2140,
			word_status_set: 3311,
			demo_extra_counter: 7,
			unmapped: s.data.jpdb_vocab.map((v) => ({ spelling: v.spelling, reading: v.reading, review_count: v.occurrence_total, jpdb_vid: v.jpdb_vid })),
			kanji_unmapped: [{ character: '𠮟', card_kind: 'kanji', review_count: 4 }]
		}
	)
);
on('GET', '/import/jpdb/vocab', (s, _m, q) =>
	page(
		s.data.jpdb_vocab.filter((v) => !q.get('status') || v.resolution === q.get('status')),
		q
	)
);
on('PUT', '/import/jpdb/vocab/:vid/resolution', (s, m, _q, b) => {
	const v = s.data.jpdb_vocab.find((x) => String(x.jpdb_vid) === m[1]) ?? notFoundThrow('jpdb vocab');
	Object.assign(v, { resolution: b.resolution, entry_seq: b.entry_seq ?? null, resolved_at: now() });
	return { vocab: v, jobs: [job(s, 'media-parse', { media_id: s.data.media[5]?.id ?? null }, null)] };
});
on('GET', '/jobs/:id', (s, m) => s.jobs.get(m[1]) ?? notFoundThrow('job'));
on('PUT', '/blobs/:hash', (_s, m) => ({
	hash: m[1],
	kind: 'upload',
	origin: 'upload',
	content_type: 'application/octet-stream',
	size_bytes: 0,
	created_at: now()
}));

// words / struggle
on('GET', '/words/hot', (s) => ({ items: s.data.hot_words }));
on('GET', '/words/leeches', (s, _m, q) => {
	const p = page(
		allCards(s).filter(({ card }) => card.leech),
		q
	);
	return { ...p, items: p.items.map(({ card, note }) => cardWithNote(card, note)) };
});
on('PUT', '/words/:seq/status', (s, m, _q, b) => {
	const e = entryOf(s, Number(m[1]));
	if (e) e.status = b.status;
	return { entry_seq: Number(m[1]), status: b.status, auto_status: null, override_status: b.status, override_origin: 'manual', updated_at: now() };
});
on('GET', '/words/:seq/confusables', (s, m) => {
	const seq = Number(m[1]);
	return {
		items: s.data.confusables.filter((c) =>
			[c.note_a, c.note_b].some((n) => (n as Obj).entry_seq === seq)
		)
	};
});
on('GET', '/confusables', (s, _m, q) =>
	page(
		s.data.confusables.filter((c) => !q.get('status') || c.status === q.get('status')),
		q
	)
);
on('PUT', '/confusables/:a/:b/:reason', (s, m, _q, b) => {
	const c =
		s.data.confusables.find((x) => x.note_a_id === m[1] && x.note_b_id === m[2] && x.reason === m[3]) ??
		notFoundThrow('pair');
	Object.assign(c, { status: b.status, updated_at: now() });
	return c;
});

// grammar
on('GET', '/grammar', (s, _m, q) => page(s.data.grammar_points.filter((g) => matches(g, q.get('q') ?? '')), q));
on('POST', '/grammar', (s, _m, _q, b) => {
	const g = {
		id: b.id ?? uuid(),
		title: b.title,
		pattern: b.pattern ?? '',
		meaning: '',
		explanation: '',
		notes: '',
		jlpt: null,
		language: 'ja',
		is_stub: false,
		example_count: 0,
		detection_rules: b.detection_rules ?? [],
		links: [],
		related_ids: [],
		note_id: null,
		created_at: now(),
		updated_at: now(),
		deleted_at: null
	};
	s.data.grammar_points.push(g);
	return g;
});
on('PATCH', '/grammar/:id', (s, m, _q, b) => {
	const g = s.data.grammar_points.find((x) => x.id === m[1]) ?? notFoundThrow('grammar point');
	Object.assign(g, b, { updated_at: now() });
	return g;
});
on('POST', '/grammar/:id/rules/test', (s) => ({
	matches: s.data.sentences.slice(0, 3).map((x) => ({ sentence: x, spans: [{ start_offset: 0, end_offset: 1 }] })),
	scanned: s.data.sentences.length,
	truncated: false
}));
on('PUT', '/grammar/:id/spans/:sid/:start/:end', (_s, m, _q, b) => ({
	grammar_point_id: m[1],
	sentence_id: m[2],
	start_offset: Number(m[3]),
	end_offset: Number(m[4]),
	media_id: b.media_id ?? null,
	origin: 'manual',
	rule_index: null,
	created_at: now()
}));

// parse overrides
on('GET', '/parse-overrides', (s, _m, q) => page(s.data.parse_overrides, q, 200));
on('PUT', '/parse-overrides/:id', (s, m, _q, b) => {
	const o = { ...b, id: m[1], created_at: now(), updated_at: now(), deleted_at: null };
	s.data.parse_overrides.push(o);
	return o;
});
on('DELETE', '/parse-overrides/:id', (s, m) => {
	s.data.parse_overrides = s.data.parse_overrides.filter((o) => o.id !== m[1]);
	return null;
});

// stats
const statsPart =
	(key: string): Handler =>
	(s) =>
		(s.data.stats ?? emptyStats())[key];
on('GET', '/stats/summary', statsPart('summary'));
on('GET', '/stats/heatmap', statsPart('heatmap'));
on('GET', '/stats/retention', statsPart('retention'));
on('GET', '/stats/forecast', statsPart('forecast'));
on('GET', '/stats/known-words', statsPart('known_words'));
on('GET', '/stats/calibration', statsPart('calibration'));

// devices / tokens
on('GET', '/devices', (s) => ({ items: s.data.devices }));
on('POST', '/devices/pairings', () => ({
	code: '482-913',
	expires_at: new Date(Date.now() + 5 * 60_000).toISOString(),
	pairing_uri: 'kotobaya://pair?server=https%3A%2F%2Fdemo.invalid&code=482913'
}));
on('PATCH', '/devices/:id', (s, m, _q, b) => {
	const d = s.data.devices.find((x) => x.id === m[1]) ?? notFoundThrow('device');
	Object.assign(d, b);
	return d;
});
on('DELETE', '/devices/:id', (s, m) => {
	const d = s.data.devices.find((x) => x.id === m[1]);
	if (d) d.revoked_at = now();
	return null;
});
on('GET', '/tokens', (s) => ({ items: s.data.tokens }));
on('POST', '/tokens', (s, _m, _q, b) => {
	const info = {
		id: uuid(),
		name: b.name,
		kind: 'personal',
		scopes: b.scopes,
		token_prefix: 'kby_p_demo',
		device_id: null,
		created_at: now(),
		last_used_at: null,
		expires_at: b.expires_at ?? null,
		revoked_at: null,
		active: true
	};
	s.data.tokens.push(info);
	return { info, token: 'kby_p_demo_0123456789abcdef0123456789abcdef' };
});
on('DELETE', '/tokens/:id', (s, m) => {
	const t = s.data.tokens.find((x) => x.id === m[1]);
	if (t) Object.assign(t, { revoked_at: now(), active: false });
	return null;
});

/* ---------------------------------------------------------------- middleware */

const COOKIE = 'kb-mock-scenario';

// The node request/response surface this middleware uses (the web package has no @types/node).
interface Req extends AsyncIterable<Uint8Array> {
	url?: string;
	method?: string;
	headers: Record<string, string | string[] | undefined>;
	on(event: 'close', fn: () => void): void;
}
interface Res {
	statusCode: number;
	setHeader(name: string, value: string): void;
	writeHead(status: number, headers: Record<string, string>): void;
	write(chunk: string): void;
	end(body?: string): void;
}

function scenarioOf(req: Req, fallback: string): string {
	const cookie = String(req.headers.cookie ?? '');
	const m = new RegExp(`(?:^|;\\s*)${COOKIE}=([\\w-]+)`).exec(cookie);
	return m && SCENARIOS.includes(m[1]) ? m[1] : fallback;
}

async function readBody(req: Req): Promise<Obj> {
	const chunks: Uint8Array[] = [];
	for await (const c of req) chunks.push(c);
	const text = new TextDecoder().decode(
		chunks.reduce((a, b) => {
			const out = new Uint8Array(a.length + b.length);
			out.set(a);
			out.set(b, a.length);
			return out;
		}, new Uint8Array())
	);
	if (!text) return {};
	try {
		const parsed: unknown = JSON.parse(text);
		return typeof parsed === 'object' && parsed !== null ? (parsed as Obj) : {};
	} catch {
		return {};
	}
}

/** Vite plugin: serves the demo API under /api/v1 on the dev and preview servers. */
export function demoApi(defaultScenario: string): Plugin {
	const states = new Map<string, State>();
	const stateFor = (name: string): State => {
		let s = states.get(name);
		if (!s) {
			s = load(name);
			states.set(name, s);
		}
		return s;
	};

	const middleware = (req: Req, res: Res, next: () => void): void => {
		const url = new URL(req.url ?? '/', 'http://localhost');
		const send = (status: number, body: unknown, type = 'application/json'): void => {
			res.statusCode = status;
			res.setHeader('content-type', type);
			res.end(body === null ? '' : typeof body === 'string' ? body : JSON.stringify(body));
		};

		const switchTo = /^\/__mock\/scenario\/([\w-]+)$/.exec(url.pathname);
		if (switchTo) {
			const name = SCENARIOS.includes(switchTo[1]) ? switchTo[1] : defaultScenario;
			states.delete(name); // a fresh copy
			res.statusCode = 302;
			res.setHeader('set-cookie', `${COOKIE}=${name}; Path=/; SameSite=Lax`);
			res.setHeader('location', url.searchParams.get('to') ?? '/');
			res.end();
			return;
		}
		if (!url.pathname.startsWith('/api/v1/')) return next();
		const path = url.pathname.slice('/api/v1'.length);
		const s = stateFor(scenarioOf(req, defaultScenario));

		if (path === '/events') {
			// One hello, then keep the stream open (no live updates in demo mode).
			res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
			res.write(`event: hello\ndata: ${JSON.stringify({ type: 'hello', at: now(), data: { scenario: s.name } })}\n\n`);
			const ping = setInterval(() => res.write(': ping\n\n'), 15_000);
			req.on('close', () => clearInterval(ping));
			return;
		}

		const method = req.method ?? 'GET';
		const route = routes.find((r) => r.method === method && r.re.test(path));
		void (async () => {
			try {
				if (!route) throw new HttpError(501, 'not_implemented', `${method} ${path} is not simulated in demo mode`);
				const body = method === 'GET' || method === 'DELETE' ? {} : await readBody(req);
				const result = route.fn(s, route.re.exec(path) ?? [], url.searchParams, body);
				if (result === null || result === undefined) send(204, null);
				else send(200, result);
			} catch (e) {
				const err = e instanceof HttpError ? e : new HttpError(500, 'mock_error', String(e));
				send(
					err.status,
					{
						type: `urn:kotobaya:problem:${err.code}`,
						title: err.code,
						status: err.status,
						code: err.code,
						detail: err.detail,
						request_id: 'demo'
					},
					'application/problem+json'
				);
			}
		})();
	};

	return {
		name: 'kotobaya-demo-api',
		configureServer(server) {
			server.middlewares.use(middleware as unknown as Connect.NextHandleFunction);
		},
		configurePreviewServer(server) {
			server.middlewares.use(middleware as unknown as Connect.NextHandleFunction);
		}
	};
}
