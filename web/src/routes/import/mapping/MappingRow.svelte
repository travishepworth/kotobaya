<script lang="ts">
	import { onMount } from 'svelte';
	import { MappingRowState, type JpdbVocab, type JpdbVocabResolutionResult } from '../../../lib/state/import.svelte';
	import { tm } from './messages';

	let {
		vocab,
		onResolved
	}: { vocab: JpdbVocab; onResolved: (res: JpdbVocabResolutionResult) => void } = $props();

	const vm = new MappingRowState(
		() => vocab,
		() => onResolved
	);
	onMount(() => vm.start());
</script>

<tr>
	<td lang="ja">{vocab.spelling}</td>
	<td lang="ja">{vocab.reading}</td>
	<td>{vocab.occurrenceTotal}</td>
	<td>
		<ul>
			{#each vocab.media as m (m.mediaId)}
				<li lang="ja">{tm('map.mediaCount', { title: m.title, count: m.occurrenceCount })}</li>
			{/each}
		</ul>
	</td>
	<td>
		<form
			onsubmit={(e) => {
				e.preventDefault();
				void vm.search();
			}}
		>
			<label>{tm('map.search')} <input type="search" lang="ja" bind:value={vm.query} /></label>
			<button class="kb-btn kb-btn-quiet" type="submit" disabled={vm.searching}>
				{vm.searching ? tm('map.searching') : tm('map.searchButton')}
			</button>
		</form>
		{#if !vm.searching && vm.candidates.length === 0}
			<p>{tm('map.noMatches')}</p>
		{:else}
			<ul>
				{#each vm.candidates as c (c.seq)}
					<li>
						<button
							class="kb-btn kb-btn-quiet"
							type="button"
							disabled={vm.busy}
							onclick={() => void vm.map(c.seq)}
						>
							<span lang="ja">{c.headword}</span>
							<span lang="ja">{c.headwordReading}</span>
							{c.glosses.slice(0, 3).join('; ')}
						</button>
					</li>
				{/each}
			</ul>
		{/if}
		<p>
			<button
				class="kb-btn kb-btn-quiet"
				type="button"
				disabled={vm.busy}
				onclick={() => void vm.ignore()}
			>
				{tm('map.ignore')}
			</button>
		</p>
	</td>
</tr>

<style>
	td {
		text-align: left;
		vertical-align: top;
		padding: var(--kb-space-1) var(--kb-space-3);
		border-bottom: 1px solid var(--kb-border);
	}
</style>
