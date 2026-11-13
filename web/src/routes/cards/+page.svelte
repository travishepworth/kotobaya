<script lang="ts">
	// Browse › Cards: search-language query, a windowed table with selection and bulk actions, and the selected
	// card rendered for real in the detail pane.
	import { onMount } from 'svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import QueryField from '../../lib/ui/QueryField.svelte';
	import VirtualTable from '../../lib/ui/VirtualTable.svelte';
	import { kindLabel, stateLabel, stateOf } from '../../lib/ui/cardState';
	import type { TableColumn } from '../../lib/ui/kinds';
	import { CardsPage, rowLabel, type Card } from '../../lib/state/cards.svelte';
	import BrowseLayout from './BrowseLayout.svelte';
	import BulkBar from './BulkBar.svelte';
	import CardDetail from './CardDetail.svelte';
	import { tb } from './messages';

	const s = new CardsPage();
	onMount(() => s.start());

	const columns: TableColumn[] = [
		{ key: 'word', label: tb('br.col.word'), lang: 'ja' },
		{ key: 'kind', label: tb('br.col.kind'), width: '7.5rem', optional: true },
		{ key: 'state', label: tb('br.col.state'), width: '8.5rem' },
		{ key: 'due', label: tb('br.col.due'), width: '7.5rem', optional: true },
		{ key: 'lapses', label: tb('br.col.lapses'), width: '5.5rem', align: 'end', optional: true }
	];

	const allLoaded = $derived(s.cards.length > 0 && s.cards.every((c) => s.selected.has(c.id)));

	function due(c: Card): string {
		if (!c.dueAt) return '–';
		return c.dueAt.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
	}
	// Dictionary-backed notes are named by their headword; others by their first field.
	function label(c: Card): string {
		const hw = c.note?.entrySeq != null ? s.headwords.get(c.note.entrySeq) : undefined;
		return hw ?? rowLabel(c.note, c.noteId, s.headwords);
	}
	function clearSearch(): void {
		s.draft = '';
		s.search();
	}
</script>

<svelte:head><title>{tb('br.cards')} · Kotobaya</title></svelte:head>

{#snippet toolbar()}
	<div class="tools">
		<div class="q">
			<QueryField
				label={tb('br.query')}
				hideLabel
				bind:value={s.draft}
				vocabulary={s.vocabulary}
				placeholder={tb('br.queryPlaceholder')}
				hint={tb('br.queryHint')}
				serverError={s.serverError}
				completionsLabel={tb('br.completions')}
				onsubmit={() => s.search()}
			/>
		</div>
		<Button variant="primary" onclick={() => s.search()}>{tb('br.search')}</Button>
	</div>
	{#if s.hasSelection}
		<div class="bulk">
			<BulkBar
				selector={s.selector}
				scopeText={s.allMatching ? tb('br.selectedAll') : tb('br.selected', { n: s.selected.size })}
				decks={s.userDecks}
				canSelectMatching={!s.allMatching && s.appliedQuery !== ''}
				onselectmatching={() => s.selectAllMatching()}
				onclear={() => s.clearSelection()}
				onapplied={() => s.applied()}
			/>
		</div>
	{/if}
{/snippet}

{#snippet cell(c: Card, col: TableColumn)}
	{#if col.key === 'word'}
		{label(c)}
	{:else if col.key === 'kind'}
		<span class="muted">{kindLabel(c.kind)}</span>
	{:else if col.key === 'state'}
		<span class="state-chip"><Chip kind="state" state={stateOf(c)} label={stateLabel(c)} /></span>
	{:else if col.key === 'due'}
		<span class="muted kb-num">{due(c)}</span>
	{:else}
		<span class="kb-num" class:hot={c.lapses >= 4}>{c.lapses}</span>
	{/if}
{/snippet}

{#snippet master()}
	<VirtualTable
		label={tb('br.cards')}
		{columns}
		rows={s.cards}
		rowKey={(c) => c.id}
		{cell}
		activeKey={s.activeId}
		onactivate={(c) => s.activate(c)}
		selection={{
			has: (c) => s.allMatching || s.selected.has(c.id),
			toggle: (c) => s.toggle(c),
			all: s.allMatching || allLoaded,
			toggleAll: () => s.togglePage(),
			disabled: s.allMatching,
			label: tb('br.selectRow'),
			allLabel: tb('br.selectAll')
		}}
		loading={!s.loadedOnce || (s.loading && s.cards.length === 0)}
		hasMore={s.cursor !== null}
		onnearend={() => void s.loadPage(false)}
		height="min(40rem, calc(100dvh - 16rem))"
	>
		{#snippet empty()}
			{#if s.loadFailed}
				<LoadError onretry={() => s.retry()} />
			{:else if s.appliedQuery !== ''}
				<EmptyState art="search" title={tb('br.noneTitle')} body={tb('br.noneBody')}>
					{#snippet action()}<Button variant="outline" onclick={clearSearch}>{tb('br.clearSearch')}</Button>{/snippet}
				</EmptyState>
			{:else}
				<EmptyState art="review" title={tb('br.emptyTitle')} body={tb('br.emptyBody')}>
					{#snippet action()}<Button variant="tonal" icon="search" href="/dictionary">{tb('br.toDictionary')}</Button>{/snippet}
				</EmptyState>
			{/if}
		{/snippet}
	</VirtualTable>
	{#if s.loadFailed && s.cards.length > 0}
		<div class="more-error"><LoadError size="banner" onretry={() => s.retry()} /></div>
	{/if}
	{#if s.cards.length > 0}
		<p class="count">{s.cursor !== null ? tb('br.loadedMore', { n: s.cards.length }) : tb('br.loaded', { n: s.cards.length })}</p>
	{/if}
{/snippet}

{#snippet detail()}
	{#if s.active}
		<CardDetail card={s.active} noteType={s.noteTypeOf(s.active)} onheadword={(seq, h) => s.setHeadword(seq, h)} />
	{/if}
{/snippet}

{#snippet placeholder()}
	<EmptyState art="review" title={tb('br.pickCard')} body={tb('br.pickCardBody')} />
{/snippet}

<BrowseLayout
	view="cards"
	{toolbar}
	{master}
	detail={s.active ? detail : null}
	{placeholder}
	detailTitle={s.active ? label(s.active) : tb('br.preview')}
	onclose={() => (s.activeId = null)}
/>

<style>
	.tools {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		gap: var(--kb-space-2);
	}
	.q {
		flex: 1 1 20rem;
		min-width: 0;
	}
	.bulk {
		margin-top: var(--kb-space-3);
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	/* Narrow tables (phones at large text): the state chip keeps its glyph and colour; the word is read out. */
	@container (max-width: 24rem) {
		.state-chip :global(.label) {
			position: absolute;
			width: 1px;
			height: 1px;
			overflow: hidden;
			clip-path: inset(50%);
		}
	}
	.hot {
		color: var(--kb-color-danger);
		font-weight: 600;
	}
	.more-error {
		margin-top: var(--kb-space-3);
	}
	.count {
		margin-top: var(--kb-space-2);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
