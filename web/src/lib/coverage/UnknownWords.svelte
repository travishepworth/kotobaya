<script lang="ts">
	import { UnknownWordsState } from '../state/media.svelte';
	import { tc } from './messages';

	let { deckId, onchanged }: { deckId: string; onchanged: () => void } = $props();

	const vm = new UnknownWordsState(() => deckId);
</script>

<section>
	<h2>{tc('cov.words')}</h2>
	<label>
		{tc('cov.sort')}
		<select bind:value={vm.sort}>
			<option value="occurrence_count">{tc('cov.sort.count')}</option>
			<option value="freq_rank">{tc('cov.sort.rank')}</option>
		</select>
	</label>
	{#if !vm.loaded}
		<p>{tc('cov.loading')}</p>
	{:else if vm.items.length === 0}
		<p>{tc('cov.noWords')}</p>
	{:else}
		<table>
			<thead>
				<tr>
					<th>{tc('cov.col.word')}</th>
					<th>{tc('cov.col.count')}</th>
					<th>{tc('cov.col.rank')}</th>
					<th>{tc('cov.col.actions')}</th>
				</tr>
			</thead>
			<tbody>
				{#each vm.items as item (item.itemKey)}
					<tr>
						<td lang="ja">
							{#if item.entry}
								<a href={`/dictionary?q=${encodeURIComponent(item.entry.headword)}`}>{item.entry.headword}</a>
								({item.entry.headwordReading}): {item.entry.glosses.slice(0, 3).join('; ')}
							{:else}
								{tc('cov.noEntry')}
							{/if}
						</td>
						<td>{item.occurrenceCount}</td>
						<td>{item.entry?.freqRank ?? ''}</td>
						<td>
							{#if item.entrySeq != null}
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.mark(item, 'known', onchanged)}>{tc('cov.markKnown')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.mark(item, 'ignored', onchanged)}>{tc('cov.markIgnore')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.mark(item, 'never_forget', onchanged)}>{tc('cov.markNeverForget')}</button>
							{/if}
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
		{#if vm.cursor}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.load(true)}>{tc('cov.more')}</button>
		{/if}
	{/if}
</section>
