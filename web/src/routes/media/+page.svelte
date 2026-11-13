<script lang="ts">
	import { onMount } from 'svelte';
	import { apiConfig } from '../../lib/api/client';
	import { MediaApi, MediaKind } from '../../lib/api/generated';
	import type { Media } from '../../lib/api/generated';
	import { pushToast } from '../../lib/toasts.svelte';
	import { tm } from '../../lib/media/messages';
	import type { MediaMessageKey } from '../../lib/media/messages';

	const api = new MediaApi(apiConfig);
	const kinds = Object.values(MediaKind);

	let items = $state<Media[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let kind = $state<MediaKind | ''>('');
	let q = $state('');
	let editing = $state<string | null>(null);
	let draftTitle = $state('');

	async function load(more: boolean): Promise<void> {
		if (loading) return;
		loading = true;
		try {
			const page = await api.listMedia({
				kind: kind || undefined,
				q: q.trim() || undefined,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			items = more ? [...items, ...page.items] : page.items;
			cursor = page.nextCursor;
		} catch {
			// The API client already showed an error toast.
		} finally {
			loading = false;
		}
	}
	onMount(() => void load(false));

	function startRename(m: Media): void {
		editing = m.id;
		draftTitle = m.title;
	}

	async function saveRename(m: Media): Promise<void> {
		const title = draftTitle.trim();
		if (!title) return;
		try {
			const updated = await api.updateMedia({ mediaId: m.id, mediaPatch: { title } });
			items = items.map((x) => (x.id === m.id ? { ...x, ...updated } : x));
			editing = null;
		} catch {
			// toast shown by the client
		}
	}

	async function reparse(m: Media): Promise<void> {
		try {
			await api.reparseMedia({ mediaId: m.id });
			pushToast(tm('med.reparseQueued'), 'info');
		} catch {
			// toast shown by the client
		}
	}

	async function remove(m: Media): Promise<void> {
		if (!confirm(tm('med.confirmDelete', { title: m.title }))) return;
		try {
			await api.deleteMedia({ mediaId: m.id });
			items = items.filter((x) => x.id !== m.id);
			pushToast(tm('med.deleted'), 'info');
		} catch {
			// toast shown by the client
		}
	}
</script>

<h1>{tm('med.title')}</h1>
<p><a class="kb-btn" href="/media/new">{tm('med.new')}</a></p>

<form
	onsubmit={(e) => {
		e.preventDefault();
		void load(false);
	}}
>
	<label
		>{tm('med.filter.kind')}
		<select bind:value={kind} onchange={() => void load(false)}>
			<option value="">{tm('med.filter.all')}</option>
			{#each kinds as k (k)}
				<option value={k}>{tm(`med.kind.${k}` as MediaMessageKey)}</option>
			{/each}
		</select></label
	>
	<label>{tm('med.filter.title')} <input type="search" bind:value={q} /></label>
</form>

{#if items.length === 0 && !loading}
	<p>{tm('med.empty')}</p>
{:else}
	<table>
		<thead>
			<tr>
				<th>{tm('med.col.title')}</th><th>{tm('med.col.kind')}</th>
				<th>{tm('med.col.source')}</th><th>{tm('med.col.parse')}</th>
				<th>{tm('med.col.segments')}</th><th>{tm('med.col.actions')}</th>
			</tr>
		</thead>
		<tbody>
			{#each items as m (m.id)}
				<tr>
					<td lang="ja">
						{#if editing === m.id}
							<form
								onsubmit={(e) => {
									e.preventDefault();
									void saveRename(m);
								}}
							>
								<input aria-label={tm('med.titleLabel')} bind:value={draftTitle} />
								<button class="kb-btn" type="submit">{tm('med.save')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => (editing = null)}
									>{tm('med.cancel')}</button
								>
							</form>
						{:else}
							<a href={`/media/${m.id}`}>{m.title}</a>
						{/if}
					</td>
					<td>{tm(`med.kind.${m.kind}` as MediaMessageKey)}</td>
					<td>{tm(`med.src.${m.source}` as MediaMessageKey)}</td>
					<td>{tm(`med.parse.${m.parseStatus}` as MediaMessageKey)}</td>
					<td>{m.segments?.length ?? 0}</td>
					<td>
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => startRename(m)}
							>{tm('med.rename')}</button
						>
						{#if m.source !== 'jpdb'}
							<button class="kb-btn kb-btn-quiet" type="button" onclick={() => void reparse(m)}
								>{tm('med.reparse')}</button
							>
						{/if}
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => void remove(m)}
							>{tm('med.delete')}</button
						>
					</td>
				</tr>
			{/each}
		</tbody>
	</table>
{/if}
{#if cursor}
	<button class="kb-btn" type="button" disabled={loading} onclick={() => void load(true)}
		>{tm('med.more')}</button
	>
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
