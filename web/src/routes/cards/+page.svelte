<script lang="ts">
	// Browse › Cards: search-language query, a windowed table with one row per note (each card of the note as a chip
	// that opens it, with its own checkbox), selection and bulk actions, and the selected card rendered in the detail pane.
	import { onMount } from 'svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import QueryField from '../../lib/ui/QueryField.svelte';
	import VirtualTable from '../../lib/ui/VirtualTable.svelte';
	import { kindLabel, stateLabel, stateOf } from '../../lib/ui/cardState';
	import type { TableColumn } from '../../lib/ui/kinds';
	import {
		CardsPage,
		earliestDue,
		maxLapses,
		rowLabel,
		rowSelection,
		type Card,
		type NoteRow
	} from '../../lib/state/cards.svelte';
	import BrowseLayout from './BrowseLayout.svelte';
	import BulkBar from './BulkBar.svelte';
	import CardDetail from './CardDetail.svelte';
	import { tb } from './messages';

	const s = new CardsPage();
	onMount(() => s.start());

	const columns: TableColumn[] = [
		{ key: 'word', label: tb('br.col.word'), lang: 'ja' },
		{ key: 'cards', label: tb('br.col.cards'), width: 'minmax(0, 1.6fr)' },
		{ key: 'due', label: tb('br.col.due'), width: '7.5rem', optional: true },
		{ key: 'lapses', label: tb('br.col.lapses'), width: '5.5rem', align: 'end', optional: true }
	];

	const allLoaded = $derived(s.cards.length > 0 && s.cards.every((c) => s.selected.has(c.id)));

	function day(d: Date | null): string {
		if (!d) return '–';
		return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
	}
	function cardSummary(c: Card): string {
		return tb('br.cardSummary', { kind: kindLabel(c.kind), state: stateLabel(c), due: day(c.dueAt), lapses: c.lapses });
	}
	function openCard(e: Event, c: Card): void {
		e.stopPropagation();
		s.activate(c);
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

{#snippet cell(row: NoteRow<Card>, col: TableColumn)}
	{#if col.key === 'word'}
		{label(row.cards[0])}
	{:else if col.key === 'cards'}
		<span class="cards">
			{#each row.cards as c (c.id)}
				<span class="card" class:on={c.id === s.activeId}>
					{#if row.cards.length > 1}
						<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
						<label class="pick" onclick={(e) => e.stopPropagation()}>
							<input
								type="checkbox"
								aria-label={tb('br.selectCard', { kind: kindLabel(c.kind) })}
								checked={s.allMatching || s.selected.has(c.id)}
								disabled={s.allMatching}
								onchange={() => s.toggle(c)}
							/>
						</label>
					{/if}
					<button
						type="button"
						class="open"
						title={cardSummary(c)}
						aria-label={tb('br.openCard', { summary: cardSummary(c) })}
						aria-pressed={c.id === s.activeId}
						onclick={(e) => openCard(e, c)}
					>
						<span class="muted kind">{kindLabel(c.kind)}</span>
						<span class="state-chip"><Chip kind="state" state={stateOf(c)} label={stateLabel(c)} /></span>
					</button>
				</span>
			{/each}
		</span>
	{:else if col.key === 'due'}
		<span class="muted kb-num">{day(earliestDue(row))}</span>
	{:else}
		{@const lapses = maxLapses(row)}
		<span class="kb-num" class:hot={lapses >= 4}>{lapses}</span>
	{/if}
{/snippet}

{#snippet master()}
	<VirtualTable
		label={tb('br.cards')}
		{columns}
		rows={s.rows}
		rowKey={(r) => r.noteId}
		{cell}
		activeKey={s.active?.noteId ?? null}
		onactivate={(r) => s.activateRow(r)}
		selection={{
			has: (r) => s.allMatching || rowSelection(r, s.selected) === 'all',
			toggle: (r) => s.toggleNote(r),
			all: s.allMatching || allLoaded,
			toggleAll: () => s.togglePage(),
			disabled: s.allMatching,
			label: tb('br.selectRow'),
			allLabel: tb('br.selectAll')
		}}
		loading={!s.loadedOnce || (s.loading && s.rows.length === 0)}
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
		<p class="count">
			{s.cursor !== null
				? tb('br.loadedNotesMore', { n: s.rows.length, c: s.cards.length })
				: tb('br.loadedNotes', { n: s.rows.length, c: s.cards.length })}
		</p>
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
	.cards {
		display: flex;
		flex-wrap: nowrap;
		gap: var(--kb-space-2);
		min-width: 0;
		overflow: hidden;
	}
	.card {
		display: inline-flex;
		align-items: center;
		flex: 0 1 auto;
		min-width: 0;
		border-radius: var(--kb-radius-full);
	}
	.card.on {
		outline: 2px solid var(--kb-color-primary);
	}
	.pick {
		display: inline-flex;
		align-items: center;
		padding-inline: var(--kb-space-1);
		cursor: pointer;
	}
	.open {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-width: 0;
		padding: 0;
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		cursor: pointer;
	}
	.kind {
		white-space: nowrap;
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
