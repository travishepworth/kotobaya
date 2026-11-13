<script lang="ts">
	// Decks: grouped list rows (your decks, smart decks, lists, media) with sticky group headers and a filter once
	// the list gets long.
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import ListRow from '../../lib/ui/ListRow.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import type { IconName } from '../../lib/ui/icons';
	import { td } from '../../lib/decks/messages';
	import { DecksListPage, type Deck } from '../../lib/state/decks.svelte';
	import { dk, type DeckViewKey } from './messages';

	const s = new DecksListPage();
	$effect(() => {
		s.start();
	});

	let filter = $state('');
	const GROUPS: { type: string; title: DeckViewKey; icon: IconName }[] = [
		{ type: 'user', title: 'dk.group.user', icon: 'decks' },
		{ type: 'smart', title: 'dk.group.smart', icon: 'search' },
		{ type: 'list', title: 'dk.group.list', icon: 'browse' },
		{ type: 'media', title: 'dk.group.media', icon: 'media' }
	];
	const shown = $derived(
		filter.trim() === '' ? s.items : s.items.filter((d) => d.name.toLowerCase().includes(filter.trim().toLowerCase()))
	);
	const groups = $derived(
		GROUPS.map((g) => ({ ...g, decks: shown.filter((d) => (GROUPS.some((x) => x.type === d.type) ? d.type : 'user') === g.type) })).filter(
			(g) => g.decks.length > 0
		)
	);

	function supporting(d: Deck): string {
		const parts = [dk('dk.itemsCount', { n: d.itemCount.toLocaleString() })];
		if (d.priority !== 0) parts.push(dk('dk.priority', { n: d.priority }));
		return parts.join(' · ');
	}
</script>

<svelte:head><title>{td('decks.title')} · Kotobaya</title></svelte:head>

<Page title={td('decks.title')} lead={dk('dk.lead')} width="measure">
	{#snippet actions()}<Button variant="primary" icon="plus" href="/decks/new">{td('decks.new')}</Button>{/snippet}

	{#if !s.loaded}
		<Skeleton shape="row" count={8} />
	{:else if s.loadFailed}
		<LoadError size="page" onretry={() => s.retry()} />
	{:else if s.items.length === 0}
		<EmptyState size="page" art="decks" title={dk('dk.emptyTitle')} body={dk('dk.emptyBody')}>
			{#snippet action()}
				<div class="row-actions">
					<Button variant="primary" icon="plus" href="/decks/new">{td('decks.create')}</Button>
					<Button variant="outline" icon="import" href="/import">{dk('dk.import')}</Button>
				</div>
			{/snippet}
		</EmptyState>
	{:else}
		{#if s.items.length > 12}
			<div class="filter">
				<TextField label={dk('dk.filter')} hideLabel type="search" icon="search" clearable placeholder={dk('dk.filter')} bind:value={filter} />
			</div>
		{/if}
		{#if groups.length === 0}
			<EmptyState art="search" title={dk('dk.noMatch', { q: filter.trim() })} />
		{/if}
		{#each groups as g (g.type)}
			<section class="group" aria-labelledby="g-{g.type}">
				<h2 id="g-{g.type}"><span>{dk(g.title)}</span><span class="n">{g.decks.length}</span></h2>
				<div class="rows">
					{#each g.decks as d (d.id)}
						<ListRow title={d.name} supporting={supporting(d)} href="/decks/{d.id}">
							{#snippet leading()}<span class="disc" class:off={!d.enabled}><Icon name={g.icon} size={18} /></span>{/snippet}
							{#snippet trailing()}{#if !d.enabled}<span class="paused">{dk('dk.off')}</span>{/if}{/snippet}
						</ListRow>
					{/each}
				</div>
			</section>
		{/each}
	{/if}
</Page>

<style>
	.filter {
		margin-bottom: var(--kb-space-4);
		max-width: 24rem;
	}
	.group {
		margin-bottom: var(--kb-space-6);
	}
	h2 {
		position: sticky;
		top: 0;
		z-index: 1;
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		padding: var(--kb-space-2) var(--kb-space-1);
		background: var(--kb-color-bg);
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	/* Phones: the sticky header sits under the shell's top bar. */
	@media (max-width: 599.98px) {
		h2 {
			top: var(--kb-size-top-bar);
		}
	}
	.n {
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		letter-spacing: 0;
	}
	.rows {
		display: flex;
		flex-direction: column;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		padding: var(--kb-space-1);
	}
	.disc {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.disc.off {
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
	.paused {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.row-actions {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: var(--kb-space-2);
	}
</style>
