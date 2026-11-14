// Note browser state (/notes): search, paging, the edit pane with its core-rendered preview.
import type { DictEntry, Note, NoteBaseType, NotePatch, NoteType } from '../api/generated';
import { fetchHeadwords } from '../cards/api';
import { PAGE_SIZE } from '../cards/model';
import { noteTypesApi, notesApi } from '../notes/api';
import { dictFieldValues } from '../notes/dict';
import { HeadwordMap } from '../notes/headwords.svelte';
import { withPinned } from './noteLink';
import { tn } from '../notes/messages';
import { pushToast } from '../toasts.svelte';
import { DictRef, renderPreview, sortKinds, type CardSide, type Rendered } from './preview.svelte';

export type { DictEntry, Note, NoteBaseType, NoteSummary, NoteType } from '../api/generated';
export { HeadwordMap } from '../notes/headwords.svelte';
export { ROW_HEIGHT, rowLabel, windowRange } from '../cards/model';

export const BASE_TYPES: NoteBaseType[] = ['vocab', 'kanji', 'phrase', 'grammar', 'sentence', 'custom'];

/** The /notes screen. `deepLink` is the `?note=<id>` the dictionary page links with. */
export class NotesPage {
	draft = $state('');
	typeFilter = $state<NoteBaseType | ''>('');
	tagFilter = $state('');
	notes = $state<Note[]>([]);
	cursor = $state<string | null>(null);
	loading = $state(false);
	loadedOnce = $state(false);
	noteTypes = $state<Map<string, NoteType>>(new Map());
	activeId = $state<string | null>(null);
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	/** Cursor of a failed "load more", so `retry` asks for the same page again. */
	private retryCursor: string | null = null;
	readonly headwords = new HeadwordMap();
	private generation = 0;
	/** The deep-linked note, kept in the list across the initial page load (which would otherwise replace it). */
	private pinned: Note | null = null;
	private appliedFilter = { q: '', type: '' as NoteBaseType | '', tag: '' };

	readonly active = $derived(this.notes.find((n) => n.id === this.activeId) ?? null);

	/** Call once on mount. */
	start(deepLink: string | null): void {
		void (async () => {
			try {
				this.noteTypes = new Map((await noteTypesApi.listNoteTypes({})).items.map((n) => [n.id, n]));
			} catch {
				/* toast shown; previews just report the missing type */
			}
			if (deepLink) {
				try {
					const n = await notesApi.getNote({ noteId: deepLink });
					this.pinned = n;
					this.notes = withPinned(this.notes, n);
					this.activate(n);
				} catch {
					/* toast shown */
				}
			}
		})();
		this.search();
	}

	async loadPage(reset: boolean): Promise<void> {
		if (this.loading && !reset) return;
		if (!reset && this.cursor === null) return;
		const gen = reset ? ++this.generation : this.generation;
		const applied = this.appliedFilter;
		this.loading = true;
		try {
			const res = await notesApi.listNotes({
				q: applied.q === '' ? undefined : applied.q,
				type: applied.type === '' ? undefined : applied.type,
				tag: applied.tag === '' ? undefined : applied.tag,
				limit: PAGE_SIZE,
				cursor: reset ? undefined : (this.cursor ?? undefined)
			});
			if (gen !== this.generation) return;
			this.loadFailed = false;
			this.notes = reset ? withPinned(res.items, this.pinned) : [...this.notes, ...res.items];
			this.cursor = res.nextCursor;
			void fetchHeadwords(
				res.items.flatMap((n) => (n.entrySeq != null ? [n.entrySeq] : [])),
				(found) => this.headwords.putHeadwords(found)
			);
		} catch {
			if (gen !== this.generation) return;
			this.loadFailed = true;
			this.retryCursor = reset ? null : this.cursor;
			this.cursor = null;
		} finally {
			if (gen === this.generation) {
				this.loading = false;
				this.loadedOnce = true;
			}
		}
	}

	/** Load the failed page again (the first page, or the same "more" page). */
	retry(): void {
		if (this.retryCursor !== null) {
			this.cursor = this.retryCursor;
			this.retryCursor = null;
			void this.loadPage(false);
		} else void this.loadPage(true);
	}

	search(): void {
		this.appliedFilter = { q: this.draft.trim(), type: this.typeFilter, tag: this.tagFilter.trim() };
		this.activeId = null;
		this.pinned = null;
		this.notes = [];
		this.cursor = null;
		void this.loadPage(true);
	}

	activate(n: Note): void {
		this.activeId = n.id;
	}

	saved(n: Note): void {
		this.notes = this.notes.map((x) => (x.id === n.id ? n : x));
	}

	deleted(id: string): void {
		this.notes = this.notes.filter((x) => x.id !== id);
		this.activeId = null;
	}

	entryLoaded(seq: number, e: DictEntry): void {
		this.headwords.put(seq, e);
	}
}

function parseTags(s: string): string[] {
	return [...new Set(s.split(/\s+/).filter((x) => x !== ''))].sort();
}

export interface NoteEditorCallbacks {
	onentry: (seq: number, entry: DictEntry) => void;
	onsaved: (n: Note) => void;
	ondeleted: (id: string) => void;
}

