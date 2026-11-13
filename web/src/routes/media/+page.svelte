<script lang="ts">
	// Reading library: media as cards (initial, title, kind, parse state), filters, rename/reparse/delete per card.
	import { onMount } from 'svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Menu from '../../lib/ui/Menu.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { tm, type MediaMessageKey } from '../../lib/media/messages';
	import { MEDIA_KINDS, MediaListPage, type Media } from '../../lib/state/media.svelte';
	import { mx } from './messages';

	const vm = new MediaListPage();
	let loaded = $state(false);
	onMount(() => void vm.load(false).then(() => (loaded = true)));
	let deleting = $state<Media | null>(null);

	const kindOptions = [{ value: '', label: tm('med.filter.all') }, ...MEDIA_KINDS.map((k) => ({ value: k, label: tm(`med.kind.${k}` as MediaMessageKey) }))];
	let searchTimer: ReturnType<typeof setTimeout> | undefined;
	function onsearch(): void {
		clearTimeout(searchTimer);
		searchTimer = setTimeout(() => void vm.load(false), 250);
	}
	const filtered = $derived(vm.kind !== '' || vm.q.trim() !== '');
</script>

<svelte:head><title>{tm('med.title')} · Kotobaya</title></svelte:head>

<Page title={tm('med.title')} lead={mx('mx.lead')}>
	{#snippet actions()}<Button variant="primary" icon="plus" href="/media/new">{tm('med.new')}</Button>{/snippet}

	<div class="filters">
		<div class="q"><TextField label={mx('mx.search')} hideLabel type="search" icon="search" clearable placeholder={mx('mx.search')} bind:value={vm.q} oninput={onsearch} /></div>
		<div class="kind"><Select label={tm('med.filter.kind')} hideLabel options={kindOptions} bind:value={vm.kind} onchange={() => void vm.load(false)} /></div>
	</div>

	{#if !loaded}
		<div class="grid"><Skeleton shape="block" height="9rem" /><Skeleton shape="block" height="9rem" /><Skeleton shape="block" height="9rem" /></div>
	{:else if vm.items.length === 0}
		{#if filtered}
			<EmptyState art="search" title={mx('mx.noMatch')} />
		{:else}
			<EmptyState size="page" art="media" title={mx('mx.emptyTitle')} body={mx('mx.emptyBody')}>
				{#snippet action()}<Button variant="primary" icon="plus" href="/media/new">{tm('med.new')}</Button>{/snippet}
			</EmptyState>
		{/if}
	{:else}
		<ul class="grid">
			{#each vm.items as m (m.id)}
				<li class="card">
					{#if vm.editing === m.id}
						<form
							class="rename"
							onsubmit={(e) => {
								e.preventDefault();
								void vm.saveRename(m);
							}}
						>
							<TextField label={tm('med.titleLabel')} bind:value={vm.draftTitle} lang="ja" />
							<div class="row">
								<Button size="sm" variant="quiet" onclick={() => vm.cancelRename()}>{tm('med.cancel')}</Button>
								<Button size="sm" variant="primary" type="submit">{tm('med.save')}</Button>
							</div>
						</form>
					{:else}
						<a class="body" href="/media/{m.id}">
							<span class="cover" lang="ja" aria-hidden="true">{[...m.title][0] ?? '?'}</span>
							<span class="text">
								<span class="title" lang="ja">{m.title}</span>
								<span class="meta">{tm(`med.kind.${m.kind}` as MediaMessageKey)} · {tm(`med.src.${m.source}` as MediaMessageKey)}{#if m.segments?.length} · {mx('mx.segments', { n: m.segments.length })}{/if}</span>
								<span class="status {m.parseStatus}">{tm(`med.parse.${m.parseStatus}` as MediaMessageKey)}</span>
							</span>
						</a>
						<div class="menu">
							<Menu
								label={mx('mx.actions', { title: m.title })}
								items={[
									{ label: tm('med.rename'), icon: 'edit', onselect: () => vm.startRename(m) },
									...(m.source !== 'jpdb' ? [{ label: tm('med.reparse'), icon: 'sync' as const, onselect: () => void vm.reparse(m) }] : []),
									{ label: tm('med.delete'), icon: 'trash', danger: true, onselect: () => (deleting = m) }
								]}
							/>
						</div>
					{/if}
				</li>
			{/each}
		</ul>
		{#if vm.cursor}
			<div class="more"><Button variant="quiet" loading={vm.loading} onclick={() => void vm.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
</Page>

<Dialog open={deleting !== null} title={mx('mx.deleteTitle', { title: deleting?.title ?? '' })} onclose={() => (deleting = null)}>
	<p>{tm('med.confirmDelete', { title: deleting?.title ?? '' })}</p>
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (deleting = null)}>{ut('app.cancel')}</Button>
		<Button
			variant="danger"
			onclick={() => {
				if (deleting) void vm.remove(deleting);
				deleting = null;
			}}>{tm('med.delete')}</Button
		>
	{/snippet}
</Dialog>

<style>
	.filters {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-5);
	}
	.q {
		flex: 1 1 16rem;
		min-width: 0;
		max-width: 28rem;
	}
	.kind {
		flex: 0 1 12rem;
		min-width: 0;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 19rem), 1fr));
		gap: var(--kb-space-3);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.card {
		position: relative;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		transition: box-shadow var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.card:hover {
		box-shadow: var(--kb-elevation-1);
	}
	.body {
		display: flex;
		gap: var(--kb-space-4);
		padding: var(--kb-space-4) var(--kb-space-12) var(--kb-space-4) var(--kb-space-4);
		color: var(--kb-color-text);
		text-decoration: none;
		border-radius: inherit;
	}
	.cover {
		display: grid;
		place-items: center;
		flex: none;
		width: 4rem;
		height: 5.25rem;
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		font: 2rem/1 var(--kb-family-serif);
	}
	.text {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		min-width: 0;
	}
	.title {
		font: var(--kb-type-title-sm);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.meta {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.status {
		align-self: flex-start;
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.status.parsed {
		background: var(--kb-color-success-container);
		color: var(--kb-color-on-success-container);
	}
	.status.failed {
		background: var(--kb-color-danger-container);
		color: var(--kb-color-on-danger-container);
	}
	.menu {
		position: absolute;
		top: var(--kb-space-2);
		right: var(--kb-space-2);
	}
	.rename {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		padding: var(--kb-space-4);
	}
	.row {
		display: flex;
		justify-content: flex-end;
		gap: var(--kb-space-2);
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-4);
	}
</style>
