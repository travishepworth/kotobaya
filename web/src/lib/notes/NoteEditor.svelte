<script lang="ts">
	// Edit pane: fields, tags, mnemonic, source sentence, with a core-rendered preview and delete.
	import type { DictEntry, Note, NotePatch, NoteType } from '../api/generated';
	import { renderCard, type CardSide, type RenderedCard } from '../core/index';
	import CardView from '../review/CardView.svelte';
	import { MEDIA_URL_TEMPLATE, toCoreNoteType, toDictionary } from '../review/model';
	import { pushToast } from '../toasts.svelte';
	import { notesApi } from './api';
	import { tn } from './messages';

	let {
		note,
		noteType,
		entry,
		onsaved,
		ondeleted
	}: {
		note: Note;
		noteType: NoteType | undefined;
		entry: DictEntry | null;
		onsaved: (n: Note) => void;
		ondeleted: (id: string) => void;
	} = $props();

	let fields = $state<Record<string, string>>({});
	let tagsText = $state('');
	let mnemonic = $state('');
	let sourceSentence = $state('');
	let side = $state<CardSide>('front');
	let saving = $state(false);
	let confirming = $state(false);

	// Reset the form whenever another note (or a fresh server copy of it) arrives.
	$effect(() => {
		fields = { ...note.fields };
		tagsText = note.tags.join(' ');
		mnemonic = note.mnemonic;
		sourceSentence = note.sourceSentence;
		confirming = false;
	});
	$effect(() => {
		void note.id;
		side = 'front';
	});

	const fieldKeys = $derived.by(() => {
		const keys = noteType ? noteType.fields.map((f) => f.key) : [];
		for (const k of Object.keys(note.fields)) if (!keys.includes(k)) keys.push(k);
		return keys;
	});
	const referenced = $derived(note.entrySeq !== null || note.kanjiLiteral !== null);

	function parseTags(s: string): string[] {
		return [...new Set(s.split(/\s+/).filter((x) => x !== ''))].sort();
	}

	const tagsValid = $derived(parseTags(tagsText).every((x) => x.length <= 64 && x !== 'leech'));

	type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };
	const rendered = $derived.by((): Rendered => {
		if (!noteType) return { ok: false, detail: tn('notes.previewMissingType') };
		try {
			const kind = note.cards[0]?.kind ?? Object.keys(noteType.templates)[0] ?? '';
			return {
				ok: true,
				card: renderCard({
					noteType: toCoreNoteType(noteType),
					kind,
					fields,
					mnemonic,
					dictionary: toDictionary(entry),
					kanji: null,
					side,
					options: {
						furigana: true,
						pitch: true,
						showMnemonic: true,
						mediaUrlTemplate: MEDIA_URL_TEMPLATE
					}
				})
			};
		} catch (e) {
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});

	async function save(): Promise<void> {
		const patch: NotePatch = {};
		const changed: Record<string, string> = {};
		for (const k of fieldKeys) {
			if ((fields[k] ?? '') !== (note.fields[k] ?? '')) changed[k] = fields[k] ?? '';
		}
		if (Object.keys(changed).length > 0) patch.fields = changed;
		const tags = parseTags(tagsText);
		if (tags.join(' ') !== note.tags.join(' ')) patch.tags = tags;
		if (mnemonic !== note.mnemonic) patch.mnemonic = mnemonic;
		if (sourceSentence !== note.sourceSentence) {
			// One register covers all three source properties: keep the soft references.
			patch.sourceSentence = sourceSentence;
			patch.sourceSentenceId = note.sourceSentenceId;
			patch.sourceMediaId = note.sourceMediaId;
		}
		if (Object.keys(patch).length === 0) {
			pushToast(tn('notes.nothingChanged'), 'info');
			return;
		}
		saving = true;
		try {
			const updated = await notesApi.updateNote({ noteId: note.id, notePatch: patch });
			pushToast(tn('notes.saved'), 'info');
			onsaved(updated);
		} catch {
			/* error toast shown by the client */
		} finally {
			saving = false;
		}
	}

	async function remove(): Promise<void> {
		saving = true;
		try {
			await notesApi.deleteNote({ noteId: note.id });
			pushToast(tn('notes.deleted'), 'info');
			ondeleted(note.id);
		} catch {
			/* error toast shown by the client */
		} finally {
			saving = false;
			confirming = false;
		}
	}
</script>

<section aria-label={tn('notes.edit')}>
	<h2>{tn('notes.edit')}</h2>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			void save();
		}}
	>
		<fieldset>
			<legend>{tn('notes.fields')}</legend>
			{#if referenced}<p class="muted">{tn('notes.refFieldHint')}</p>{/if}
			{#each fieldKeys as k (k)}
				<label>
					{noteType?.fields.find((f) => f.key === k)?.label ?? k}
					<textarea rows="2" lang="ja" bind:value={fields[k]}></textarea>
				</label>
			{/each}
		</fieldset>
		<label>
			{tn('notes.tags')}
			<input type="text" bind:value={tagsText} />
		</label>
		{#if !tagsValid}<p class="err">{tn('notes.badTag')}</p>{/if}
		<label>
			{tn('notes.mnemonic')}
			<textarea rows="2" bind:value={mnemonic}></textarea>
		</label>
		<label>
			{tn('notes.sourceSentence')}
			<textarea rows="2" lang="ja" bind:value={sourceSentence}></textarea>
		</label>
		<div class="actions">
			<button type="submit" class="kb-btn" disabled={saving || !tagsValid}>
				{saving ? tn('notes.saving') : tn('notes.save')}
			</button>
			{#if !confirming}
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (confirming = true)}>
					{tn('notes.delete')}
				</button>
			{/if}
		</div>
	</form>

	{#if confirming}
		<div class="confirm" role="alertdialog" aria-label={tn('notes.delete')}>
			<p>{tn('notes.deleteConfirm')}</p>
			<button type="button" class="kb-btn" disabled={saving} onclick={() => void remove()}>
				{tn('notes.deleteYes')}
			</button>
			<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (confirming = false)}>
				{tn('notes.deleteNo')}
			</button>
		</div>
	{/if}

	<h3>{tn('notes.preview')}</h3>
	<div class="tabs">
		<button
			type="button"
			class="kb-btn kb-btn-quiet"
			aria-pressed={side === 'front'}
			onclick={() => (side = 'front')}>{tn('notes.previewFront')}</button
		>
		<button
			type="button"
			class="kb-btn kb-btn-quiet"
			aria-pressed={side === 'back'}
			onclick={() => (side = 'back')}>{tn('notes.previewBack')}</button
		>
	</div>
	{#if rendered.ok}
		<CardView rendered={rendered.card} />
	{:else}
		<p class="err">{tn('notes.previewFailed', { detail: rendered.detail })}</p>
	{/if}
</section>

<style>
	form {
		display: grid;
		gap: var(--kb-space-3);
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	fieldset {
		display: grid;
		gap: var(--kb-space-2);
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	.actions,
	.tabs {
		display: flex;
		gap: var(--kb-space-2);
	}
	.confirm {
		margin-top: var(--kb-space-2);
		padding: var(--kb-space-2);
		border: 1px solid var(--kb-error);
		border-radius: var(--kb-radius);
	}
	[aria-pressed='true'] {
		font-weight: 700;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.err {
		color: var(--kb-error);
	}
</style>
