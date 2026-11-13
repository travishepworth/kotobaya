import type { Sentence } from '../api/generated';

export function formatMs(ms: number): string {
	const s = Math.floor(ms / 1000);
	const h = Math.floor(s / 3600);
	const m = Math.floor((s % 3600) / 60);
	const sec = s % 60;
	const mm = String(m).padStart(h > 0 ? 2 : 1, '0');
	const ss = String(sec).padStart(2, '0');
	return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Text pieces of a sentence: tokens and the untokenised gaps between them (offsets are UTF-16). */
export interface Piece {
	text: string;
	tokenIndex: number | null;
}

export function pieces(sentence: Pick<Sentence, 'text' | 'tokens'>): Piece[] {
	const out: Piece[] = [];
	let pos = 0;
	sentence.tokens.forEach((t, i) => {
		if (t.s > pos) out.push({ text: sentence.text.slice(pos, t.s), tokenIndex: null });
		out.push({ text: sentence.text.slice(t.s, t.e), tokenIndex: i });
		pos = Math.max(pos, t.e);
	});
	if (pos < sentence.text.length) out.push({ text: sentence.text.slice(pos), tokenIndex: null });
	return out;
}
