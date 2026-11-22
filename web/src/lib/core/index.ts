/**
 * The web's only door to the shared Kotlin core (ADR-002, T213b).
 *
 * Rules:
 * - This is the single module in the web app that imports `@kotobaya/core`. Every other module
 *   imports from here (`../lib/core/index`, relative path: SvelteKit 3 has no `$lib` alias).
 * - Domain logic (card rendering and sanitising, search parsing and completion, FSRS previews)
 *   lives in the core. Never re-implement any of it in TypeScript; if something is missing, the
 *   core gains it (CONTRACT_CHANGE_REQUEST against `contracts/js/CoreJs.kt`).
 * - This module only converts between plain TypeScript objects and the `Js*` DTOs. Instants are
 *   epoch milliseconds (integral, non-negative; the core throws otherwise).
 * - Calls are pure and synchronous. Invalid host input (unknown side, base type or card state)
 *   throws a JS Error from the core; that is a caller bug, not a user error.
 */
import {
	JsCardSchedule,
	JsCloze,
	JsDictionary,
	JsField,
	JsFurigana,
	JsKanji,
	JsLoopInput,
	JsNoteType,
	JsPendingAnswer,
	JsPreset,
	JsRenderInput,
	JsSearchVocabulary,
	JsTemplate,
	JsUntilStopParts,
	kotobayaCore,
	presentLoop as corePresentLoop,
	type CoreJs
} from '@kotobaya/core';

let instance: CoreJs | undefined;

function core(): CoreJs {
	instance ??= kotobayaCore();
	return instance;
}

/* ---------- card rendering ---------- */

export type CardSide = 'front' | 'back';
export type NoteBaseType = 'vocab' | 'kanji' | 'phrase' | 'grammar' | 'sentence' | 'custom';

export interface Template {
	kind: string;
	isDefault: boolean;
	/** Custom front HTML, or null for the built-in template. */
	front: string | null;
	/** Custom back HTML, or null for the built-in template. */
	back: string | null;
}

export interface NoteType {
	baseType: NoteBaseType;
	fieldKeys: string[];
	templates: Template[];
	css: string;
}

export interface FuriganaSegment {
	text: string;
	/** Reading, or null for kana. */
	reading: string | null;
}

export interface DictionaryData {
	expression: string;
	reading: string;
	furigana: FuriganaSegment[];
	glosses: string[];
	pitchDrops: number[];
}

export interface KanjiData {
	literal: string;
	meanings: string[];
	onyomi: string[];
	kunyomi: string[];
	strokeCount: number | null;
}

export interface ClozeSentence {
	sentenceId: string | null;
	text: string;
	/** UTF-16 start of the cloze span. */
	start: number;
	/** UTF-16 end of the cloze span (exclusive). */
	end: number;
	translation: string | null;
}

export interface RenderOptions {
	furigana: boolean;
	pitch: boolean;
	showMnemonic: boolean;
	/** e.g. `/api/v1/blobs/{sha256}`. */
	mediaUrlTemplate: string;
	/**
	 * Field regions (C-19, WYSIWYG note view): every field-value wrapper span gets
	 * `data-field="<key>"` and `data-field-source="note|dictionary|kanji"`, and `RenderedCard.fields`
	 * is filled. Omitted or false: the review output, unchanged.
	 */
	fieldRegions?: boolean;
}

/** `note`: the note's own value; `dictionary` / `kanji`: filled from reference data; `empty`: nothing to show. */
export type FieldSource = 'note' | 'dictionary' | 'kanji' | 'empty';

/** One field the rendered side refers to (core `RenderedField`). */
export interface RenderedField {
	/** `note.fields` key, e.g. `keyword`. */
	key: string;
	source: FieldSource;
	/** True iff `html` holds at least one `[data-field="<key>"]` region. */
	rendered: boolean;
}

export interface RenderInput {
	noteType: NoteType;
	kind: string;
	/** Note fields: key → HTML fragment (as `note.fields` comes from the API). */
	fields: Record<string, string>;
	mnemonic: string;
	dictionary?: DictionaryData | null;
	cloze?: ClozeSentence | null;
	/** Kanji data (from `QueueItem.kanji`) or null. */
	kanji?: KanjiData | null;
	side: CardSide;
	options: RenderOptions;
	/** `QueueItem.front_furigana` (ADR-042), passed through unchanged; absent = false. */
	frontFurigana?: boolean;
	/** `QueueItem.word_audio.blob_hash` (ADR-047); absent or null = none. */
	wordAudio?: string | null;
}

export interface RenderedCard {
	/** Sanitised HTML fragment; safe to assign to `innerHTML` inside the card container. */
	html: string;
	/** Audio blob hashes in document order. */
	audio: string[];
	/** Note type CSS to append to the card stylesheet. */
	extraCss: string;
	/** Warning i18n keys. */
	warnings: string[];
	/**
	 * With `options.fieldRegions`: the fields this side refers to (also ones in hidden sections), one
	 * entry per key in template order; otherwise empty. A region's text is the rendered form of the
	 * value: an editor loads `note.fields[key]`, never the region's content.
	 */
	fields: RenderedField[];
	/** Hash `P` / the speaker replays, or null (ADR-047). */
	wordAudio: string | null;
	/** Hash to play once when this side is shown (and autoplay is on), or null (ADR-047). */
	autoplayAudio: string | null;
}

