<script lang="ts">
	import { DeckItemsState, deckItemLabel, type Deck } from '../state/decks.svelte';
	import { td } from './messages';

	let { deck }: { deck: Deck } = $props();

	const s = new DeckItemsState(() => deck);

	function add(e: SubmitEvent): void {
		e.preventDefault();
		void s.add();
	}
</script>

<section>
	<h2>{td('decks.itemsTitle')}</h2>
	{#if deck.type === 'smart'}
		<p class="help">{td('decks.itemsSmart')}</p>
	{:else}
		{#if deck.type === 'list'}
			<p class="help">{td('decks.itemsList')}</p>
			{#if deck.itemCount === 0}<p class="help">{td('decks.itemsListZero')}</p>{/if}
		{:else if deck.type === 'media'}
			<p class="help">{td('decks.itemsMedia')}</p>
		{/if}

		{#if !s.loaded}
			<p>{td('decks.loading')}</p>
		{:else if s.items.length === 0}
			<p>{td('decks.itemsNone')}</p>
		{:else}
			<ul>
				{#each s.items as item (item.itemKey)}
					<li>
						<span lang="ja">{deckItemLabel(item)}</span>
						{#if s.editable}
							<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.remove(item)}>
								{td('decks.itemRemove')}
							</button>
						{/if}
					</li>
				{/each}
			</ul>
			{#if s.cursor}
				<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.load(true)}>
					{td('decks.itemsMore')}
				</button>
			{/if}
		{/if}

		{#if s.editable}
			<form onsubmit={add}>
				<h3>{td('decks.itemsAddHeading')}</h3>
				<label>
					{td('decks.itemsAddEntry')}
					<input type="text" inputmode="numeric" bind:value={s.entrySeq} disabled={s.noteId !== ''} />
				</label>
				<label>
					{td('decks.itemsAddNote')}
					<input type="text" bind:value={s.noteId} disabled={s.entrySeq !== ''} />
				</label>
				<button class="kb-btn" type="submit" disabled={s.busy}>{td('decks.itemsAdd')}</button>
			</form>
		{/if}
	{/if}
</section>

<style>
	.help {
		color: var(--kb-text-muted);
	}
	form {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 28rem;
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
</style>
