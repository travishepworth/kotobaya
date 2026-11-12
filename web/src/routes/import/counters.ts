/** Known result counters in display order; any other numeric member is shown under "other". */
export const KNOWN_COUNTERS = [
	'vocabulary_total',
	'vocabulary_carded',
	'vocabulary_status_only',
	'vocabulary_en_jp_ignored',
	'notes_created',
	'notes_existing',
	'cards_created',
	'word_statuses_set',
	'kanji_total',
	'kanji_carded',
	'kanji_notes_created',
	'kanji_notes_existing',
	'kanji_cards_created',
	'kanji_cards_suspended',
	'reviews_total',
	'reviews_imported',
	'reviews_already_imported',
	'reviews_skipped',
	'reviews_unknown_grade',
	'reviews_duplicate_dropped',
	'status_events_unknown',
	'status_events_known'
] as const;

export interface Counter {
	key: string;
	value: number;
}

export interface CounterSplit {
	known: Counter[];
	other: Counter[];
}

/** Split a raw job result into known counters (fixed order) and unknown numeric extras (sorted). */
export function splitCounters(result: Record<string, unknown>): CounterSplit {
	const known: Counter[] = [];
	const seen = new Set<string>(KNOWN_COUNTERS);
	for (const key of KNOWN_COUNTERS) {
		const v = result[key];
		if (typeof v === 'number') known.push({ key, value: v });
	}
	const other: Counter[] = [];
	for (const [key, value] of Object.entries(result)) {
		if (!seen.has(key) && typeof value === 'number') other.push({ key, value });
	}
	other.sort((a, b) => a.key.localeCompare(b.key));
	return { known, other };
}

export interface UnmappedVocab {
	jpdb_vid: number;
	spelling: string;
	reading: string;
	review_count: number;
}
export interface UnmappedKanji {
	character: string;
	card_kind: string;
	review_count: number;
}

export function listOf<T>(result: Record<string, unknown>, key: string): T[] {
	const v = result[key];
	return Array.isArray(v) ? (v as T[]) : [];
}

/** Lowercase hex SHA-256 of the file bytes (the blob key). */
export async function sha256Hex(data: ArrayBuffer): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', data);
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
