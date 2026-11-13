<script lang="ts">
	import { page } from '$app/state';
	import Button from '../../../lib/ui/Button.svelte';
	import Dialog from '../../../lib/ui/Dialog.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { tn } from '../../../lib/note-types/messages';
	import { NoteTypeEditPage } from '../../../lib/state/noteTypes.svelte';
	import Editor from '../Editor.svelte';
	import { nt } from '../messages';

	const id = $derived(page.params.id ?? '');
	const s = new NoteTypeEditPage();
	$effect(() => {
		if (id) s.load(id);
	});
	let confirming = $state(false);
</script>

<svelte:head><title>{s.draft?.name ?? tn('nt.title')} · Kotobaya</title></svelte:head>

{#if s.missing}
	<Page title={tn('nt.title')} back={{ href: '/note-types', label: tn('nt.title') }} width="measure">
		<EmptyState size="page" art="noteTypes" title={tn('nt.notFound')} />
	</Page>
{:else if s.draft}
	<Page title={s.draft.name} eyebrow={s.builtin ? tn('nt.builtin') : undefined} back={{ href: '/note-types', label: tn('nt.title') }}>
		{#snippet actions()}
			{#if !s.builtin}<Button variant="quiet" icon="trash" onclick={() => (confirming = true)}>{tn('nt.delete')}</Button>{/if}
		{/snippet}
		<Editor bind:draft={s.draft} isNew={false} busy={s.busy} submitLabel={tn('nt.save')} onsubmit={() => void s.save()} />
	</Page>
	<Dialog bind:open={confirming} title={nt('nt2.deleteTitle', { name: s.draft.name })}>
		<p>{nt('nt2.dangerBody')}</p>
		{#snippet actions()}
			<Button variant="quiet" onclick={() => (confirming = false)}>{ut('app.cancel')}</Button>
			<Button variant="danger" onclick={() => void s.remove()}>{tn('nt.delete')}</Button>
		{/snippet}
	</Dialog>
{:else}
	<Page title={tn('nt.title')} back={{ href: '/note-types', label: tn('nt.title') }} hideTitle>
		<Skeleton shape="block" height="20rem" />
	</Page>
{/if}
