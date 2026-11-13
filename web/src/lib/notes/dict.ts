// Pure helpers: dictionary-derived values for notes (no I/O).
import type { DictEntry, Kanji } from '../api/generated';

/** What the page-level headword map holds per dictionary entry. */
export interface HeadwordInfo {
	headword: string;
	reading: string;
	/** First gloss of the first sense; '' when the entry has none. */
	gloss: string;
}

export function headwordInfo(e: DictEntry): HeadwordInfo {
	return {
		headword: e.headword,
		reading: e.headwordReading,
		gloss: e.senses[0]?.gloss[0]?.text ?? ''
	};
}

/** Dictionary-supplied value per note-type field key, used as read-only display/placeholder. */
export function dictFieldValues(e: DictEntry | null, k: Kanji | null): Record<string, string> {
	const out: Record<string, string> = {};
	if (e) {
		out.expression = e.headword;
		out.reading = e.headwordReading;
		out.meaning = e.senses
			.map((s) => s.gloss.map((g) => g.text).join(', '))
			.filter((x) => x !== '')
			.join('; ');
	}
	if (k) {
		out.literal = k.literal;
		out.keyword = k.meanings.join(', ');
		out.readings = [...k.onReadings, ...k.kunReadings].join('、');
	}
	return out;
}
