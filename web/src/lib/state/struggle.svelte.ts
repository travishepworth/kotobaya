// Struggle screens (/struggle, /struggle/leeches, /struggle/confusables, /struggle/word/[noteId]).
import type { Card, Confusable, ConfusableStatus, HotKanji, HotWord, NoteSummary, Sentence } from '../api/generated';
import { apiConfig, ResponseError } from '../api/client';
import { MediaApi } from '../api/generated';
import { notesApi } from '../notes/api';
import { wordsApi } from '../review/api';
import { uuidv7 } from '../review/model';
import { buildSentenceNote } from '../sentences/noteRequest';
import { pushToast } from '../toasts.svelte';
import { tg } from '../../routes/struggle/messages';

export type { Card, Confusable, ConfusableStatus, HotKanji, HotWord, NoteSummary, Sentence } from '../api/generated';

const media = new MediaApi(apiConfig);

/** /struggle: hot words and hot kanji. */
export class StrugglePage {
	words = $state<HotWord[]>([]);
	kanji = $state<HotKanji[]>([]);
	loaded = $state(false);

	start(): void {
		void (async () => {
			try {
				const [w, k] = await Promise.all([wordsApi.listHotWords({ limit: 50 }), wordsApi.listHotKanji({ limit: 30 })]);
				this.words = w.items;
				this.kanji = k.items;
			} catch {
				/* toast shown */
			} finally {
				this.loaded = true;
			}
		})();
	}
}

/** /struggle/leeches */
export class LeechesPage {
	cards = $state<Card[]>([]);
	cursor = $state<string | null>(null);
	loaded = $state(false);
	busy = $state(false);

	/** Safe to call from an effect: reads no state before its first await unless `more`. */
	async load(more: boolean): Promise<void> {
		this.busy = true;
		try {
			const page = await wordsApi.listLeeches({ limit: 50, cursor: more ? (this.cursor ?? undefined) : undefined });
			const merged = more ? [...this.cards, ...page.items] : page.items;
			// Cursor paging has no snapshot: de-duplicate by id, last occurrence wins.
			this.cards = [...new Map(merged.map((c) => [c.id, c])).values()];
			this.cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
			this.loaded = true;
		}
	}
}

export function leechLabel(c: Card): string {
	const n = c.note;
	return n?.fields['word'] ?? n?.fields['sentence'] ?? n?.kanjiLiteral ?? c.noteId;
}

export function noteName(n: NoteSummary): string {
	return n.fields['word'] ?? n.fields['sentence'] ?? n.kanjiLiteral ?? n.id;
}

export const CONFUSABLE_STATUSES: ConfusableStatus[] = ['suggested', 'confirmed', 'rejected'];

/** /struggle/confusables */
export class ConfusablesPage {
	pairs = $state<Confusable[]>([]);
	cursor = $state<string | null>(null);
	status = $state<ConfusableStatus>('suggested');
	loaded = $state(false);
	busy = $state(false);

	constructor() {
		// Reload whenever the status filter changes (load reads `status`, which is the intended dependency).
		$effect(() => {
			void this.status;
			void this.load(false);
		});
	}

	async load(more: boolean): Promise<void> {
		this.busy = true;
		try {
			const page = await wordsApi.listConfusables({
				status: this.status,
				limit: 50,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			this.pairs = more ? [...this.pairs, ...page.items] : page.items;
			this.cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
			this.loaded = true;
		}
	}

	async decide(p: Confusable, to: 'confirmed' | 'rejected'): Promise<void> {
		this.busy = true;
		try {
			await wordsApi.setConfusable({
				noteAId: p.noteAId,
				noteBId: p.noteBId,
				reason: p.reason,
				confusableSet: { status: to }
			});
			// The pair leaves the current filter (or changes status): drop it from the view.
			this.pairs = this.pairs.filter(
				(x) => !(x.noteAId === p.noteAId && x.noteBId === p.noteBId && x.reason === p.reason)
			);
			pushToast(tg('st.pairUpdated'), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}
}

/** /struggle/word/[noteId] */
export class StruggleWordPage {
	noteId = $state('');
	word = $state<HotWord | null>(null);
	sentences = $state<Sentence[]>([]);
	partners = $state<Confusable[]>([]);
	mnemonic = $state('');
	loaded = $state(false);
	busy = $state(false);

	/** Load the word for `id` (call from an effect on the route param; reads no state). */
	load(id: string): void {
		this.noteId = id;
		void (async () => {
			try {
				const hot = (await wordsApi.listHotWords({ limit: 200 })).items.find((w) => w.noteId === id) ?? null;
				this.word = hot;
				if (hot) {
					this.mnemonic = hot.note.mnemonic;
					if (hot.entrySeq !== null) {
						const [s, c] = await Promise.all([
							media.listSentences({ entrySeq: hot.entrySeq, limit: 10 }),
							wordsApi.listWordConfusables({ seq: hot.entrySeq })
						]);
						this.sentences = s.items;
						this.partners = c.items;
					}
				}
			} catch {
				/* toast shown */
			} finally {
				this.loaded = true;
			}
		})();
	}

	async makeCard(s: Sentence): Promise<void> {
		this.busy = true;
		try {
			await notesApi.createNote({ noteCreate: buildSentenceNote(s, { id: uuidv7() }) });
			pushToast(tg('st.cardCreated'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) pushToast(tg('st.duplicate'), 'info');
		} finally {
			this.busy = false;
		}
	}

	async saveMnemonic(): Promise<void> {
		this.busy = true;
		try {
			await notesApi.updateNote({ noteId: this.noteId, notePatch: { mnemonic: this.mnemonic } });
			pushToast(tg('st.mnemonicSaved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	partner(c: Confusable): NoteSummary {
		return c.noteAId === this.noteId ? c.noteB : c.noteA;
	}
}
