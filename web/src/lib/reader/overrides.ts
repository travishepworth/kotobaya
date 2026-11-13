import type { OverrideToken, ParseOverridePut } from '../api/generated';

/** The part of a sentence token the override builder needs. */
export interface TokenLike {
	surface: string;
	lemma: string;
	reading: string;
	seq: number | null;
}

/** A proposed re-tokenisation of `matchText`. */
export interface OverrideEdit {
	matchText: string;
	tokens: OverrideToken[];
}

export type OverrideScope = 'sentence' | 'media' | 'global';

export interface DictChoice {
	seq: number;
	lemma: string;
	reading?: string;
}

/** The API's 422 rule: token surfaces must concatenate exactly to match_text. */
export function surfacesConcatenate(matchText: string, tokens: readonly OverrideToken[]): boolean {
	return tokens.length > 0 && tokens.map((t) => t.surface).join('') === matchText;
}

/** Split token `index` after `offset` UTF-16 units (1..length-1); the halves are unmapped. */
export function splitEdit(
	tokens: readonly TokenLike[],
	index: number,
	offset: number
): OverrideEdit | null {
	const t = tokens[index];
	if (!t || offset < 1 || offset >= t.surface.length) return null;
	const a = t.surface.slice(0, offset);
	const b = t.surface.slice(offset);
	return {
		matchText: t.surface,
		tokens: [
			{ surface: a, lemma: a, seq: null },
			{ surface: b, lemma: b, seq: null }
		]
	};
}

/** Join token `index` with the next one into a single unmapped token. */
export function joinEdit(tokens: readonly TokenLike[], index: number): OverrideEdit | null {
	const a = tokens[index];
	const b = tokens[index + 1];
	if (!a || !b) return null;
	const surface = a.surface + b.surface;
	return { matchText: surface, tokens: [{ surface, lemma: surface, seq: null }] };
}

/** Keep the token's surface but point it at another dictionary entry. */
export function changeEdit(
	tokens: readonly TokenLike[],
	index: number,
	choice: DictChoice
): OverrideEdit | null {
	const t = tokens[index];
	if (!t) return null;
	const tok: OverrideToken = { surface: t.surface, lemma: choice.lemma, seq: choice.seq };
	if (choice.reading) tok.reading = choice.reading;
	return { matchText: t.surface, tokens: [tok] };
}

export interface ScopeIds {
	mediaId: string;
	sentenceId: string;
}

/** Build the PUT body; throws if the edit would be rejected with 422 for not concatenating. */
export function buildOverridePut(
	edit: OverrideEdit,
	scope: OverrideScope,
	ids: ScopeIds,
	note?: string
): ParseOverridePut {
	if (!surfacesConcatenate(edit.matchText, edit.tokens)) {
		throw new Error('override tokens do not concatenate to match_text');
	}
	const body: ParseOverridePut = { matchText: edit.matchText, tokens: edit.tokens };
	if (scope === 'sentence') {
		body.mediaId = ids.mediaId;
		body.sentenceId = ids.sentenceId;
	} else if (scope === 'media') {
		body.mediaId = ids.mediaId;
	}
	if (note) body.note = note;
	return body;
}

/** Which scope an existing override has. */
export function scopeOf(o: { mediaId: string | null; sentenceId: string | null }): OverrideScope {
	if (o.sentenceId) return 'sentence';
	if (o.mediaId) return 'media';
	return 'global';
}
