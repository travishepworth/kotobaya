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
	JsNoteType,
	JsPreset,
	JsRenderInput,
	JsSearchVocabulary,
	JsTemplate,
	kotobayaCore,
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
			k ? new JsKanji(k.literal, [...k.meanings], [...k.onyomi], [...k.kunyomi], k.strokeCount) : null
		)
	);
	return { html: out.html, audio: [...out.audio], extraCss: out.extraCss, warnings: [...out.warnings] };
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
