<script lang="ts">
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import ListRow from '../../lib/ui/ListRow.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import { tn } from '../../lib/note-types/messages';
	import { NoteTypesListPage, type NoteType } from '../../lib/state/noteTypes.svelte';
	import { nt } from './messages';

	const s = new NoteTypesListPage();
	$effect(() => {
		s.start();
	});
	const groups = $derived([
		{ title: nt('nt2.yours'), items: s.items.filter((n) => !n.builtin) },
		{ title: nt('nt2.builtins'), items: s.items.filter((n) => n.builtin) }
	]);
	function supporting(n: NoteType): string {
		return `${tn(`nt.base.${n.baseType}`)} · ${tn('nt.fieldCount', { n: n.fields.length })} · ${tn('nt.kindCount', { n: Object.keys(n.templates).length })}`;
	}
</script>

<svelte:head><title>{tn('nt.title')} · Kotobaya</title></svelte:head>

<Page title={tn('nt.title')} lead={nt('nt2.lead')} width="measure">
	{#snippet actions()}<Button variant="primary" icon="plus" href="/note-types/new">{tn('nt.new')}</Button>{/snippet}
	{#if !s.loaded}
		<Skeleton shape="row" count={6} />
	{:else if s.items.length === 0}
		<EmptyState size="page" art="noteTypes" title={nt('nt2.emptyTitle')} />
	{:else}
		{#each groups as g (g.title)}
			{#if g.items.length > 0}
				<section class="group">
					<h2>{g.title}</h2>
					<div class="rows">
						{#each g.items as n (n.id)}
							<ListRow title={n.name} supporting={supporting(n)} href="/note-types/{n.id}">
								{#snippet leading()}<span class="disc"><Icon name="noteTypes" size={18} /></span>{/snippet}
							</ListRow>
						{/each}
					</div>
				</section>
			{/if}
		{/each}
	{/if}
</Page>

<style>
	.group {
		margin-bottom: var(--kb-space-6);
	}
	h2 {
		padding: var(--kb-space-2) var(--kb-space-1);
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.rows {
		display: flex;
		flex-direction: column;
		padding: var(--kb-space-1);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.disc {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
</style>
