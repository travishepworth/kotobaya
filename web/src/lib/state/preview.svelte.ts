// Shared preview state: core-rendered card previews and the dictionary/kanji lookup behind referenced notes.
// Used by the card browser, the note editor, the review screen and the note-type editor.
import type { DictEntry, Kanji, NoteType } from '../api/generated';
import { renderCard, type CardSide, type ClozeSentence, type RenderedCard } from '../core/index';
import { fetchEntry, fetchKanji } from '../cards/api';
import { MEDIA_URL_TEMPLATE, toCoreNoteType, toDictionary, toKanji } from '../review/model';

export type { CardSide, RenderedCard } from '../core/index';
export { MEDIA_URL_TEMPLATE } from '../review/model';

export type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };

export interface PreviewInput {
	noteType: NoteType;
	kind: string;
	fields: Record<string, string>;
	mnemonic: string;
	entry: DictEntry | null;
	kanji: Kanji | null | undefined;
	side: CardSide;
	cloze?: ClozeSentence | null;
}

/** Render one side of a card through the shared core; a renderer error becomes `{ ok: false }`. */
export function renderPreview(p: PreviewInput): Rendered {
	try {
		return {
			ok: true,
			card: renderCard({
				noteType: toCoreNoteType(p.noteType),
				kind: p.kind,
				fields: p.fields,
				mnemonic: p.mnemonic,
				dictionary: toDictionary(p.entry),
				kanji: toKanji(p.kanji),
				...(p.cloze !== undefined ? { cloze: p.cloze } : {}),
				side: p.side,
				// Furigana is switched with a CSS class in CardView, not by re-rendering.
				options: { furigana: true, pitch: true, showMnemonic: true, mediaUrlTemplate: MEDIA_URL_TEMPLATE }
			})
		};
	} catch (e) {
		return { ok: false, detail: e instanceof Error ? e.message : String(e) };
	}
}

/**
 * The dictionary entry and/or kanji a referenced note renders from (cached fetches).
 * `load` only writes this object's state, never reads it, so it is safe to call from an $effect
 * (HOTFIX-0.1.7: reading `loading` back made the effect depend on what it wrote, and it looped).
 */
export class DictRef {
	entry = $state<DictEntry | null>(null);
	kanji = $state<Kanji | null>(null);
	loading = $state(false);
	failed = $state(false);

	/** Start loading a note's references; returns the cleanup that drops a stale answer. */
	load(seq: number | null, lit: string | null, onentry?: (seq: number, entry: DictEntry) => void): () => void {
		// Test a local, never the state this writes.
		const busy = seq !== null || lit !== null;
		this.entry = null;
		this.kanji = null;
		this.failed = false;
		this.loading = busy;
		let stale = false;
		if (!busy) return () => {};
		void (async () => {
			try {
				if (seq !== null) {
					const e = await fetchEntry(seq);
					if (stale) return;
					this.entry = e;
					onentry?.(seq, e);
				}
				if (lit !== null) {
					const k = await fetchKanji(lit);
					if (stale) return;
					this.kanji = k;
				}
			} catch {
				if (!stale) this.failed = true;
			} finally {
				if (!stale) this.loading = false;
			}
		})();
		return () => {
			stale = true;
		};
	}
}

/** Sort card kinds into the note type's template order (unknown kinds last). Sorts in place. */
export function sortKinds(kinds: string[], noteType: NoteType | undefined): string[] {
	const order = noteType ? Object.keys(noteType.templates) : [];
	return kinds.sort((a, b) => {
		const ia = order.indexOf(a);
		const ib = order.indexOf(b);
		return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib);
	});
}
