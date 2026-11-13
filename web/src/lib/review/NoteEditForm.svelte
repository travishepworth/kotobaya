<script lang="ts">
	// Inline note edit: the note type's fields and the mnemonic, saved with updateNote.
	import { NoteEditFormState, type Note, type NoteSummary, type NoteType } from '../state/review.svelte';
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

	const s = new NoteEditFormState(
		() => note,
		() => noteType
	);
	let first: HTMLTextAreaElement | undefined = $state();

	$effect(() => {
		first?.focus();
	});

	function save(e: Event): void {
		e.preventDefault();
		void s.save(onsaved);
	}

	function onkeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			oncancel();
		} else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
			save(e);
		}
	}
</script>

<svelte:window {onkeydown} />

<form class="edit" onsubmit={save} aria-label={tr('review.edit.title')}>
	<h2>{tr('review.edit.title')}</h2>
	{#each s.keys as f, i (f.key)}
		<label>
			{f.label}
			{#if i === 0}
				<textarea rows="2" bind:value={s.fields[f.key]} bind:this={first}></textarea>
			{:else}
				<textarea rows="2" bind:value={s.fields[f.key]}></textarea>
			{/if}
		</label>
	{/each}
	<label>
		{tr('review.edit.mnemonic')}
		<textarea rows="2" bind:value={s.mnemonic}></textarea>
	</label>
	<div class="row">
		<button class="kb-btn" type="submit" disabled={s.busy}>
			{s.busy ? tr('review.edit.saving') : tr('review.edit.save')}
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
