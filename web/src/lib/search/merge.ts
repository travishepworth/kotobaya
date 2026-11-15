// Pure merge rules of the one-box search (ADR-026): dictionary hits in dictionary order, each flagged "In my
// cards" when the user has a note for it, then the user's notes that no listed word covers.
import type { DictEntrySummary, Note } from '../api/generated';
import { fieldText, noteLabel } from '../cards/model';

export interface WordRow {
	seq: number;
	headword: string;
	reading: string;
	meaning: string;
	isCommon: boolean;
	/** Word status (new, learning, known, ...). */
	status: string;
	/** True when the user has a note for the entry. */
	inMyCards: boolean;
	noteId: string | null;
}

export interface NoteRow {
	noteId: string;
	label: string;
	reading: string;
	meaning: string;
	cardCount: number;
}

export interface Merged {
	words: WordRow[];
	notes: NoteRow[];
}

/** Card-query syntax: any `key:value` / comparison term (the core parser tags these tokens as `key`). */
export function usesCardSyntax(tokens: ReadonlyArray<{ kind: string }>): boolean {
	return tokens.some((t) => t.kind === 'key');
}

export function wordRow(e: DictEntrySummary): WordRow {
	return {
		seq: e.seq,
		headword: e.headword,
		reading: e.headwordReading,
		meaning: e.glosses.join('; '),
		isCommon: e.isCommon,
		status: e.status,
		inMyCards: e.noteId !== null,
		noteId: e.noteId
	};
}

/** A note as a result row; its short id is the very last fallback. */
export function noteRow(
	n: { id: string; fields?: Record<string, string> | null; entrySeq?: number | null; kanjiLiteral?: string | null },
	cardCount: number,
	headwords?: ReadonlyMap<number, string>
): NoteRow {
	return {
		noteId: n.id,
		label: noteLabel(n, headwords) ?? n.id.slice(0, 8),
		reading: fieldText(n.fields, 'reading'),
		meaning: fieldText(n.fields, 'meaning'),
		cardCount
	};
}

/** Dictionary rows first (their order), then notes whose entry is not already listed; one row per note. */
export function mergeResults(
	dict: readonly DictEntrySummary[],
	notes: readonly Note[],
	headwords?: ReadonlyMap<number, string>
): Merged {
	const words = dict.map(wordRow);
	const listed = new Set(dict.map((e) => e.seq));
	const seen = new Set<string>();
	const rows: NoteRow[] = [];
	for (const n of notes) {
		if (n.deletedAt !== null || seen.has(n.id)) continue;
		if (n.entrySeq != null && listed.has(n.entrySeq)) continue;
		seen.add(n.id);
		rows.push(noteRow(n, n.cards.length, headwords));
	}
	return { words, notes: rows };
}
