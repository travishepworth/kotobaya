// Deep links into the dictionary screen:
//   `/dictionary?seq=<JMdict entry seq>` opens that entry;
//   `/dictionary?q=<text>` runs a search (used for kanji: the dictionary has no stand-alone kanji page,
//   so a kanji opens as a search for its literal, whose results are the words written with it).

export function dictionaryHref(seq: number): string {
	return `/dictionary?seq=${seq}`;
}

/** A dictionary search for `q` (a kanji literal, or any word without a known entry). */
export function dictionarySearchHref(q: string): string {
	return `/dictionary?${new URLSearchParams({ q }).toString()}`;
}

/** The entry seq a dictionary URL asks for, or null when absent/invalid. */
export function seqFromSearch(search: string): number | null {
	const raw = new URLSearchParams(search).get('seq');
	if (raw === null || !/^\d+$/.test(raw)) return null;
	return Number(raw);
}

/** The search text a dictionary URL asks for, or null when absent/blank. */
export function queryFromSearch(search: string): string | null {
	const raw = new URLSearchParams(search).get('q')?.trim() ?? '';
	return raw === '' ? null : raw;
}

/** What a word or kanji row knows about itself (notes, cards, deck items, struggle rows). */
export interface DictionaryRef {
	entrySeq?: number | null;
	kanjiLiteral?: string | null;
}

/**
 * Where "open in dictionary" goes for a row: its entry when the seq is known, else a search for its kanji
 * literal; null when neither is known (no link is shown).
 */
export function dictionaryLinkFor(ref: DictionaryRef | null | undefined): string | null {
	if (!ref) return null;
	if (ref.entrySeq != null) return dictionaryHref(ref.entrySeq);
	const k = ref.kanjiLiteral?.trim() ?? '';
	return k === '' ? null : dictionarySearchHref(k);
}
