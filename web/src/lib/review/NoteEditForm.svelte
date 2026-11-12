<script lang="ts">
	// Inline note edit: the note type's fields and the mnemonic, saved with updateNote.
	import type { Note, NoteSummary, NoteType } from '../api/generated';
	import { notesApi } from './api';
	import { tr } from './messages';

	let {
		note,
		noteType,
		onsaved,
		oncancel
	}: {
		note: NoteSummary;
		noteType: NoteType | undefined;
		onsaved: (n: Note) => void;
		oncancel: () => void;
	} = $props();

	// The form edits a copy; the props are only the starting point.
	/* svelte-ignore state_referenced_locally */
	let fields = $state<Record<string, string>>({ ...note.fields });
	/* svelte-ignore state_referenced_locally */
	let mnemonic = $state(note.mnemonic);
	let busy = $state(false);
	let first: HTMLTextAreaElement | undefined = $state();

	const keys = $derived(
		noteType
			? noteType.fields.map((f) => ({ key: f.key, label: f.label }))
			: Object.keys(note.fields).map((k) => ({ key: k, label: k }))
	);

	$effect(() => {
		first?.focus();
	});

	async function save(e: Event): Promise<void> {
		e.preventDefault();
		if (busy) return;
		busy = true;
		try {
			const changed: Record<string, string> = {};
			for (const { key } of keys) {
				if ((fields[key] ?? '') !== (note.fields[key] ?? '')) changed[key] = fields[key] ?? '';
			}
			const n = await notesApi.updateNote({
				noteId: note.id,
				notePatch: {
					...(Object.keys(changed).length > 0 ? { fields: changed } : {}),
					...(mnemonic !== note.mnemonic ? { mnemonic } : {})
				}
			});
			onsaved(n);
		} catch {
			/* toast shown by the client */
		} finally {
			busy = false;
		}
	}

	function onkeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			oncancel();
		} else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
			void save(e);
		}
	}
</script>

<svelte:window {onkeydown} />

<form class="edit" onsubmit={save} aria-label={tr('review.edit.title')}>
	<h2>{tr('review.edit.title')}</h2>
	{#each keys as f, i (f.key)}
		<label>
			{f.label}
			{#if i === 0}
				<textarea rows="2" bind:value={fields[f.key]} bind:this={first}></textarea>
			{:else}
				<textarea rows="2" bind:value={fields[f.key]}></textarea>
			{/if}
		</label>
	{/each}
	<label>
		{tr('review.edit.mnemonic')}
		<textarea rows="2" bind:value={mnemonic}></textarea>
	</label>
	<div class="row">
		<button class="kb-btn" type="submit" disabled={busy}>
			{busy ? tr('review.edit.saving') : tr('review.edit.save')}
		</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={oncancel}>{tr('review.edit.cancel')}</button>
	</div>
</form>

<style>
	.edit {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 40rem;
		margin: 1rem auto;
	}
	label {
		display: grid;
		gap: 0.25rem;
	}
	textarea {
		font: inherit;
		width: 100%;
	}
	.row {
		display: flex;
		gap: var(--kb-space-2);
	}
</style>
