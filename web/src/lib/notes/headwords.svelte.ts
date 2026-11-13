// Page-level headword map: dictionary entry seq -> {headword, reading, gloss}.
// Filled by one batch call per loaded page (`putHeadwords`) and from entries fetched when a note is opened (`onentry`).
import { SvelteMap } from 'svelte/reactivity';
import type { DictEntry, DictHeadword } from '../api/generated';
import { headwordInfo, type HeadwordInfo } from './dict';

export class HeadwordMap {
	readonly byseq = new SvelteMap<number, HeadwordInfo>();

	get(seq: number): HeadwordInfo | undefined {
		return this.byseq.get(seq);
	}

	put(seq: number, entry: DictEntry): void {
		this.byseq.set(seq, headwordInfo(entry));
	}

	/** Fill from the slim batch shape (listDictHeadwords). */
	putHeadwords(items: Iterable<DictHeadword>): void {
		for (const h of items) this.byseq.set(h.seq, { headword: h.headword, reading: h.headwordReading, gloss: h.glosses[0] ?? '' });
	}

	/** Batch fill: `entries` keyed by seq. */
	putMany(entries: Iterable<[number, DictEntry]>): void {
		for (const [seq, e] of entries) this.put(seq, e);
	}
}
