// Note-type screens (/note-types, /note-types/new, /note-types/[id]) and the shared editor with its live preview.
import { goto } from '$app/navigation';
import { untrack } from 'svelte';
import type { NoteType } from '../api/generated';
import { ResponseError } from '../api/client';
import { renderCard, type RenderedCard } from '../core/index';
import { noteTypesApi } from '../note-types/api';
import {
	emptyDraft,
	fieldsOf,
	KEY_PATTERN,
	previewOptions,
	sampleFields,
	templatesOf,
	toCoreNoteType,
	toCreate,
	toDraft,
	validate,
	type NoteTypeDraft
} from '../note-types/draft';
import { tn, type NoteTypeMessageKey } from '../note-types/messages';
import { pushToast } from '../toasts.svelte';

export type { NoteType } from '../api/generated';
export { BASE_TYPES } from '../note-types/draft';
export type { FieldKind, NoteTypeDraft } from '../note-types/draft';

/** /note-types */
export class NoteTypesListPage {
	items = $state<NoteType[]>([]);
	loaded = $state(false);
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);

	start(): void {
		this.loaded = false;
		this.loadFailed = false;
		void (async () => {
			try {
				this.items = (await noteTypesApi.listNoteTypes({})).items;
			} catch {
				this.loadFailed = true; // the client already toasted
			} finally {
				this.loaded = true;
			}
		})();
	}
	/** Load again after a failure. */
	retry(): void {
		this.start();
	}

}

/** /note-types/new */
export class NoteTypeNewPage {
	draft = $state(emptyDraft());
	busy = $state(false);

	async create(): Promise<void> {
		this.busy = true;
		try {
			const nt = await noteTypesApi.createNoteType({ noteTypeCreate: toCreate(this.draft) });
			pushToast(tn('nt.created'), 'info');
			await goto(`/note-types/${nt.id}`, { replaceState: true });
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}
}

/** /note-types/[id] */
export class NoteTypeEditPage {
	id = $state('');
	draft = $state<NoteTypeDraft | null>(null);
	builtin = $state(false);
	missing = $state(false);
	busy = $state(false);

	/** Load the note type `noteTypeId` (call from an effect on the route param; reads no state). */
	load(noteTypeId: string): void {
		this.id = noteTypeId;
		void (async () => {
			try {
				const nt = await noteTypesApi.getNoteType({ noteTypeId });
				this.draft = toDraft(nt);
				this.builtin = nt.builtin;
			} catch (e) {
				if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
					this.missing = true;
				}
			}
		})();
	}