export function renderCard(input: RenderInput): RenderedCard {
	const nt = input.noteType;
	const d = input.dictionary;
	const c = input.cloze;
	const k = input.kanji;
	const out = core().renderCard(
		new JsRenderInput(
			new JsNoteType(
				nt.baseType,
				[...nt.fieldKeys],
				nt.templates.map((t) => new JsTemplate(t.kind, t.isDefault, t.front, t.back)),
				nt.css
			),
			input.kind,
			Object.entries(input.fields).map(([key, value]) => new JsField(key, value)),
			input.mnemonic,
			d
				? new JsDictionary(
						d.expression,
						d.reading,
						d.furigana.map((s) => new JsFurigana(s.text, s.reading)),
						[...d.glosses],
						[...d.pitchDrops]
					)
				: null,
			c ? new JsCloze(c.sentenceId, c.text, c.start, c.end, c.translation) : null,
			input.side,
			input.options.furigana,
			input.options.pitch,
			input.options.showMnemonic,
			input.options.mediaUrlTemplate,
			k ? new JsKanji(k.literal, [...k.meanings], [...k.onyomi], [...k.kunyomi], k.strokeCount) : null,
			input.options.fieldRegions ?? false,
			input.frontFurigana ?? false,
			input.wordAudio ?? null
		)
	);
	return {
		html: out.html,
		audio: [...out.audio],
		extraCss: out.extraCss,
		warnings: [...out.warnings],
		fields: out.fields.map((f) => ({ key: f.key, source: f.source as FieldSource, rendered: f.rendered })),
		wordAudio: out.wordAudio ?? null,
		autoplayAudio: out.autoplayAudio ?? null
	};
}

/* ---------- search ---------- */

export type SearchTokenKind = 'key' | 'value' | 'text' | 'operator' | 'negation' | 'paren' | 'error';

export interface SearchToken {
	kind: SearchTokenKind;
	/** UTF-16 start (JS string index). */
	start: number;
	/** UTF-16 end (exclusive). */
	end: number;
}

export interface SearchError {
	/** Lower snake case, e.g. `unknown_key`. */
	code: string;
	start: number;
	end: number;
	/** i18n key `search.error.<code>`. */
	messageKey: string;
}

export type ParseResult =
	| { ok: true; canonical: string; tokens: SearchToken[] }
	| { ok: false; errors: SearchError[]; tokens: SearchToken[] };

export function parseQuery(query: string): ParseResult {
	const r = core().parseSearch(query);
	const tokens = r.tokens.map((t) => ({ kind: t.kind as SearchTokenKind, start: t.start, end: t.end }));
	if (r.ok) return { ok: true, canonical: r.canonical ?? '', tokens };
	return {
		ok: false,
		errors: r.errors.map((e) => ({ code: e.code, start: e.start, end: e.end, messageKey: e.messageKey })),
		tokens
	};
}

export interface SearchVocabulary {
	deckNames: string[];
	tags: string[];
	cardKinds: string[];
	noteTypeNames: string[];
	presetNames: string[];
	fieldKeys: string[];
}

export interface Completion {
	/** Text to insert over `[replaceStart, replaceEnd)`. */
	insert: string;
	label: string;
	kind: 'key' | 'value' | 'operator';
	replaceStart: number;
	replaceEnd: number;
}

/** Completions at [cursor] (a UTF-16 offset into [query]). */
export function completeQuery(query: string, cursor: number, vocabulary: SearchVocabulary, limit = 20): Completion[] {
	const v = vocabulary;
	return core()
		.completeSearch(
			query,
			cursor,
			new JsSearchVocabulary(
				[...v.deckNames],
				[...v.tags],
				[...v.cardKinds],
				[...v.noteTypeNames],
				[...v.presetNames],
				[...v.fieldKeys]
			),
			limit
		)
		.map((c) => ({
			insert: c.insert,
			label: c.label,
			kind: c.kind as Completion['kind'],
			replaceStart: c.replaceStart,
			replaceEnd: c.replaceEnd
		}));
}

/* ---------- scheduling previews ---------- */

export type CardState = 'new' | 'learning' | 'review' | 'relearning';

/** The card's schedule cache as the API returns it, instants as epoch ms. */
export interface CardSchedule {
	state: CardState;
	step: number | null;
	stability: number | null;
	difficulty: number | null;
	dueAt: number | null;
	intervalDays: number | null;
	lastReviewAt: number | null;
	reps: number;
	lapses: number;
}

