<script lang="ts">
	// The items of a user or list deck as rows (headword, reading, gloss); user decks can add and remove items.
	import Button from '../../../lib/ui/Button.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import IconButton from '../../../lib/ui/IconButton.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import TextField from '../../../lib/ui/TextField.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { td } from '../../../lib/decks/messages';
	import { DeckItemsState, deckItemLabel, type Deck } from '../../../lib/state/decks.svelte';
	import { dk } from '../messages';

	interface Props {
		deck: Deck;
	}
	let { deck }: Props = $props();
	const s = new DeckItemsState(() => deck);

	function add(e: Event): void {
		e.preventDefault();
		void s.add();
	}
</script>

<Panel flush>
	{#if !s.loaded}
		<div class="pad"><Skeleton shape="row" count={6} /></div>
	{:else if s.items.length === 0}
		<EmptyState art="decks" title={dk('dk.itemsEmpty')} body={deck.type === 'list' ? td('decks.itemsListZero') : undefined} />
	{:else}
		<ul class="items">
			{#each s.items as it (it.itemKey)}
				<li>
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
					{#if s.editable}
						<IconButton icon="close" label={td('decks.itemRemove')} onclick={() => void s.remove(it)} />
					{/if}
				</li>
			{/each}
		</ul>
		{#if s.cursor !== null}
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
		padding: var(--kb-space-2) var(--kb-space-2) var(--kb-space-2) var(--kb-space-5);
		border-bottom: 1px solid var(--kb-color-border);
	}
	li:last-child {
		border-bottom: 0;
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	.line {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 var(--kb-space-3);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-weight: 500;
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
	.more {
		display: flex;
		justify-content: center;
		padding: var(--kb-space-2);
	}
</style>
