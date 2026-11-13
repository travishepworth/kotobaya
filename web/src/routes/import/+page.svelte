<script lang="ts">
	import { onDestroy } from 'svelte';
	import { ImportPage, isRunning } from '../../lib/state/import.svelte';
	import { ti } from './messages';

	const vm = new ImportPage();
	onDestroy(() => vm.dispose());
</script>

<h1>{ti('imp.title')}</h1>

{#if vm.job === null}
	<p>{ti('imp.intro')}</p>
	<p><a href="/import/mapping">{ti('imp.mapping')}</a></p>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			void vm.start();
		}}
	>
		<p>
			<label
				>{ti('imp.file')}
				<input
					type="file"
					accept=".json,application/json"
					onchange={(e) => (vm.file = e.currentTarget.files?.[0] ?? null)}
				/></label
			>
		</p>
		<p><label><input type="checkbox" bind:checked={vm.importReviews} /> {ti('imp.reviews')}</label></p>
		<p>
			<label><input type="checkbox" bind:checked={vm.importWordStatus} /> {ti('imp.status')}</label>
		</p>
		<button class="kb-btn" type="submit" disabled={!vm.file || vm.uploading}>
			{vm.uploading ? ti('imp.uploading') : ti('imp.start')}
		</button>
	</form>
{:else if isRunning(vm.job)}
	<p role="status">
		{ti('imp.progress', { status: vm.job.status, percent: Math.round(vm.job.progress * 100) })}
	</p>
	<progress max="1" value={vm.job.progress}></progress>
	{#if vm.job.progressMessage}<p>{vm.job.progressMessage}</p>{/if}
{:else if vm.job.status === 'succeeded' && vm.counters}
	<p role="status">{ti('imp.done')}</p>
	<h2>{ti('imp.counters')}</h2>
	<table>
		<tbody>
			{#each vm.counters.known as c (c.key)}
				<tr><th scope="row">{c.key}</th><td>{c.value}</td></tr>
			{/each}
		</tbody>
	</table>
	{#if vm.counters.other.length > 0}
		<h3>{ti('imp.counter.other')}</h3>
		<table>
			<tbody>
				{#each vm.counters.other as c (c.key)}
					<tr><th scope="row">{c.key}</th><td>{c.value}</td></tr>
				{/each}
			</tbody>
		</table>
	{/if}

	<h2>{ti('imp.unmapped', { count: vm.unmapped.length })}</h2>
	{#if vm.unmapped.length === 0}
		<p>{ti('imp.none')}</p>
	{:else}
		<table>
			<thead>
				<tr>
					<th>{ti('imp.col.spelling')}</th><th>{ti('imp.col.reading')}</th>
					<th>{ti('imp.col.reviews')}</th><th>{ti('imp.col.vid')}</th>
				</tr>
			</thead>
			<tbody>
				{#each vm.unmapped as u, i (i)}
					<tr>
						<td lang="ja">{u.spelling}</td><td lang="ja">{u.reading}</td>
						<td>{u.review_count}</td><td>{u.jpdb_vid}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}

	<h2>{ti('imp.kanjiUnmapped', { count: vm.kanjiUnmapped.length })}</h2>
	{#if vm.kanjiUnmapped.length === 0}
		<p>{ti('imp.none')}</p>
	{:else}
		<table>
			<thead>
				<tr>
					<th>{ti('imp.col.character')}</th><th>{ti('imp.col.kind')}</th>
					<th>{ti('imp.col.reviews')}</th>
				</tr>
			</thead>
			<tbody>
				{#each vm.kanjiUnmapped as k, i (i)}
					<tr><td lang="ja">{k.character}</td><td>{k.card_kind}</td><td>{k.review_count}</td></tr>
				{/each}
			</tbody>
		</table>
	{/if}
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.reset()}>{ti('imp.reset')}</button></p>
{:else}
	<p role="alert">
		{vm.job.status === 'failed'
			? ti('imp.failed', { error: vm.job.error ?? ti('imp.noError') })
			: ti('imp.cancelled')}
	</p>
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.reset()}>{ti('imp.reset')}</button></p>
{/if}

<style>
	table {
		border-collapse: collapse;
		margin-bottom: var(--kb-space-4);
	}
	th,
	td {
		text-align: left;
		padding: var(--kb-space-1) var(--kb-space-3);
		border-bottom: 1px solid var(--kb-border);
	}
</style>
