<script lang="ts">
	import { onMount } from 'svelte';
	import { apiConfig } from '../../../lib/api/client';
	import {
		DictionaryApi,
		JpdbVocabResolutionPutResolutionEnum,
		MediaApi
	} from '../../../lib/api/generated';
	import type {
		DictEntrySummary,
		JpdbVocab,
		JpdbVocabResolutionPut,
		JpdbVocabResolutionResult
	} from '../../../lib/api/generated';
	import { tm } from './messages';

	let {
		vocab,
		onResolved
	}: { vocab: JpdbVocab; onResolved: (res: JpdbVocabResolutionResult) => void } = $props();

	const dictApi = new DictionaryApi(apiConfig);
	const mediaApi = new MediaApi(apiConfig);

	let query = $state('');
	let candidates = $state<DictEntrySummary[]>([]);
	let searching = $state(false);
	let busy = $state(false);

	async function search(): Promise<void> {
		const q = query.trim();
		if (q === '') {
			candidates = [];
			return;
		}
		searching = true;
		try {
			candidates = (await dictApi.searchDictionary({ q })).items;
		} catch {
			// The API client already showed an error toast.
		} finally {
			searching = false;
		}
	}
	onMount(() => {
		// Pre-fill with the spelling (or the reading when there is none) and show candidates.
		query = vocab.spelling !== '' ? vocab.spelling : vocab.reading;
		void search();
	});

	async function put(body: JpdbVocabResolutionPut): Promise<void> {
		if (busy) return;
		busy = true;
		try {
			const res = await mediaApi.putJpdbVocabResolution({
				jpdbVid: vocab.jpdbVid,
				jpdbVocabResolutionPut: body
			});
			onResolved(res);
		} catch {
			// The API client already showed an error toast.
		} finally {
			busy = false;
		}
	}
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
				void search();
			}}
		>
			<label>{tm('map.search')} <input type="search" lang="ja" bind:value={query} /></label>
			<button class="kb-btn kb-btn-quiet" type="submit" disabled={searching}>
				{searching ? tm('map.searching') : tm('map.searchButton')}
			</button>
		</form>
		{#if !searching && candidates.length === 0}
			<p>{tm('map.noMatches')}</p>
		{:else}
			<ul>
				{#each candidates as c (c.seq)}
					<li>
						<button
							class="kb-btn kb-btn-quiet"
							type="button"
							disabled={busy}
							onclick={() =>
								void put({
									resolution: JpdbVocabResolutionPutResolutionEnum.Mapped,
									entrySeq: c.seq
								})}
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
				disabled={busy}
				onclick={() =>
					void put({ resolution: JpdbVocabResolutionPutResolutionEnum.Ignored })}
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
