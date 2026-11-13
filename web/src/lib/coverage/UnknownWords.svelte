<script lang="ts">
	import { DecksApi, WordsApi } from '../api/generated';
	import type { DeckItem } from '../api/generated';
	import { apiConfig } from '../api/client';
	import { pushToast } from '../toasts.svelte';
	import { tc } from './messages';

	let { deckId, onchanged }: { deckId: string; onchanged: () => void } = $props();

	const decks = new DecksApi(apiConfig);
	const words = new WordsApi(apiConfig);

	let sort = $state<'occurrence_count' | 'freq_rank'>('occurrence_count');
	let items = $state<DeckItem[]>([]);
	let cursor = $state<string | null>(null);
	let loaded = $state(false);

	async function load(more: boolean): Promise<void> {
		try {
			const p = await decks.listDeckItems({
				deckId,
				status: new Set(['new'] as const),
				sort,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			items = more ? [...items, ...p.items] : p.items;
			cursor = p.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			loaded = true;
		}
	}

	$effect(() => {
		void sort;
		void deckId;
		void load(false);
	});

	async function mark(item: DeckItem, status: 'known' | 'ignored' | 'never_forget'): Promise<void> {
		if (item.entrySeq == null) return;
		try {
			await words.setWordStatus({ seq: item.entrySeq, wordStatusSet: { status } });
			items = items.filter((x) => x.itemKey !== item.itemKey);
			pushToast(tc('cov.marked'), 'info');
			onchanged();
		} catch {
			/* toast shown */
		}
	}
</script>

<section>
	<h2>{tc('cov.words')}</h2>
	<label>
		{tc('cov.sort')}
		<select bind:value={sort}>
			<option value="occurrence_count">{tc('cov.sort.count')}</option>
			<option value="freq_rank">{tc('cov.sort.rank')}</option>
		</select>
	</label>
	{#if !loaded}
		<p>{tc('cov.loading')}</p>
	{:else if items.length === 0}
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
				{#each items as item (item.itemKey)}
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
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => mark(item, 'known')}>{tc('cov.markKnown')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => mark(item, 'ignored')}>{tc('cov.markIgnore')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => mark(item, 'never_forget')}>{tc('cov.markNeverForget')}</button>
							{/if}
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
		{#if cursor}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={() => load(true)}>{tc('cov.more')}</button>
		{/if}
	{/if}
</section>
