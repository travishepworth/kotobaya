// Glue between API types and the core wrapper's plain objects. No domain logic here: rendering and
// interval maths stay in ../core/index.
import type { DictEntry, Kanji, NoteType as ApiNoteType, Preset as ApiPreset, Card } from '../api/generated';
import type {
	CardSchedule,
	DictionaryData,
	KanjiData,
	NoteType as CoreNoteType,
	Preset as CorePreset
} from '../core/index';
import { tr } from './messages';

export const MEDIA_URL_TEMPLATE = '/api/v1/blobs/{sha256}';

export function toCoreNoteType(nt: ApiNoteType): CoreNoteType {
	return {
		baseType: nt.baseType,
		fieldKeys: nt.fields.map((f) => f.key),
		templates: Object.entries(nt.templates).map(([kind, tpl]) => ({
			kind,
			isDefault: tpl._default,
			front: tpl.front,
			back: tpl.back
		})),
		css: nt.css
	};
}

export function toCorePreset(p: ApiPreset): CorePreset {
	return {
		desiredRetention: p.desiredRetention,
		maximumIntervalDays: p.maximumIntervalDays,
		learningStepsSeconds: p.learningStepsS,
		relearningStepsSeconds: p.relearningStepsS,
		fuzz: p.fuzz,
		easyDays: p.easyDays,
		fsrsVersion: p.fsrsVersion,
		weights: p.weights,
		weightsVersion: p.weightsVersion
	};
}

export function toSchedule(c: Card): CardSchedule {
	return {
		state: c.state,
		step: c.step,
		stability: c.stability,
		difficulty: c.difficulty,
		dueAt: c.dueAt ? c.dueAt.getTime() : null,
		intervalDays: c.intervalDays,
		lastReviewAt: c.lastReviewAt ? c.lastReviewAt.getTime() : null,
		reps: c.reps,
		lapses: c.lapses
	};
}

/** The API carries no furigana alignment: the reading is applied to the whole headword. */
export function toDictionary(e: DictEntry | null): DictionaryData | null {
	if (!e) return null;
	const kana = e.headword === e.headwordReading;
	const pitch = e.pitch.find((p) => p.reading === e.headwordReading) ?? e.pitch[0];
	return {
		expression: e.headword,
		reading: e.headwordReading,
		furigana: [{ text: e.headword, reading: kana ? null : e.headwordReading }],
		glosses: e.senses.flatMap((s) => s.gloss.map((g) => g.text)),
		pitchDrops: pitch ? [...pitch.positions] : []
	};
}

export function toKanji(k: Kanji | null | undefined): KanjiData | null {
	if (!k) return null;
	return {
		literal: k.literal,
		meanings: k.meanings,
		onyomi: k.onReadings,
		kunyomi: k.kunReadings,
		strokeCount: k.strokeCount
	};
}

/** Grade buttons: label key, API rating and (5-button mode only) the `button` to send. */
export interface GradeButton {
	label: 'again' | 'hard' | 'good' | 'easy' | 'nothing' | 'something' | 'okay';
	rating: 1 | 2 | 3 | 4;
	button: GradeButton['label'] | null;
}

export const BUTTONS_4: GradeButton[] = [
	{ label: 'again', rating: 1, button: null },
	{ label: 'hard', rating: 2, button: null },
	{ label: 'good', rating: 3, button: null },
	{ label: 'easy', rating: 4, button: null }
];

export const BUTTONS_5: GradeButton[] = [
	{ label: 'nothing', rating: 1, button: 'nothing' },
	{ label: 'something', rating: 1, button: 'something' },
	{ label: 'hard', rating: 2, button: 'hard' },
	{ label: 'okay', rating: 3, button: 'okay' },
	{ label: 'easy', rating: 4, button: 'easy' }
];

export function formatInterval(ms: number): string {
	const min = ms / 60000;
	if (min < 1) return tr('review.interval.now');
	if (min < 60) return tr('review.interval.minutes', { n: Math.round(min) });
	const h = min / 60;
	if (h < 24) return tr('review.interval.hours', { n: Math.round(h) });
	const d = h / 24;
	if (d < 30) return tr('review.interval.days', { n: Math.round(d) });
	if (d < 365) return tr('review.interval.months', { n: Math.round(d / 30) });
	return tr('review.interval.years', { n: Math.round((d / 365) * 10) / 10 });
}

/** A fresh UUIDv7 (the answer id must be one and is reused for retries and the fuzzed preview). */
export function uuidv7(now: number = Date.now()): string {
	const b = new Uint8Array(16);
	crypto.getRandomValues(b);
	let ms = now;
	for (let i = 5; i >= 0; i--) {
		b[i] = ms % 256;
		ms = Math.floor(ms / 256);
	}
	b[6] = (b[6] & 0x0f) | 0x70;
	b[8] = (b[8] & 0x3f) | 0x80;
	const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
