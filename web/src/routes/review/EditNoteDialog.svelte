<script lang="ts">
	// Edit the note behind the current review card without leaving Review: the note type's fields and the mnemonic.
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import TextArea from '../../lib/ui/TextArea.svelte';
	import { ut } from '../../lib/ui/messages';
	import { NoteEditFormState, type Note, type NoteSummary, type NoteType } from '../../lib/state/review.svelte';
	import { rv } from './messages';

	interface Props {
		note: NoteSummary;
		noteType: NoteType | undefined;
		onsaved: (n: Note) => void;
		oncancel: () => void;
	}
	let { note, noteType, onsaved, oncancel }: Props = $props();

	const s = new NoteEditFormState(
		() => note,
		() => noteType
	);
	let open = $state(true);

	function save(e?: Event): void {
		e?.preventDefault();
		void s.save(onsaved);
	}

	function onkeydown(e: KeyboardEvent): void {
		if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save(e);
	}
</script>

<Dialog bind:open title={rv('rv.edit.title')} size="lg" onclose={oncancel}>
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<form id="review-edit" class="form" onsubmit={save} {onkeydown}>
		{#each s.keys as f (f.key)}
			<TextArea label={f.label} bind:value={s.fields[f.key]} rows={2} />
		{/each}
		<TextArea label={rv('rv.edit.mnemonic')} hint={rv('rv.edit.mnemonicHint')} bind:value={s.mnemonic} rows={3} />
	</form>
	{#snippet actions()}
		<Button variant="quiet" onclick={oncancel}>{ut('app.cancel')}</Button>
		<Button variant="primary" type="submit" form="review-edit" loading={s.busy}>{rv('rv.edit.save')}</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding-top: var(--kb-space-2);
		color: var(--kb-color-text);
	}
</style>
