<script lang="ts">
	import { page } from '$app/state';
	import Editor from '../../../lib/note-types/Editor.svelte';
	import { tn } from '../../../lib/note-types/messages';
	import { NoteTypeEditPage } from '../../../lib/state/noteTypes.svelte';

	const id = $derived(page.params.id ?? '');
	const s = new NoteTypeEditPage();

	$effect(() => {
		if (id) s.load(id);
	});

	function remove(): void {
		if (!s.draft || !confirm(tn('nt.confirmDelete', { name: s.draft.name }))) return;
		void s.remove();
	}
</script>

<p><a href="/note-types">{tn('nt.back')}</a></p>
{#if s.missing}
	<p>{tn('nt.notFound')}</p>
{:else if s.draft}
	<h1>
		{s.draft.name}
		{#if s.builtin}<small>({tn('nt.builtin')})</small>{/if}
	</h1>
	<Editor
		bind:draft={s.draft}
		isNew={false}
		busy={s.busy}
		submitLabel={tn('nt.save')}
		onsubmit={() => void s.save()}
		ondelete={s.builtin ? undefined : remove}
	/>
{:else}
	<p>{tn('nt.loading')}</p>
{/if}
