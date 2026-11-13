<script lang="ts">
	import { onDestroy } from 'svelte';
	import { apiConfig } from '../../lib/api/client';
	import { BlobsApi, JobsApi, MediaApi, PutBlobKindEnum } from '../../lib/api/generated';
	import type { Job } from '../../lib/api/generated';
	import { openEventStream } from '../../lib/events';
	import { listOf, sha256Hex, splitCounters } from './counters';
	import type { UnmappedKanji, UnmappedVocab } from './counters';
	import { ti } from './messages';

	const blobsApi = new BlobsApi(apiConfig);
	const jobsApi = new JobsApi(apiConfig);
	const mediaApi = new MediaApi(apiConfig);

	let file = $state<File | null>(null);
	let importReviews = $state(true);
	let importWordStatus = $state(true);
	let uploading = $state(false);
	let job = $state<Job | null>(null);
	let closeStream: (() => void) | null = null;

	function isRunning(j: Job | null): boolean {
		return j !== null && (j.status === 'queued' || j.status === 'running');
	}

	// Job.result is passed through raw (snake_case), so unknown extra counters survive.
	const result = $derived<Record<string, unknown> | null>(
		job && job.status === 'succeeded' ? (job.result ?? null) : null
	);
	const counters = $derived(result ? splitCounters(result) : null);
	const unmapped = $derived(result ? listOf<UnmappedVocab>(result, 'unmapped') : []);
	const kanjiUnmapped = $derived(result ? listOf<UnmappedKanji>(result, 'kanji_unmapped') : []);

	function stop(): void {
		closeStream?.();
		closeStream = null;
	}
	onDestroy(stop);

	async function refresh(id: string): Promise<void> {
		try {
			job = await jobsApi.getJob({ jobId: id });
			if (!isRunning(job)) stop();
		} catch {
			// The API client already showed an error toast.
		}
	}

	function follow(id: string): void {
		stop();
		closeStream = openEventStream({
			types: ['job.updated'],
			onEvent: (e) => {
				if (e.type === 'job.updated' && e.data['id'] === id) void refresh(id);
			},
			// No replay after a reconnect: refetch the job.
			onReconnect: () => void refresh(id)
		});
	}

	async function start(): Promise<void> {
		if (!file || uploading) return;
		uploading = true;
		try {
			const bytes = await file.arrayBuffer();
			const hash = await sha256Hex(bytes);
			await blobsApi.putBlob({
				kind: PutBlobKindEnum.Upload,
				hash,
				// The generated model named Blob shadows the DOM Blob in this parameter's type.
				body: new Blob([bytes], { type: 'application/json' }) as never
			});
			const started = await mediaApi.importJpdbHistory({
				jpdbHistoryImportRequest: { blobHash: hash, importReviews, importWordStatus }
			});
			job = started;
			if (isRunning(started)) follow(started.id);
		} catch {
			// The API client already showed an error toast.
		} finally {
			uploading = false;
		}
	}

	function reset(): void {
		stop();
		job = null;
		file = null;
	}
</script>

<h1>{ti('imp.title')}</h1>

{#if job === null}
	<p>{ti('imp.intro')}</p>
	<p><a href="/import/mapping">{ti('imp.mapping')}</a></p>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			void start();
		}}
	>
		<p>
			<label
				>{ti('imp.file')}
				<input
					type="file"
					accept=".json,application/json"
					onchange={(e) => (file = e.currentTarget.files?.[0] ?? null)}
				/></label
			>
		</p>
		<p><label><input type="checkbox" bind:checked={importReviews} /> {ti('imp.reviews')}</label></p>
		<p>
			<label><input type="checkbox" bind:checked={importWordStatus} /> {ti('imp.status')}</label>
		</p>
		<button class="kb-btn" type="submit" disabled={!file || uploading}>
			{uploading ? ti('imp.uploading') : ti('imp.start')}
		</button>
	</form>
{:else if isRunning(job)}
	<p role="status">
		{ti('imp.progress', { status: job.status, percent: Math.round(job.progress * 100) })}
	</p>
	<progress max="1" value={job.progress}></progress>
	{#if job.progressMessage}<p>{job.progressMessage}</p>{/if}
{:else if job.status === 'succeeded' && counters}
	<p role="status">{ti('imp.done')}</p>
	<h2>{ti('imp.counters')}</h2>
	<table>
		<tbody>
			{#each counters.known as c (c.key)}
				<tr><th scope="row">{c.key}</th><td>{c.value}</td></tr>
			{/each}
		</tbody>
	</table>
	{#if counters.other.length > 0}
		<h3>{ti('imp.counter.other')}</h3>
		<table>
			<tbody>
				{#each counters.other as c (c.key)}
					<tr><th scope="row">{c.key}</th><td>{c.value}</td></tr>
				{/each}
			</tbody>
		</table>
	{/if}

	<h2>{ti('imp.unmapped', { count: unmapped.length })}</h2>
	{#if unmapped.length === 0}
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
				{#each unmapped as u, i (i)}
					<tr>
						<td lang="ja">{u.spelling}</td><td lang="ja">{u.reading}</td>
						<td>{u.review_count}</td><td>{u.jpdb_vid}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}

	<h2>{ti('imp.kanjiUnmapped', { count: kanjiUnmapped.length })}</h2>
	{#if kanjiUnmapped.length === 0}
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
				{#each kanjiUnmapped as k, i (i)}
					<tr><td lang="ja">{k.character}</td><td>{k.card_kind}</td><td>{k.review_count}</td></tr>
				{/each}
			</tbody>
		</table>
	{/if}
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={reset}>{ti('imp.reset')}</button></p>
{:else}
	<p role="alert">
		{job.status === 'failed'
			? ti('imp.failed', { error: job.error ?? ti('imp.noError') })
			: ti('imp.cancelled')}
	</p>
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={reset}>{ti('imp.reset')}</button></p>
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
