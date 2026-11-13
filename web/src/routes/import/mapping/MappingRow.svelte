<script lang="ts">
	// One unresolved jpdb word as a card: the word, where it occurs, dictionary candidates to map it to, or Ignore.
	import { onMount } from 'svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import TextField from '../../../lib/ui/TextField.svelte';
	import { MappingRowState, type JpdbVocab, type JpdbVocabResolutionResult } from '../../../lib/state/import.svelte';
	import { tm } from './messages';

	interface Props {
		vocab: JpdbVocab;
		onResolved: (res: JpdbVocabResolutionResult) => void;
	}
	let { vocab, onResolved }: Props = $props();

	const vm = new MappingRowState(
		() => vocab,
		() => onResolved
	);
	onMount(() => vm.start());
</script>

<li class="item">
	<div class="word">
		<p class="sp" lang="ja">{vocab.spelling || vocab.reading}</p>
		{#if vocab.spelling && vocab.reading !== vocab.spelling}<p class="rd" lang="ja">{vocab.reading}</p>{/if}
		<p class="occ kb-num">{tm('map.occurrences', { n: vocab.occurrenceTotal })}</p>
		<ul class="media">
			{#each vocab.media as m (m.mediaId)}<li lang="ja">{tm('map.mediaCount', { title: m.title, count: m.occurrenceCount })}</li>{/each}
		</ul>
	</div>
	<div class="resolve">
		<form
			class="search"
			onsubmit={(e) => {
				e.preventDefault();
				void vm.search();
			}}
		>
			<TextField label={tm('map.search')} hideLabel type="search" icon="search" lang="ja" bind:value={vm.query} />
			<Button variant="outline" type="submit" loading={vm.searching}>{tm('map.searchButton')}</Button>
		</form>
		{#if vm.searching && vm.candidates.length === 0}
			<Skeleton shape="text" count={2} />
		{:else if vm.candidates.length === 0}
			<p class="muted">{tm('map.noMatches')}</p>
		{:else}
			<ul class="cands" aria-label={tm('map.candidates', { word: vocab.spelling || vocab.reading })}>
				{#each vm.candidates.slice(0, 6) as c (c.seq)}
					<li>
						<button type="button" disabled={vm.busy} onclick={() => void vm.map(c.seq)}>
							<span class="hw" lang="ja">{c.headword}</span>
							<span class="r" lang="ja">{c.headwordReading}</span>
							<span class="g">{c.glosses.slice(0, 3).join('; ')}</span>
						</button>
					</li>
				{/each}
			</ul>
		{/if}
		<div class="ignore"><Button size="sm" variant="quiet" disabled={vm.busy} onclick={() => void vm.ignore()}>{tm('map.ignore')}</Button></div>
	</div>
</li>

<style>
	.item {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-4);
		padding: var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	@container (min-width: 44rem) {
		.item {
			grid-template-columns: 14rem minmax(0, 1fr);
		}
	}
	.sp {
		font: var(--kb-type-jp-display);
		font-size: 1.75rem;
	}
	.rd {
		color: var(--kb-color-text-muted);
	}
	.occ {
		margin-top: var(--kb-space-1);
		font: var(--kb-type-body-sm);
	}
	.media {
		margin: var(--kb-space-1) 0 0;
		padding: 0;
		list-style: none;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.resolve {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
	}
	.search {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: var(--kb-space-2);
	}
	.cands {
		display: flex;
		flex-direction: column;
		gap: 2px;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.cands button {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 var(--kb-space-3);
		width: 100%;
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-2) var(--kb-space-3);
		border: 1px solid transparent;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text);
		text-align: start;
		cursor: pointer;
	}
	.cands button:hover {
		border-color: var(--kb-color-primary);
		background: var(--kb-color-surface-selected);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-weight: 500;
	}
	.r,
	.g {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.g {
		flex-basis: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.ignore {
		display: flex;
		justify-content: flex-end;
	}
</style>