/**
 * Edit pane: fields, tags, mnemonic, source sentence, with a core-rendered preview and delete.
 * Construct during component init; its effects read only the props (getters), never the state they write.
 */
export class NoteEditorState {
	readonly ref = new DictRef();
	fields = $state<Record<string, string>>({});
	tagsText = $state('');
	mnemonic = $state('');
	sourceSentence = $state('');
	side = $state<CardSide>('front');
	tab = $state<'preview' | 'edit'>('preview');
	kind = $state('');
	saving = $state(false);
	confirming = $state(false);

	readonly #note: () => Note;
	readonly #noteType: () => NoteType | undefined;
	readonly #cb: () => NoteEditorCallbacks;

	/** The note's live card kinds (falls back to the note type's templates). */
	readonly kinds = $derived.by(() => {
		const note = this.#note();
		const nt = this.#noteType();
		const live = [...new Set(note.cards.map((c) => c.kind))];
		// Default (first) kind follows the note type's template order.
		if (live.length === 0) return nt ? Object.keys(nt.templates) : [];
		return sortKinds(live, nt);
	});
	readonly activeKind = $derived(this.kinds.includes(this.kind) ? this.kind : (this.kinds[0] ?? ''));
	readonly dictValues = $derived(dictFieldValues(this.ref.entry, this.ref.kanji));
	readonly fieldKeys = $derived.by(() => {
		const nt = this.#noteType();
		const keys = nt ? nt.fields.map((f) => f.key) : [];
		for (const k of Object.keys(this.#note().fields)) if (!keys.includes(k)) keys.push(k);
		return keys;
	});
	readonly referenced = $derived.by(() => this.#note().entrySeq !== null || this.#note().kanjiLiteral !== null);
	readonly tagsValid = $derived(parseTags(this.tagsText).every((x) => x.length <= 64 && x !== 'leech'));
	readonly rendered = $derived.by((): Rendered => {
		const nt = this.#noteType();
		if (!nt) return { ok: false, detail: tn('notes.previewMissingType') };
		return renderPreview({
			noteType: nt,
			kind: this.activeKind,
			fields: this.fields,
			mnemonic: this.mnemonic,
			entry: this.ref.entry,
			kanji: this.ref.kanji,
			side: this.side,
			fieldRegions: true
		});
	});

	constructor(note: () => Note, noteType: () => NoteType | undefined, callbacks: () => NoteEditorCallbacks) {
		this.#note = note;
		this.#noteType = noteType;
		this.#cb = callbacks;

		// Reset the form whenever another note (or a fresh server copy of it) arrives.
		$effect(() => {
			const n = note();
			this.fields = { ...n.fields };
			this.tagsText = n.tags.join(' ');
			this.mnemonic = n.mnemonic;
			this.sourceSentence = n.sourceSentence;
			this.confirming = false;
		});
		$effect(() => {
			void note().id;
			this.side = 'front';
			this.tab = 'preview';
			this.kind = '';
		});
		// Referenced notes render from the dictionary: fetch (cached) whenever another note is opened.
		$effect(() => {
			const n = note();
			return this.ref.load(n.entrySeq, n.kanjiLiteral, (seq, e) => callbacks().onentry(seq, e));
		});
	}

	kindLabel(k: string): string {
		return k === 'meaning' || k === 'reading' ? tn(`notes.kind.${k}`) : k;
	}

	labelOf(key: string): string {
		return this.#noteType()?.fields.find((f) => f.key === key)?.label ?? key;
	}

	async save(): Promise<void> {
		const note = this.#note();
		const patch: NotePatch = {};
		const changed: Record<string, string> = {};
		for (const k of this.fieldKeys) {
			if ((this.fields[k] ?? '') !== (note.fields[k] ?? '')) changed[k] = this.fields[k] ?? '';
		}
		if (Object.keys(changed).length > 0) patch.fields = changed;
		const tags = parseTags(this.tagsText);
		if (tags.join(' ') !== note.tags.join(' ')) patch.tags = tags;
		if (this.mnemonic !== note.mnemonic) patch.mnemonic = this.mnemonic;
		if (this.sourceSentence !== note.sourceSentence) {
			// One register covers all three source properties: keep the soft references.
			patch.sourceSentence = this.sourceSentence;
			patch.sourceSentenceId = note.sourceSentenceId;
			patch.sourceMediaId = note.sourceMediaId;
		}
		if (Object.keys(patch).length === 0) {
			pushToast(tn('notes.nothingChanged'), 'info');
			return;
		}
		this.saving = true;
		try {
			const updated = await notesApi.updateNote({ noteId: note.id, notePatch: patch });
			pushToast(tn('notes.saved'), 'info');
			this.#cb().onsaved(updated);
		} catch {
			/* error toast shown by the client */
		} finally {
			this.saving = false;
		}
	}

	async remove(): Promise<void> {
		const note = this.#note();
		this.saving = true;
		try {
			await notesApi.deleteNote({ noteId: note.id });
			pushToast(tn('notes.deleted'), 'info');
			this.#cb().ondeleted(note.id);
		} catch {
			/* error toast shown by the client */
		} finally {
			this.saving = false;
			this.confirming = false;
		}
	}
}
