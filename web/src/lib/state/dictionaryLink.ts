// Deep link into the dictionary screen: `/dictionary?seq=<JMdict entry seq>` opens that entry.

export function dictionaryHref(seq: number): string {
	return `/dictionary?seq=${seq}`;
}

/** The entry seq a dictionary URL asks for, or null when absent/invalid. */
export function seqFromSearch(search: string): number | null {
	const raw = new URLSearchParams(search).get('seq');
	if (raw === null || !/^\d+$/.test(raw)) return null;
	return Number(raw);
}
