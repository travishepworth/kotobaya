<script lang="ts">
	import { onMount } from 'svelte';
	import BulkBar from '../../lib/cards/BulkBar.svelte';
	import CardPreview from '../../lib/cards/CardPreview.svelte';
	import CardTable from '../../lib/cards/CardTable.svelte';
	import SearchBox from '../../lib/cards/SearchBox.svelte';
	import { tc } from '../../lib/cards/messages';
	import { CardsPage } from '../../lib/state/cards.svelte';

	const s = new CardsPage();

	onMount(() => s.start());
</script>

<h1>{tc('cards.title')}</h1>

<SearchBox bind:value={s.draft} vocabulary={s.vocabulary} serverError={s.serverError} onsubmit={() => s.search()} />

<div class="layout">
	<div class="main">
		{#if !s.loadedOnce}
			<p>{tc('cards.loading')}</p>
		{:else if s.cards.length === 0 && !s.serverError}
			<p>{tc('cards.none')}</p>
		{:else}
			<CardTable
				cards={s.cards}
				selected={s.selected}
				allMatching={s.allMatching}
				activeId={s.activeId}
				hasMore={s.cursor !== null}
				headwords={s.headwords}
				onactivate={(c) => s.activate(c)}
				ontoggle={(c) => s.toggle(c)}
				ontogglepage={() => s.togglePage()}
				onnearend={() => void s.loadPage(false)}
			/>
			<p class="muted">
				{tc('cards.loadedCount', { n: s.cards.length })}{#if s.cursor !== null}
					· {tc('cards.moreAvailable')}{/if}
			</p>
		{/if}

		{#if s.hasSelection}
			<p class="sel">
				{s.allMatching ? tc('cards.selectedAll') : tc('cards.selected', { n: s.selected.size })}
				{#if !s.allMatching && s.appliedQuery !== ''}
					<button type="button" class="kb-btn kb-btn-quiet" onclick={() => s.selectAllMatching()}>
						{tc('cards.selectAllMatching')}
					</button>
				{/if}
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => s.clearSelection()}>
					{tc('cards.clearSelection')}
				</button>
			</p>
			<BulkBar selector={s.selector} scopeText={s.scopeText} decks={s.userDecks} onapplied={() => s.applied()} />
		{:else if s.appliedQuery !== '' && s.cards.length > 0}
			<p class="sel">
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => s.selectAllMatching()}>
					{tc('cards.selectAllMatching')}
				</button>
			</p>
		{/if}
	</div>

	<aside>
		<h2>{tc('cards.preview')}</h2>
		{#if s.active}
			<CardPreview card={s.active} onheadword={(seq, h) => s.setHeadword(seq, h)} noteType={s.noteTypeOf(s.active)} />
		{:else}
			<p class="muted">{tc('cards.previewNone')}</p>
		{/if}
	</aside>
</div>

<style>
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 2fr) minmax(16rem, 1fr);
		gap: var(--kb-space-4);
		margin-top: var(--kb-space-4);
		align-items: start;
	}
	.main {
		display: grid;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.sel {
		margin: 0;
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	h2 {
		font-size: 1rem;
	}
	@media (max-width: 60rem) {
		.layout {
			grid-template-columns: 1fr;
		}
	}
</style>
