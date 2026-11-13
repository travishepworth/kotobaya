<script lang="ts">
	import { onMount } from 'svelte';
	import { apiConfig } from '../../../lib/api/client';
	import { ListJpdbVocabStatusEnum, MediaApi } from '../../../lib/api/generated';
	import type { Job, JpdbVocab, JpdbVocabResolutionResult } from '../../../lib/api/generated';
	import MappingRow from './MappingRow.svelte';
	import { tm } from './messages';
	import type { MapMessageKey } from './messages';

	const api = new MediaApi(apiConfig);
	const PAGE_SIZE = 50;
	const KNOWN_STATUSES: readonly string[] = ['queued', 'running', 'succeeded', 'failed', 'cancelled'];

	interface Republish {
		job: Job;
		title: string;
	}

	let items = $state<JpdbVocab[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let loaded = $state(false);
	// Re-publish jobs started by corrections made on this page; statuses are as returned by the API.
	let republish = $state<Republish[]>([]);

	async function load(more: boolean): Promise<void> {
		if (loading) return;
		loading = true;
		try {
			const page = await api.listJpdbVocab({
				status: ListJpdbVocabStatusEnum.Unresolved,
				limit: PAGE_SIZE,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			// Keyset pages can repeat a row when totals change; keep the first occurrence.
			const seen = new Set(items.map((v) => v.jpdbVid));
			const fresh = page.items.filter((v) => !seen.has(v.jpdbVid));
			items = more ? [...items, ...fresh] : fresh;
			cursor = page.nextCursor;
		} catch {
			// The API client already showed an error toast.
		} finally {
			loading = false;
			loaded = true;
		}
	}
	onMount(() => void load(false));

	function titleFor(res: JpdbVocabResolutionResult, job: Job): string {
		const mediaId = job.payload['media_id'];
		return res.vocab.media.find((m) => m.mediaId === mediaId)?.title ?? String(mediaId ?? '');
	}

	function statusLabel(s: string): string {
		return KNOWN_STATUSES.includes(s) ? tm(`map.status.${s}` as MapMessageKey) : tm('map.status.other');
	}

	// A resolved row leaves the review queue; its re-publish jobs are listed below the table.
	function resolved(res: JpdbVocabResolutionResult): void {
		items = items.filter((v) => v.jpdbVid !== res.vocab.jpdbVid);
		const incoming = res.jobs.map((job) => ({ job, title: titleFor(res, job) }));
		const incomingIds = new Set(incoming.map((r) => r.job.id));
		republish = [...republish.filter((r) => !incomingIds.has(r.job.id)), ...incoming];
	}
</script>

<h1>{tm('map.title')}</h1>
<p><a href="/import">{tm('map.back')}</a></p>
<p>{tm('map.intro')}</p>

{#if loaded && items.length === 0 && cursor === null}
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
			{#each items as v (v.jpdbVid)}
				<MappingRow vocab={v} onResolved={resolved} />
			{/each}
		</tbody>
	</table>
	{#if cursor !== null}
		<p>
			<button
				class="kb-btn kb-btn-quiet"
				type="button"
				disabled={loading}
				onclick={() => void load(true)}>{tm('map.loadMore')}</button
			>
		</p>
	{/if}
{/if}

{#if republish.length > 0}
	<h2>{tm('map.republishHeading')}</h2>
	<ul>
		{#each republish as r (r.job.id)}
			<li>{tm('map.republish', { title: r.title, status: statusLabel(r.job.status) })}</li>
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
