<script lang="ts">
	import { onMount } from 'svelte';
	import { jobStatusLabel, MappingPage } from '../../../lib/state/import.svelte';
	import MappingRow from './MappingRow.svelte';
	import { tm } from './messages';

	const vm = new MappingPage();
	onMount(() => void vm.load(false));
</script>

<h1>{tm('map.title')}</h1>
<p><a href="/import">{tm('map.back')}</a></p>
<p>{tm('map.intro')}</p>

{#if vm.loaded && vm.items.length === 0 && vm.cursor === null}
	<p role="status">{tm('map.empty')}</p>
{:else}
	<table>
		<thead>
			<tr>
				<th>{tm('map.col.spelling')}</th><th>{tm('map.col.reading')}</th>
				<th>{tm('map.col.occurrences')}</th><th>{tm('map.col.media')}</th>
				<th>{tm('map.col.resolve')}</th>
			</tr>
		</thead>
		<tbody>
			{#each vm.items as v (v.jpdbVid)}
				<MappingRow vocab={v} onResolved={(res) => vm.resolved(res)} />
			{/each}
		</tbody>
	</table>
	{#if vm.cursor !== null}
		<p>
			<button
				class="kb-btn kb-btn-quiet"
				type="button"
				disabled={vm.loading}
				onclick={() => void vm.load(true)}>{tm('map.loadMore')}</button
			>
		</p>
	{/if}
{/if}

{#if vm.republish.length > 0}
	<h2>{tm('map.republishHeading')}</h2>
	<ul>
		{#each vm.republish as r (r.job.id)}
			<li>{tm('map.republish', { title: r.title, status: jobStatusLabel(r.job.status) })}</li>
		{/each}
	</ul>
{/if}

<style>
	table {
		border-collapse: collapse;
		margin-bottom: var(--kb-space-4);
	}
	th {
		text-align: left;
		padding: var(--kb-space-1) var(--kb-space-3);
		border-bottom: 1px solid var(--kb-border);
	}
</style>
