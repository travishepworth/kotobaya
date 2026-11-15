<script lang="ts">
	// The items of a user or list deck as rows (headword, reading, gloss); user decks can add and remove items.
	import WordStatusActions from '../../../lib/components/WordStatusActions.svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import Chip from '../../../lib/ui/Chip.svelte';
	import Select from '../../../lib/ui/Select.svelte';
	import DictionaryLink from '../../../lib/ui/DictionaryLink.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import LoadError from '../../../lib/ui/LoadError.svelte';
	import IconButton from '../../../lib/ui/IconButton.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import TextField from '../../../lib/ui/TextField.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { td } from '../../../lib/decks/messages';
	import { DECK_FILTERS, DeckItemsState, deckItemLabel, type Deck, type DeckSort } from '../../../lib/state/decks.svelte';
	import { dk } from '../messages';

	interface Props {
		deck: Deck;
	}
	let { deck }: Props = $props();
	const s = new DeckItemsState(() => deck);

	const sorts = $derived<DeckSort[]>(deck.type === 'media' ? ['position', 'freq_rank', 'occurrence_count'] : ['position', 'freq_rank']);
	let sort = $state<DeckSort>('position');

	function add(e: Event): void {
		e.preventDefault();
		void s.add();
	}
</script>

{#if deck.type !== 'smart'}
	<div class="toolbar">
		<div class="chips" role="group" aria-label={td('decks.filter.label')}>
			{#each DECK_FILTERS as f (f)}
				<Chip kind="filter" label={td(`decks.filter.${f}`)} selected={s.filter === f} onclick={() => void s.setView(f, s.sort)} />
			{/each}
		</div>
		<Select
			label={td('decks.sort.label')}
			bind:value={sort}
			options={sorts.map((v) => ({ value: v, label: td(`decks.sort.${v}`) }))}
			onchange={(v) => void s.setView(s.filter, v)}
		/>
	</div>
	{#if s.items.some((i) => s.hasStatus(i))}
		<div class="bulk">
			<Button variant="quiet" size="sm" onclick={() => s.toggleAll()}>{td('decks.selectAll')}</Button>
			{#if s.selected.size > 0}
				<span class="count">{td('decks.selected', { n: s.selected.size })}</span>
				<Button variant="outline" size="sm" disabled={s.progress !== null} onclick={() => void s.bulk({ kind: 'set', status: 'known' })}>
					{td('decks.act.known')}
				</Button>
				<Button variant="outline" size="sm" disabled={s.progress !== null} onclick={() => void s.bulk({ kind: 'set', status: 'ignored' })}>
					{td('decks.act.ignored')}
				</Button>
				<Button variant="outline" size="sm" disabled={s.progress !== null} onclick={() => void s.bulk({ kind: 'reset' })}>
					{td('decks.act.reset')}
				</Button>
			{/if}
			{#if s.progress}<span class="count" role="status">{td('decks.bulkProgress', { done: s.progress.done, total: s.progress.total })}</span>{/if}
		</div>
	{/if}
{/if}

<Panel flush>
	{#if !s.loaded}
		<div class="pad"><Skeleton shape="row" count={6} /></div>
	{:else if s.loadFailed && s.items.length === 0}
		<LoadError onretry={() => s.retry()} />
	{:else if s.items.length === 0 && s.filter !== 'all'}
		<EmptyState art="decks" title={td('decks.itemsFilteredEmpty')} />
	{:else if s.items.length === 0}
		<EmptyState art="decks" title={dk('dk.itemsEmpty')} body={deck.type === 'list' ? td('decks.itemsListZero') : undefined} />
	{:else}
		<ul class="items">
			{#each s.items as it (it.itemKey)}
				<li>
					{#if s.hasStatus(it)}
						<input
							type="checkbox"
							class="pick"
							aria-label={td('decks.select', { text: it.entry?.headword ?? deckItemLabel(it) })}
							checked={s.selected.has(it.itemKey)}
							onchange={() => s.toggle(it.itemKey)}
						/>
					{/if}
					{#if it.entry}
						<div class="text">
							<p class="line">
								<span class="hw" lang="ja">{it.entry.headword}</span>
								{#if it.entry.headword !== it.entry.headwordReading}<span class="rd" lang="ja">{it.entry.headwordReading}</span>{/if}
							</p>
							<p class="gloss">{it.entry.glosses.slice(0, 3).join('; ')}</p>
						</div>
					{:else}
						<p class="text">{deckItemLabel(it)}</p>
					{/if}
					{#if it.status != null && s.hasStatus(it)}
						<span class="badge">{td(`decks.status.${it.status}`)}</span>
						<WordStatusActions
							status={it.status}
							seq={it.entrySeq}
							canMine={it.entry != null && it.entry.noteId == null}
							disabled={s.pending.has(it.itemKey)}
							onaction={(a) => void s.act(it, a)}
							onmine={() => void s.mine(it)}
						/>
					{/if}
					<DictionaryLink seq={it.entrySeq} text={it.entry?.headword} />
					{#if s.editable}
						<IconButton icon="close" label={td('decks.itemRemove')} onclick={() => void s.remove(it)} />
					{/if}
				</li>
			{/each}
		</ul>
		{#if s.loadFailed}
			<div class="more-error"><LoadError size="banner" onretry={() => s.retry()} /></div>
		{:else if s.cursor !== null}
			<div class="more"><Button variant="quiet" onclick={() => void s.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
</Panel>

{#if s.editable}
	<form class="add" onsubmit={add} aria-label={dk('dk.addItems')}>
		<TextField label={dk('dk.addEntry')} inputmode="numeric" bind:value={s.entrySeq} />
		<TextField label={dk('dk.addNote')} bind:value={s.noteId} />
		<Button variant="tonal" icon="plus" type="submit" loading={s.busy}>{td('decks.itemsAdd')}</Button>
	</form>
{/if}


<style>
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: end;
		justify-content: space-between;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
	}
	.bulk {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-3);
	}
	.count {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.pick {
		flex: none;
		width: 1.25rem;
		height: 1.25rem;
	}
	.badge {
		flex: none;
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.add {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
		align-items: end;
		gap: var(--kb-space-3);
		margin-top: var(--kb-space-5);
	}
	.pad {
		padding: var(--kb-space-3) var(--kb-space-4);
	}
	.items {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: 3.5rem;
		padding: var(--kb-space-2) var(--kb-space-2) var(--kb-space-2) var(--kb-space-4);
		border-bottom: 1px solid var(--kb-color-border);
	}
	li:last-child {
		border-bottom: 0;
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	.line > * {
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.line {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 var(--kb-space-3);
	}
	.hw {
		min-width: 0;
		font: var(--kb-type-title-sm);
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.rd {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.gloss {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.more-error {
		padding: var(--kb-space-3);
	}
	.more {
		display: flex;
		justify-content: center;
		padding: var(--kb-space-2);
	}
</style>