/** The card's effective preset. */
export interface Preset {
	desiredRetention: number;
	maximumIntervalDays: number;
	learningStepsSeconds: number[];
	relearningStepsSeconds: number[];
	fuzz: boolean;
	/** Seven weights, Monday first. */
	easyDays: number[];
	fsrsVersion: string | null;
	weights: number[] | null;
	weightsVersion: number;
}

export interface PreviewOptions {
	/** The review id the answer will use; gives fuzzed previews equal to the real result. Omit = unfuzzed. */
	reviewId?: string | null;
	/** The user's UTC offset in minutes for easy days; omit = UTC. */
	utcOffsetMin?: number | null;
}

export interface IntervalPreview {
	/** 1 = again … 4 = easy. */
	rating: 1 | 2 | 3 | 4;
	/** Due instant after this rating (epoch ms). */
	dueAt: number;
	/** `dueAt − now`; format through i18n. */
	intervalMs: number;
	state: CardState;
}

/** Four previews, ratings 1–4, for answering the card at [now] (epoch ms). */
export function previewIntervals(
	schedule: CardSchedule,
	preset: Preset,
	now: number,
	options: PreviewOptions = {}
): IntervalPreview[] {
	const s = schedule;
	const p = preset;
	return core()
		.previewIntervals(
			new JsCardSchedule(
				s.state,
				s.step,
				s.stability,
				s.difficulty,
				s.dueAt,
				s.intervalDays,
				s.lastReviewAt,
				s.reps,
				s.lapses
			),
			new JsPreset(
				p.desiredRetention,
				p.maximumIntervalDays,
				[...p.learningStepsSeconds],
				[...p.relearningStepsSeconds],
				p.fuzz,
				[...p.easyDays],
				p.fsrsVersion,
				p.weights ? [...p.weights] : null,
				p.weightsVersion
			),
			now,
			options.reviewId ?? null,
			options.utcOffsetMin ?? null
		)
		.map((r) => ({
			rating: r.rating as IntervalPreview['rating'],
			dueAt: r.dueAtMs,
			intervalMs: r.intervalMs,
			state: r.state as CardState
		}));
}

/* ---------- review loop presenter (C-31, ADR-051) ---------- */

/** `loop.phase` wire values (an unknown value is mapped by the caller first, openapi C4). */
export type LoopPhaseWire = 'reviewing' | 'waiting' | 'goal_reached' | 'done_for_today' | 'empty';

/** Core `LoopScreen.wire`: what the review surface shows. */
export type LoopScreen = 'card' | 'settling' | 'waiting' | 'goal_reached' | 'done_for_today' | 'empty';

/** Core `QueueItemKind` (wire, lower case): the header part a card is counted in. */
export type QueueItemKind = 'learning' | 'review' | 'new';

/**
 * `loop.until_stop_parts`: learning ready now, learning later today, reviews, new cards. ADR-054: learning + review + new
 * is `until_stop`; `later` is only the later-today hint.
 */
export interface UntilStopParts {
	learning: number;
	later: number;
	review: number;
	new: number;
}

/** Core `PendingAnswer`: an answer the applied `loop` does not reflect yet. */
export interface PendingLoopAnswer {
	kind: QueueItemKind;
	/** 1 … 4. */
	rating: number;
	/** `card.state` after the answer (its response, else the scheduler preview), or null when unknown. */
	resultingState: CardState | null;
	/** `card.due_at` after the answer (epoch ms), or null when unknown. */
	dueAt: number | null;
}

export interface LoopPresentationInput {
	/** `loop.phase` and `loop.until_stop_parts` of the last applied response. */
	state: { phase: LoopPhaseWire; untilStopParts: UntilStopParts };
	/** Oldest first; empty when none. */
	pending: PendingLoopAnswer[];
	/** The client holds a card it can show now. */
	hasLocalCard: boolean;
	/** That card's kind; null iff `hasLocalCard` is false. */
	localHeadKind: QueueItemKind | null;
	/** `day_ends_at` of the response `state` came from (epoch ms). */
	dayEndsAt: number;
}

/** Core `LoopPresentation`: render exactly this (screen, header parts, bar remainder). */
export interface LoopPresentation {
	screen: LoopScreen;
	parts: UntilStopParts;
	untilStop: number;
}

/** Core `LoopPresenter.present` (openapi `getReviewQueue` rule C7). Pure; throws only on invalid host input. */
export function presentLoop(input: LoopPresentationInput): LoopPresentation {
	const p = input.state.untilStopParts;
	const out = corePresentLoop(
		new JsLoopInput(input.state.phase, new JsUntilStopParts(p.learning, p.later, p.review, p.new)),
		input.pending.map((a) => new JsPendingAnswer(a.kind, a.rating, a.resultingState, a.dueAt)),
		input.hasLocalCard,
		input.localHeadKind,
		input.dayEndsAt
	);
	return {
		screen: out.screen as LoopScreen,
		parts: { learning: out.parts.learning, later: out.parts.later, review: out.parts.review, new: out.parts.new },
		untilStop: out.untilStop
	};
}
