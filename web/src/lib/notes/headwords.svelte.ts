// Page-level headword map: dictionary entry seq -> {headword, reading, gloss}.
// Filled lazily from entries fetched when a note is opened (NoteEditor -> page `onentry`).
// BUG-7c: call `putMany(entries)` once with the result of the batch lookup (C-18) for all
// entry seqs in the loaded notes; the table reads only from this map, never fetches per row.
import { SvelteMap } from 'svelte/reactivity';
import type { DictEntry } from '../api/generated';
import { headwordInfo, type HeadwordInfo } from './dict';

export class HeadwordMap {
	readonly byseq = new SvelteMap<number, HeadwordInfo>();

	get(seq: number): HeadwordInfo | undefined {
		return this.byseq.get(seq);
	}

	put(seq: number, entry: DictEntry): void {
		this.byseq.set(seq, headwordInfo(entry));
	}

	/** Batch fill: `entries` keyed by seq. */
	putMany(entries: Iterable<[number, DictEntry]>): void {
		for (const [seq, e] of entries) this.put(seq, e);
	}
}