	async save(): Promise<void> {
		const draft = this.draft;
		if (!draft) return;
		this.busy = true;
		try {
			const nt = await noteTypesApi.updateNoteType({
				noteTypeId: this.id,
				noteTypePatch: {
					name: draft.name.trim(),
					fields: fieldsOf(draft),
					templates: templatesOf(draft),
					css: draft.css
				}
			});
			this.draft = toDraft(nt);
			pushToast(tn('nt.saved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	/** Delete (the view asks for confirmation first). */
	async remove(): Promise<void> {
		try {
			await noteTypesApi.deleteNoteType({ noteTypeId: this.id });
			pushToast(tn('nt.deleted'), 'info');
			await goto('/note-types', { replaceState: true });
		} catch {
			/* toast shown (built-in: 409) */
		}
	}
}

type Preview = { ok: true; card: RenderedCard } | { ok: false; detail: string };

const WARNING_KEYS: Record<string, true> = {
	'nt.warning.unknown_placeholder': true,
	'nt.warning.dropped_tag': true
};

/** Text for a core render warning key. */
export function warningText(key: string): string {
	const code = key.replace(/^render\.warning\./, '');
	const k = `nt.warning.${code}`;
	return k in WARNING_KEYS ? tn(k as NoteTypeMessageKey) : tn('nt.warning.other', { code });
}

/**
 * The note-type editor's working state: preview controls, sample values and draft edits. The draft itself
 * belongs to the page (passed as a getter). Construct during component init.
 */
export class NoteTypeEditorState {
	newKind = $state('');
	previewKind = $state('');
	previewSide = $state<'front' | 'back'>('front');
	furigana = $state(true);
	pitch = $state(false);
	mnemonic = $state(tn('nt.preview.sampleMnemonic'));
	samples = $state<Record<string, string>>({});

	readonly #draft: () => NoteTypeDraft;

	// Re-rendered on every edit (draft, samples, toggles): the live preview.
	readonly preview = $derived.by((): Preview | null => {
		if (this.previewKind === '') return null;
		const draft = this.#draft();
		try {
			const card = renderCard({
				noteType: toCoreNoteType(draft),
				kind: this.previewKind,
				fields: Object.fromEntries(draft.fields.map((f) => [f.key, this.samples[f.key] ?? ''])),
				mnemonic: this.mnemonic,
				side: this.previewSide,
				options: previewOptions(this.furigana, this.pitch)
			});
			return { ok: true, card };
		} catch (e) {
			// The renderer reports template problems as warnings; a throw here is a host-side bug.
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});

	constructor(draft: () => NoteTypeDraft) {
		this.#draft = draft;

		// Keep sample values in step with the field list: keep what the user typed, seed new keys.
		// Depends on the draft only; the current samples are read untracked (never depend on what you write).
		$effect(() => {
			const d = draft();
			const seeded = sampleFields(d);
			const keys = d.fields.map((f) => f.key);
			untrack(() => {
				const samples = this.samples;
				const next: Record<string, string> = {};
				let changed = false;
				for (const key of keys) {
					const cur = samples[key];
					next[key] = cur ?? seeded[key] ?? '';
					if (cur === undefined) changed = true;
				}
				if (changed || Object.keys(samples).length !== keys.length) this.samples = next;
			});
		});

		// The previewed kind must exist. Depends on the kind list only.
		$effect(() => {
			const kinds = draft().kinds.map((k) => k.kind);
			untrack(() => {
				if (!kinds.includes(this.previewKind)) this.previewKind = kinds[0] ?? '';
			});
		});
	}

	addField(): void {
		const draft = this.#draft();
		let n = draft.fields.length + 1;
		while (draft.fields.some((f) => f.key === `field_${n}`)) n++;
		draft.fields.push({ key: `field_${n}`, label: '', kind: 'text', saved: false });
	}

	removeField(i: number): void {
		this.#draft().fields.splice(i, 1);
	}

	addKind(): void {
		const draft = this.#draft();
		const k = this.newKind.trim();
		if (!KEY_PATTERN.test(k)) {
			pushToast(tn('nt.kind.invalid'), 'error');
			return;
		}
		if (draft.kinds.some((x) => x.kind === k)) {
			pushToast(tn('nt.kind.exists', { kind: k }), 'error');
			return;
		}
		draft.kinds.push({ kind: k, enabled: false, front: '', back: '' });
		this.newKind = '';
		this.previewKind = k;
	}

	/** The first validation problem as text, or null when the draft can be submitted. */
	problemText(): string | null {
		const p = validate(this.#draft());
		if (!p) return null;
		switch (p.code) {
			case 'name':
				return tn('nt.nameRequired');
			case 'fields':
				return tn('nt.fieldsRequired');
			case 'fieldKey':
				return tn('nt.field.invalidKey');
			case 'fieldDup':
				return tn('nt.field.dupKey', { key: p.key });
			case 'kindKey':
				return tn('nt.kind.invalid');
			case 'noDefault':
				return tn('nt.kind.noDefault');
		}
	}

	/** Validate, toast the problem, and report whether the page may submit. */
	canSubmit(): boolean {
		const msg = this.problemText();
		if (msg) {
			pushToast(msg, 'error');
			return false;
		}
		return true;
	}
}
